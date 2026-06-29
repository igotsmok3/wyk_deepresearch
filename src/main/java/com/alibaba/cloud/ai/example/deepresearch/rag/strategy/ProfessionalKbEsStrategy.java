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

import com.alibaba.cloud.ai.example.deepresearch.rag.SourceTypeEnum;
import com.alibaba.cloud.ai.example.deepresearch.rag.core.HybridRagProcessor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Elasticsearch 的专业知识库检索策略，通过 HybridRagProcessor 执行 ES 混合检索（BM25 + KNN）。
 *
 * <p>项目职责：RetrievalStrategy 的 ES 类型实现，将 source_type 固定为 "professional_kb_es"，
 * 委托 HybridRagProcessor 完成完整的前处理→混合检索→后处理管道。
 *
 * <p>被使用情况：由 RagNodeService 注入并用于构建专业知识库 RagNode；
 * 策略名称为 "professionalKbEs"，与 UserFileRetrievalStrategy 区分数据来源。
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class ProfessionalKbEsStrategy implements RetrievalStrategy {

	private final HybridRagProcessor hybridRagProcessor;

	public ProfessionalKbEsStrategy(HybridRagProcessor hybridRagProcessor) {
		this.hybridRagProcessor = hybridRagProcessor;
	}

	@Override
	public String getStrategyName() {
		return "professionalKbEs";
	}

	@Override
	public List<Document> retrieve(String query, Map<String, Object> options) {
		// 构建专业知识库检索的上下文选项，与VectorStoreDataIngestionService的元数据逻辑一致
		Map<String, Object> ragOptions = new HashMap<>(options);
		ragOptions.put("source_type", SourceTypeEnum.PROFESSIONAL_KB_ES.getValue());
		// 专业知识库使用固定的session_id标识
		ragOptions.put("session_id", "professional_kb_es");

		// 使用统一的RAG处理器执行完整的处理流程，包含ES混合查询
		Query ragQuery = new Query(query);
		return hybridRagProcessor.process(ragQuery, ragOptions);
	}

}
