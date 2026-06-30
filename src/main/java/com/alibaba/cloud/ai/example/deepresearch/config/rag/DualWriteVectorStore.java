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

package com.alibaba.cloud.ai.example.deepresearch.config.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 双写向量存储包装器，用于 {@code vector-store-type: milvus-es} 模式，作为 {@code ragVectorStore} Bean。
 *
 * <p>
 * 项目职责：屏蔽 Milvus + ES 双写复杂性，对 {@code VectorStoreDataIngestionService} 透明。
 * 写入时并发写两端（best-effort，任一失败仅记录日志不抛出），删除时串行删两端， 读取委托给 Milvus（{@code milvus-es}
 * 模式下检索不走此路径，仅作接口合规实现）。
 *
 * <p>
 * 被使用情况：由 {@code RagVectorStoreConfiguration.DualMilvusEsVectorStoreConfiguration}
 * 注册，注入文档摄入服务与 TTL 清理服务，使其无感知地同时写/删 Milvus 和 ES 两端。
 *
 * @author hupei
 */
public class DualWriteVectorStore implements VectorStore {

	private static final Logger logger = LoggerFactory.getLogger(DualWriteVectorStore.class);

	private final VectorStore milvusStore;

	private final VectorStore esStore;

	public DualWriteVectorStore(VectorStore milvusStore, VectorStore esStore) {
		this.milvusStore = milvusStore;
		this.esStore = esStore;
	}

	@Override
	public void add(List<Document> documents) {
		// 并发写两端，任一失败记录 ERROR 但不抛出，避免单点失败阻塞摄入
		CompletableFuture<Void> milvusFuture = CompletableFuture.runAsync(() -> milvusStore.add(documents))
			.exceptionally(ex -> {
				logger.error("DualWrite: 写入 Milvus 失败，{} 个文档丢失", documents.size(), ex);
				return null;
			});
		CompletableFuture<Void> esFuture = CompletableFuture.runAsync(() -> esStore.add(documents))
			.exceptionally(ex -> {
				logger.error("DualWrite: 写入 ES 失败，{} 个文档丢失", documents.size(), ex);
				return null;
			});

		CompletableFuture.allOf(milvusFuture, esFuture).join();
		logger.info("DualWrite: 完成双写，文档数={}", documents.size());
	}

	@Override
	public void delete(List<String> idList) {
		deleteSafely("Milvus", () -> milvusStore.delete(idList));
		deleteSafely("ES", () -> esStore.delete(idList));
	}

	@Override
	public void delete(Filter.Expression filterExpression) {
		// 串行删除，先 Milvus 后 ES，任一失败记录并继续
		deleteSafely("Milvus", () -> milvusStore.delete(filterExpression));
		deleteSafely("ES", () -> esStore.delete(filterExpression));
	}

	private void deleteSafely(String target, Runnable deletion) {
		try {
			deletion.run();
		}
		catch (Exception e) {
			logger.error("DualWrite: 从 {} 删除失败", target, e);
		}
	}

	@Override
	public List<Document> similaritySearch(SearchRequest request) {
		// 接口合规实现：milvus-es 模式下检索由 MilvusEsDualPathRetriever 负责，不经此方法
		return milvusStore.similaritySearch(request);
	}

}
