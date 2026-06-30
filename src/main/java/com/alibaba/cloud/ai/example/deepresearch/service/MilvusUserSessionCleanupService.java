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

import com.alibaba.cloud.ai.example.deepresearch.rag.SourceTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Milvus 用户会话文件过期清理服务。
 *
 * <p>
 * 项目职责：定时扫描向量库中 {@code source_type=user_upload} 的 chunk， 将 {@code expire_at}
 * 早于当前时间的过期数据批量删除，防止临时文件长期占用 Milvus 存储。 在 {@code milvusVectorStore} Bean 存在时（即
 * {@code vector-store-type=milvus} 或 {@code milvus-es}）激活。
 *
 * <p>
 * 注意：{@code milvus-es} 双路模式下，注入的 {@code ragVectorStore} 为 {@code DualWriteVectorStore}， 其
 * {@code delete(Filter.Expression)} 会同时删除 Milvus 与 ES 两端，故本服务在双路模式下自动覆盖 ES 端清理。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理，通过 {@code @Scheduled} 驱动； {@code expire_at} 字段由
 * {@code VectorStoreDataIngestionService} 在写入用户文件时写入元数据。
 *
 * @author hupei
 */
@Service
@ConditionalOnBean(name = "milvusVectorStore")
public class MilvusUserSessionCleanupService {

	private static final Logger logger = LoggerFactory.getLogger(MilvusUserSessionCleanupService.class);

	private final VectorStore vectorStore;

	public MilvusUserSessionCleanupService(@Qualifier("ragVectorStore") VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

	/**
	 * 定时清理过期的用户会话文件 chunk。 cron 表达式可通过
	 * {@code spring.ai.alibaba.deepresearch.rag.milvus.cleanup-cron} 覆盖，默认每小时执行。
	 */
	@Scheduled(cron = "${spring.ai.alibaba.deepresearch.rag.milvus.cleanup-cron:0 0 * * * *}")
	public void cleanupExpiredUserSessionFiles() {
		long now = Instant.now().getEpochSecond();
		logger.info("Starting cleanup of expired user session files, expire_at < {}", now);

		var b = new FilterExpressionBuilder();
		var expiredFilter = b.and(b.eq("source_type", SourceTypeEnum.USER_UPLOAD.getValue()), b.lt("expire_at", now));

		try {
			vectorStore.delete(expiredFilter.build());
			logger.info("Cleanup completed for expired user session files.");
		}
		catch (Exception e) {
			logger.error("Failed to cleanup expired user session files", e);
		}
	}

}
