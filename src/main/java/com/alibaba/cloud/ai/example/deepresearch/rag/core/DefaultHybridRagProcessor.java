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

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import com.alibaba.cloud.ai.example.deepresearch.config.rag.RagProperties;
import com.alibaba.cloud.ai.example.deepresearch.rag.post.DocumentSelectFirstProcess;
import com.alibaba.cloud.ai.example.deepresearch.rag.retriever.MilvusEsDualPathRetriever;
import com.alibaba.cloud.ai.example.deepresearch.rag.retriever.RrfHybridElasticsearchRetriever;
import com.alibaba.cloud.ai.example.deepresearch.rag.strategy.RrfFusionStrategy;
import com.alibaba.cloud.ai.example.deepresearch.rag.transformer.HyDeTransformer;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * HybridRagProcessor 的默认实现，将查询前处理、混合检索、结果后处理三阶段串联为完整的 RAG 管道。
 *
 * <p>
 * 项目职责：RAG 核心处理器，根据 RagProperties 配置动态组装各阶段组件：
 * 查询翻译（TranslationQueryTransformer）、查询扩展（MultiQueryExpander）、 HyDE 转换（HyDeTransformer）、ES
 * 混合检索（RrfHybridElasticsearchRetriever）、 RRF
 * 重排（RrfFusionStrategy）以及首条截断（DocumentSelectFirstProcess）。
 *
 * <p>
 * 被使用情况：被 ProfessionalKbEsStrategy、ProfessionalKbApiStrategy、UserFileRetrievalStrategy
 * 注入以执行检索流程；RagNode 和 RagNodeService 也直接依赖本类作为首选检索模式。
 *
 * @author hupei
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class DefaultHybridRagProcessor implements HybridRagProcessor {

	private static final Logger logger = LoggerFactory.getLogger(DefaultHybridRagProcessor.class);

	private final VectorStore vectorStore;

	private final RrfHybridElasticsearchRetriever hybridRetriever;

	private final MilvusEsDualPathRetriever dualPathRetriever;

	private final MultiQueryExpander queryExpander;

	private final TranslationQueryTransformer queryTransformer;

	private final HyDeTransformer hyDeTransformer;

	private final DocumentSelectFirstProcess documentPostProcessor;

	private final RrfFusionStrategy rrfFusionStrategy;

	private final RagProperties ragProperties;

	public DefaultHybridRagProcessor(@Qualifier("ragVectorStore") VectorStore vectorStore,
			ObjectProvider<RestClient> restClientProvider, EmbeddingModel embeddingModel,
			ChatClient.Builder chatClientBuilder, RagProperties ragProperties, RrfFusionStrategy rrfFusionStrategy,
			ObjectProvider<MilvusEsDualPathRetriever> dualPathRetrieverProvider) {
		this.vectorStore = vectorStore;
		this.ragProperties = ragProperties;
		this.rrfFusionStrategy = rrfFusionStrategy;

		// 双路检索器（仅 milvus-es 模式下存在），存在时启用应用层 RRF 融合分支
		this.dualPathRetriever = dualPathRetrieverProvider.getIfAvailable();

		// 初始化混合检索器（仅 elasticsearch + hybrid 模式下启用）
		RestClient restClient = restClientProvider.getIfAvailable();
		if (restClient != null && ragProperties.getVectorStoreType().equalsIgnoreCase("elasticsearch")
				&& ragProperties.getElasticsearch().getHybrid().isEnabled()) {
			this.hybridRetriever = new RrfHybridElasticsearchRetriever(restClient, embeddingModel,
					ragProperties.getElasticsearch().getIndexName(), ragProperties.getElasticsearch().getHybrid());
		}
		else {
			this.hybridRetriever = null;
		}

		// 初始化查询处理器
		this.queryExpander = ragProperties.getPipeline().isQueryExpansionEnabled()
				? MultiQueryExpander.builder().chatClientBuilder(chatClientBuilder).build() : null;

		this.queryTransformer = ragProperties.getPipeline().isQueryTranslationEnabled()
				? TranslationQueryTransformer.builder()
					.chatClientBuilder(chatClientBuilder)
					.targetLanguage(ragProperties.getPipeline().getQueryTranslationLanguage())
					.build()
				: null;

		this.hyDeTransformer = ragProperties.getPipeline().isHypotheticalDocumentEmbeddingEnabled()
				? HyDeTransformer.builder().chatClientBuilder(chatClientBuilder).build() : null;

		// 初始化文档后处理器
		this.documentPostProcessor = ragProperties.getPipeline().isPostProcessingSelectFirstEnabled()
				? new DocumentSelectFirstProcess() : null;
	}

	@Override
	public List<Document> process(org.springframework.ai.rag.Query query, Map<String, Object> options) {
		logger.debug("Starting RAG processing for query: {}", query.text());

		// 阶段1：查询前处理（翻译 → 扩展 → HyDE），可能得到多条查询
		List<org.springframework.ai.rag.Query> processedQueries = preProcess(query, options);

		// 阶段2：构建 ES 过滤条件（按 source_type/session_id/user_id 隔离数据）
		Query filterExpression = buildFilterExpression(options);

		// 阶段3：混合检索（BM25 + KNN，或纯向量）
		List<Document> documents = hybridRetrieve(processedQueries, filterExpression, options);

		// 阶段4：后处理（RRF rerank 或 SelectFirst 截断）
		List<Document> finalDocuments = postProcess(documents, options);

		logger.debug("RAG processing completed. Retrieved {} documents", finalDocuments.size());
		return finalDocuments;
	}

	@Override
	public List<org.springframework.ai.rag.Query> preProcess(org.springframework.ai.rag.Query query,
			Map<String, Object> options) {
		List<org.springframework.ai.rag.Query> queries = new ArrayList<>();
		queries.add(query);

		// 步骤1：查询翻译（如中文→英文），解决知识库语言与用户语言不匹配问题
		if (queryTransformer != null) {
			queries = queries.stream().flatMap(q -> {
				org.springframework.ai.rag.Query transformed = queryTransformer.transform(q);
				return Stream.of(transformed);
			}).collect(Collectors.toList());
		}

		// 步骤2：查询扩展，将1条查询扩展为N条，提升召回率（multiQuery 策略）
		if (queryExpander != null) {
			queries = queries.stream().flatMap(q -> queryExpander.expand(q).stream()).collect(Collectors.toList());
		}

		// 步骤3：HyDE，将每条查询转换为假设性文档，提升向量空间对齐度
		if (hyDeTransformer != null) {
			queries = queries.stream().flatMap(q -> {
				org.springframework.ai.rag.Query transformed = hyDeTransformer.transform(q);
				return Stream.of(transformed);
			}).collect(Collectors.toList());
		}

		return queries;
	}

	@Override
	public List<Document> hybridRetrieve(List<org.springframework.ai.rag.Query> queries, Query filterExpression,
			Map<String, Object> options) {
		List<Document> allDocuments = new ArrayList<>();

		for (org.springframework.ai.rag.Query query : queries) {
			if (hybridRetriever != null) {
				// ES 混合模式：优先尝试 ES 内置 RRF，若许可证不支持则回退到应用层双路 RRF 融合
				try {
					List<Document> hybridResults = hybridRetriever.retrieve(query, filterExpression);
					allDocuments.addAll(hybridResults);
				}
				catch (RuntimeException e) {
					if (e.getMessage() != null && e.getMessage().contains("non-compliant")) {
						// ES 基础许可证不支持 rank.rrf，改用应用层双路融合（KNN + BM25 分别检索后 RRF 融合）
						logger.warn("ES RRF not available (basic license), falling back to app-layer dual-path fusion");
						try {
							List<List<Document>> twoPaths = hybridRetriever.searchTwoPaths(query.text(),
									filterExpression);
							allDocuments.addAll(rrfFusionStrategy.fuse(twoPaths));
						}
						catch (java.io.IOException ioEx) {
							throw new RuntimeException("ES dual-path search failed", ioEx);
						}
					}
					else {
						throw e;
					}
				}
			}
			else if (dualPathRetriever != null) {
				// Milvus+ES 双路模式：Milvus 向量 + ES BM25 两路并行，应用层 RRF 融合
				List<List<Document>> dualResults = dualPathRetriever.retrieve(query, options);
				allDocuments.addAll(rrfFusionStrategy.fuse(dualResults));
			}
			else {
				// 降级：SimpleVectorStore 纯向量相似度搜索
				List<Document> vectorResults = performVectorSearch(query, options);
				allDocuments.addAll(vectorResults);
			}
		}

		// 多查询扩展后可能有重复文档，按 ID 或内容 hash 去重
		return deduplicateDocuments(allDocuments);
	}

	@Override
	public List<Document> postProcess(List<Document> documents, Map<String, Object> options) {
		// 双路模式下 RRF 已在检索阶段完成，跳过二次 RRF rerank，避免破坏已融合排序
		if (dualPathRetriever != null) {
			return documents;
		}

		if (ragProperties.getPipeline().isRerankEnabled()) {
			// RRF rerank：按来源分组后用 RRF 公式重新排序，topK + threshold 截断
			org.springframework.ai.rag.Query query = new org.springframework.ai.rag.Query(
					options.getOrDefault("query", "").toString());
			return rrfFusionStrategy.process(query, documents);
		}

		if (documentPostProcessor != null) {
			// SelectFirst：只保留第一条最相关文档，节省 LLM context
			return documentPostProcessor.process(null, documents);
		}

		return documents;
	}

	@Override
	public Query buildFilterExpression(Map<String, Object> options) {
		if (options == null || options.isEmpty()) {
			return null;
		}

		BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();
		boolean hasConditions = false;

		// 与 VectorStoreDataIngestionService 的元数据字段保持一致，实现数据隔离：
		// source_type 区分数据来源（user_upload / professional_kb_es / professional_kb_api）
		if (options.containsKey("source_type")) {
			boolQueryBuilder
				.must(TermQuery.of(t -> t.field("metadata.source_type").value(options.get("source_type").toString()))
					._toQuery());
			hasConditions = true;
		}

		// session_id 确保用户只检索到自己本次会话上传的文件
		if (options.containsKey("session_id")) {
			boolQueryBuilder
				.must(TermQuery.of(t -> t.field("metadata.session_id").value(options.get("session_id").toString()))
					._toQuery());
			hasConditions = true;
		}

		// user_id 进一步限定用户维度（可选）
		if (options.containsKey("user_id")) {
			boolQueryBuilder.must(
					TermQuery.of(t -> t.field("metadata.user_id").value(options.get("user_id").toString()))._toQuery());
			hasConditions = true;
		}

		return hasConditions ? boolQueryBuilder.build()._toQuery() : null;
	}

	private List<Document> performVectorSearch(org.springframework.ai.rag.Query query, Map<String, Object> options) {
		var filterBuilder = new FilterExpressionBuilder();
		var searchRequestBuilder = SearchRequest.builder().query(query.text());

		// 构建向量搜索的过滤表达式
		if (options.containsKey("source_type")) {
			var filterExpression = filterBuilder.eq("source_type", options.get("source_type").toString());

			if (options.containsKey("session_id")) {
				filterExpression = filterBuilder.and(filterExpression,
						filterBuilder.eq("session_id", options.get("session_id").toString()));
			}

			if (options.containsKey("user_id")) {
				filterExpression = filterBuilder.and(filterExpression,
						filterBuilder.eq("user_id", options.get("user_id").toString()));
			}

			searchRequestBuilder.filterExpression(filterExpression.build());
		}

		SearchRequest searchRequest = searchRequestBuilder.topK(ragProperties.getPipeline().getTopK())
			.similarityThreshold(ragProperties.getPipeline().getSimilarityThreshold())
			.build();

		return vectorStore.similaritySearch(searchRequest);
	}

	private List<Document> deduplicateDocuments(List<Document> documents) {
		if (!ragProperties.getPipeline().isDeduplicationEnabled()) {
			return documents;
		}

		Map<String, Document> uniqueDocuments = new LinkedHashMap<>();

		for (Document doc : documents) {
			String key = doc.getId() != null ? doc.getId() : doc.getText();
			if (!uniqueDocuments.containsKey(key)) {
				uniqueDocuments.put(key, doc);
			}
		}

		return new ArrayList<>(uniqueDocuments.values());
	}

}
