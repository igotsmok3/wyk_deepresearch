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

package com.alibaba.cloud.ai.example.deepresearch.rag.core;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

/**
 * 统一的 RAG 处理器接口，定义查询前处理、混合检索、文档后处理及过滤表达式构建的完整契约。
 *
 * <p>项目职责：RAG 核心抽象层，将检索流程标准化为四个阶段（preProcess / hybridRetrieve /
 * postProcess / buildFilterExpression），供各检索策略统一调用。
 *
 * <p>被使用情况：由 DefaultHybridRagProcessor 实现；ProfessionalKbEsStrategy、
 * ProfessionalKbApiStrategy、UserFileRetrievalStrategy、RagNode 及 RagNodeService 均注入本接口。
 *
 * @author hupei
 */
public interface HybridRagProcessor {

	/**
	 * 执行完整的RAG处理流程
	 * @param query 原始查询
	 * @param options 选项参数，包含session_id, user_id等上下文信息
	 * @return 处理后的文档列表
	 */
	List<Document> process(Query query, Map<String, Object> options);

	/**
	 * 查询前处理：查询扩展、翻译等
	 * @param query 原始查询
	 * @param options 选项参数
	 * @return 处理后的查询列表
	 */
	List<Query> preProcess(Query query, Map<String, Object> options);

	/**
	 * 执行混合检索（支持ES混合查询和向量搜索）
	 * @param queries 处理后的查询列表
	 * @param filterExpression 过滤表达式，与VectorStoreDataIngestionService的元数据逻辑一致
	 * @param options 选项参数
	 * @return 检索到的文档列表
	 */
	List<Document> hybridRetrieve(List<Query> queries,
			co.elastic.clients.elasticsearch._types.query_dsl.Query filterExpression, Map<String, Object> options);

	/**
	 * 文档后处理：相关性排序、去重、压缩等
	 * @param documents 检索到的文档列表
	 * @param options 选项参数
	 * @return 后处理的文档列表
	 */
	List<Document> postProcess(List<Document> documents, Map<String, Object> options);

	/**
	 * 根据元数据上下文构建ES过滤表达式
	 * @param options 包含session_id, user_id, source_type等的选项参数
	 * @return ES过滤查询对象
	 */
	co.elastic.clients.elasticsearch._types.query_dsl.Query buildFilterExpression(Map<String, Object> options);

}
