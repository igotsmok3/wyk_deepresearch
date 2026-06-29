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

package com.alibaba.cloud.ai.example.deepresearch.rag.strategy;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 多路检索结果融合策略接口，将来自不同检索来源的文档列表合并为单一排序结果。
 *
 * <p>项目职责：RAG 后处理层的融合抽象，支持不同的融合算法（如 RRF），
 * 由 RagNode 在多策略检索完成后调用 fuse() 将各路结果合并。
 *
 * <p>被使用情况：由 RrfFusionStrategy 实现；RagNode 和 RagNodeService 持有本接口，
 * 在策略模式（多 RetrievalStrategy）检索完成后执行融合排序。
 */
public interface FusionStrategy {

	/**
	 * 将多个已排序的文档列表融合成一个单一的、重新排序的列表。
	 * @param results 包含多个检索结果列表的列表。
	 * @return 经过融合和重新排序的单一文档列表。
	 */
	List<Document> fuse(List<List<Document>> results);

	/**
	 * 返回此策略的唯一名称。
	 * @return 策略名称。
	 */
	String getStrategyName();

}
