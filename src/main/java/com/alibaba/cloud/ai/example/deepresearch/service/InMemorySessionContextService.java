/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.deepresearch.service;

import com.alibaba.cloud.ai.example.deepresearch.model.SessionHistory;
import com.alibaba.cloud.ai.example.deepresearch.model.req.GraphId;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link SessionContextService} 的纯内存实现，维护会话与线程的映射关系及历史元信息。
 *
 * <p>项目职责：在不依赖 Redis 的场景下，通过 {@code ConcurrentHashMap} 维护
 * sessionId → threadId 列表 和 threadId → SessionHistory（不含报告正文）两张内存表。
 * 报告正文单独委托给 {@code ReportService} 存储，避免大对象在内存中堆积；
 * 读取时按需回填，保持惰性加载语义。
 *
 * <p>被使用情况：实现 {@link SessionContextService} 接口，由 Spring 在
 * {@code spring.data.redis.enabled=false}（默认）时自动装配；
 * 被 {@code ReporterNode} 在生成报告后写入历史，被 {@code CoordinatorNode} 和
 * {@code BackgroundInvestigationNode} 读取历史上下文。
 *
 * @author vlsmb
 * @since 2025/8/6
 */
@Service
public class InMemorySessionContextService implements SessionContextService {

	private final ReportService reportService;

	// sessionId → 该会话下所有 threadId（有序，反映对话顺序）
	private final ConcurrentHashMap<String, CopyOnWriteArrayList<String>> sessionThreadMap;

	// threadId → SessionHistory（report 字段留空，正文由 reportService 持有）
	private final ConcurrentHashMap<String, SessionHistory> sessionHistoryMap;

	public InMemorySessionContextService(ReportService reportService) {
		this.reportService = reportService;
		this.sessionThreadMap = new ConcurrentHashMap<>();
		this.sessionHistoryMap = new ConcurrentHashMap<>();
	}

	@Override
	public void addSessionHistory(GraphId graphId, SessionHistory sessionHistory) {
		sessionThreadMap.putIfAbsent(graphId.sessionId(), new CopyOnWriteArrayList<>());
		sessionThreadMap.get(graphId.sessionId()).add(graphId.threadId());
		// 报告正文由 reportService 单独持久化，避免 sessionHistoryMap 内存膨胀
		reportService.saveReport(graphId.threadId(), sessionHistory.getReport());
		sessionHistory.setReport("");
		sessionHistoryMap.put(graphId.threadId(), sessionHistory);
	}

	@Override
	public List<String> getGraphThreadIds(String sessionId) {
		return List.copyOf(Optional.ofNullable(sessionThreadMap.get(sessionId)).orElse(new CopyOnWriteArrayList<>()));
	}

	@Override
	public List<SessionHistory> getReports(String sessionId, List<String> threadIds) {
		return threadIds.stream()
			// 只返回属于该 sessionId 的 threadId，防止跨会话数据泄露
			.filter(threadId -> Optional.ofNullable(sessionThreadMap.get(sessionId))
				.orElse(new CopyOnWriteArrayList<>())
				.contains(threadId))
			.map(sessionHistoryMap::get)
			.peek(sessionHistory -> {
				// 按需从 reportService 回填报告正文
				String threadId = sessionHistory.getGraphId().threadId();
				sessionHistory.setReport(reportService.getReport(threadId));
			})
			.toList();
	}

	@Override
	public List<SessionHistory> getRecentReports(String sessionId, int count) {
		List<String> list = Optional.ofNullable(sessionThreadMap.get(sessionId)).orElse(new CopyOnWriteArrayList<>());
		int size = list.size();
		// 取最近 count 条，从列表末尾向前截取
		return this.getReports(sessionId,
				this.getGraphThreadIds(sessionId).stream().skip(Math.max(0, size - count)).toList());
	}

}
