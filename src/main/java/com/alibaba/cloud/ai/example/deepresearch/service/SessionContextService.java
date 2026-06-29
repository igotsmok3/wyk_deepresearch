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

import java.util.List;

/**
 * 会话上下文服务接口，定义会话与线程映射关系及历史报告管理的操作契约。
 *
 * <p>项目职责：抽象多轮对话的上下文存储行为，包括注册新历史记录、
 * 查询会话下所有 threadId、按 threadId 列表获取历史报告以及获取最近 N 条报告；
 * 与存储介质解耦，实现可按需替换。
 *
 * <p>被使用情况：由 {@link InMemorySessionContextService} 实现（默认）；
 * 被 {@code ReporterNode} 在生成报告后写入历史，被 {@code CoordinatorNode} 和
 * {@code BackgroundInvestigationNode} 读取历史上下文；
 * 也通过 {@code DeepResearchConfiguration} 注入节点。
 *
 * @author vlsmb
 * @since 2025/8/6
 */
public interface SessionContextService {

	void addSessionHistory(GraphId graphId, SessionHistory sessionHistory);

	List<String> getGraphThreadIds(String sessionId);

	List<SessionHistory> getReports(String sessionId, List<String> threadIds);

	List<SessionHistory> getRecentReports(String sessionId, int count);

	default List<SessionHistory> getRecentReports(String sessionId) {
		return this.getRecentReports(sessionId, 5);
	}

}
