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
import java.util.Map;

/**
 * 文档检索策略接口，定义从特定数据源检索相关文档的统一契约。
 *
 * <p>项目职责：RAG 检索层的策略抽象，每种数据来源对应一个实现类，
 * 通过 getStrategyName() 区分策略类型，由 RagNode 统一调度执行。
 *
 * <p>被使用情况：由 UserFileRetrievalStrategy（用户上传文件）、ProfessionalKbEsStrategy（ES 知识库）、
 * ProfessionalKbApiStrategy（API 知识库）实现；RagNode 持有 List&lt;RetrievalStrategy&gt; 并逐一调用检索，
 * RagNodeService 在构建 RagNode 时注入对应实现。
 */
public interface RetrievalStrategy {

	/**
	 * 根据查询和选项从特定数据源检索相关文档。
	 * @param query 用户的查询字符串。
	 * @param options 包含额外参数的映射，例如 session_id, user_id 等，用于上下文过滤。
	 * @return 相关文档的列表。
	 */
	List<Document> retrieve(String query, Map<String, Object> options);

	/**
	 * 返回此策略的唯一名称，用于在配置中识别和选择。
	 * @return 策略名称。
	 */
	String getStrategyName();

}
