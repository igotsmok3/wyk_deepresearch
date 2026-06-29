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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ReportService} 的纯内存实现，在 Redis 不可用时作为报告存储的默认方案。
 *
 * <p>项目职责：使用 {@code ConcurrentHashMap} 以 threadId 为键存储报告内容，
 * 提供保存、读取、存在性检查和删除四类操作，保证多线程并发安全。
 * 报告内容仅驻留内存，应用重启后丢失。
 *
 * <p>被使用情况：实现 {@link ReportService} 接口，在 {@code spring.data.redis.enabled=false}
 * （默认值）时由 Spring 自动装配；被 {@code ReporterNode} 写入报告、被
 * {@code ExportService}、{@code InMemorySessionContextService} 读取报告、
 * 被 {@code ReportController} 查询和删除报告。
 *
 * @author huangzhen
 * @since 2025/6/20
 */
@Service
@ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "false", matchIfMissing = true)
public class ReportMemoryService implements ReportService {

	private static final Logger logger = LoggerFactory.getLogger(ReportMemoryService.class);

	/**
	 * 内存存储，使用ConcurrentHashMap保证线程安全
	 */
	private final Map<String, String> reportStorage = new ConcurrentHashMap<>();

	/**
	 * 存储报告到内存
	 * @param threadId 线程ID
	 * @param report 报告内容
	 */
	@Override
	public void saveReport(String threadId, String report) {
		try {
			reportStorage.put(threadId, report);
			logger.info("Report saved to memory, thread ID: {}", threadId);
		}
		catch (Exception e) {
			logger.error("Failed to save report to memory, thread ID: {}", threadId, e);
			throw new RuntimeException("Failed to save report", e);
		}
	}

	/**
	 * 从内存获取报告
	 * @param threadId 线程ID
	 * @return 报告内容，如果不存在返回 null
	 */
	@Override
	public String getReport(String threadId) {
		try {
			String report = reportStorage.get(threadId);
			if (report != null) {
				logger.info("Successfully retrieved report from memory, thread ID: {}", threadId);
				return report;
			}
			else {
				logger.warn("Report not found in memory, thread ID: {}", threadId);
				return null;
			}
		}
		catch (Exception e) {
			logger.error("Failed to get report from memory, thread ID: {}", threadId, e);
			throw new RuntimeException("Failed to get report", e);
		}
	}

	/**
	 * 检查报告是否存在
	 * @param threadId 线程ID
	 * @return 是否存在
	 */
	@Override
	public boolean existsReport(String threadId) {
		try {
			boolean exists = reportStorage.containsKey(threadId);
			logger.debug("Checking if report exists, thread ID: {}, exists: {}", threadId, exists);
			return exists;
		}
		catch (Exception e) {
			logger.error("Failed to check if report exists, thread ID: {}", threadId, e);
			return false;
		}
	}

	/**
	 * 删除报告
	 * @param threadId 线程ID
	 */
	@Override
	public void deleteReport(String threadId) {
		try {
			reportStorage.remove(threadId);
			logger.info("Report deleted from memory, thread ID: {}", threadId);
		}
		catch (Exception e) {
			logger.error("Failed to delete report, thread ID: {}", threadId, e);
			throw new RuntimeException("Failed to delete report", e);
		}
	}

}
