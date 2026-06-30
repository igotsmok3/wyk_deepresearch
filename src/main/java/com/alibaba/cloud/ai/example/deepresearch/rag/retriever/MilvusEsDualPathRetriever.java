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

package com.alibaba.cloud.ai.example.deepresearch.rag.retriever;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.alibaba.cloud.ai.example.deepresearch.config.rag.RagProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Milvus+ES 双路检索器，供 {@code vector-store-type: milvus-es} 模式使用。
 *
 * <p>
 * 项目职责：并行执行 Milvus 向量检索（HNSW 语义召回）和 ES BM25 关键词检索（纯 BM25，不走 KNN）， 两路按
 * source_type/session_id/user_id 做相同的元数据隔离过滤，返回两路独立文档列表供调用方做应用层 RRF 融合。
 * 单路超时（{@code milvus.dual.retrieval-timeout-ms}）退化为空列表并记录 WARN，不阻断整体检索。
 *
 * <p>
 * 被使用情况：由 {@code RagVectorStoreConfiguration.DualMilvusEsVectorStoreConfiguration} 注册， 被
 * {@code DefaultHybridRagProcessor} 在双路分支注入并调用，融合后跳过二次 RRF rerank。
 *
 * @author hupei
 */
public class MilvusEsDualPathRetriever {

	private static final Logger logger = LoggerFactory.getLogger(MilvusEsDualPathRetriever.class);

	private final VectorStore milvusVectorStore;

	private final ElasticsearchClient elasticsearchClient;

	private final String indexName;

	private final RagProperties.Milvus.DualMode dual;

	public MilvusEsDualPathRetriever(VectorStore milvusVectorStore, RestClient restClient,
			RagProperties ragProperties) {
		this.milvusVectorStore = milvusVectorStore;
		this.elasticsearchClient = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper(
				new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false))));
		this.indexName = ragProperties.getElasticsearch().getIndexName();
		this.dual = ragProperties.getMilvus().getDual();
	}

	/**
	 * 双路并行检索。
	 * @param query 用户查询
	 * @param options 元数据过滤选项（source_type/session_id/user_id）
	 * @return 固定顺序的两路结果：[0]=Milvus 向量结果，[1]=ES BM25 结果
	 */
	public List<List<Document>> retrieve(Query query, Map<String, Object> options) {
		CompletableFuture<List<Document>> milvusFuture = CompletableFuture
			.supplyAsync(() -> milvusSearch(query, options));
		CompletableFuture<List<Document>> esFuture = CompletableFuture.supplyAsync(() -> esBm25Search(query, options));

		List<Document> milvusDocs = awaitOrEmpty(milvusFuture, "Milvus");
		List<Document> esDocs = awaitOrEmpty(esFuture, "ES BM25");

		logger.debug("双路检索完成：Milvus={} 条, ES BM25={} 条", milvusDocs.size(), esDocs.size());
		return List.of(milvusDocs, esDocs);
	}

	/**
	 * Milvus 向量检索路径。
	 */
	private List<Document> milvusSearch(Query query, Map<String, Object> options) {
		var searchRequestBuilder = SearchRequest.builder().query(query.text()).topK(dual.getVectorTopK());

		var filter = buildMilvusFilter(options);
		if (filter != null) {
			searchRequestBuilder.filterExpression(filter);
		}

		return milvusVectorStore.similaritySearch(searchRequestBuilder.build());
	}

	private org.springframework.ai.vectorstore.filter.Filter.Expression buildMilvusFilter(Map<String, Object> options) {
		if (options == null || !options.containsKey("source_type")) {
			return null;
		}
		var b = new FilterExpressionBuilder();
		var expr = b.eq("source_type", options.get("source_type").toString());
		if (options.containsKey("session_id")) {
			expr = b.and(expr, b.eq("session_id", options.get("session_id").toString()));
		}
		if (options.containsKey("user_id")) {
			expr = b.and(expr, b.eq("user_id", options.get("user_id").toString()));
		}
		return expr.build();
	}

	/**
	 * ES BM25 关键词检索路径（纯 BM25，不加 knn 子句）。
	 */
	private List<Document> esBm25Search(Query query, Map<String, Object> options) {
		try {
			BoolQuery.Builder boolBuilder = new BoolQuery.Builder()
				.must(m -> m.match(mm -> mm.field("content").query(query.text())));

			if (options != null) {
				// metadata 字段为 text 类型，term 精确过滤需走 .keyword 子字段
				if (options.containsKey("source_type")) {
					boolBuilder.filter(f -> f.term(
							t -> t.field("metadata.source_type.keyword").value(options.get("source_type").toString())));
				}
				if (options.containsKey("session_id")) {
					boolBuilder.filter(f -> f
						.term(t -> t.field("metadata.session_id.keyword").value(options.get("session_id").toString())));
				}
				if (options.containsKey("user_id")) {
					boolBuilder.filter(f -> f
						.term(t -> t.field("metadata.user_id.keyword").value(options.get("user_id").toString())));
				}
			}

			SearchResponse<Document> response = elasticsearchClient.search(
					s -> s.index(indexName).query(q -> q.bool(boolBuilder.build())).size(dual.getBm25TopK()),
					Document.class);

			return response.hits().hits().stream().map(hit -> {
				Document doc = hit.source();
				return doc != null ? doc : new Document("");
			}).filter(d -> d.getText() != null && !d.getText().isEmpty()).collect(Collectors.toList());
		}
		catch (Exception e) {
			throw new RuntimeException("ES BM25 检索失败", e);
		}
	}

	/**
	 * 等待单路结果，超时或异常时返回空列表并记录日志，不抛出。
	 */
	private List<Document> awaitOrEmpty(CompletableFuture<List<Document>> future, String pathName) {
		try {
			return future.get(dual.getRetrievalTimeoutMs(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			logger.warn("{} 路检索超时（{}ms），降级返回空列表", pathName, dual.getRetrievalTimeoutMs());
			future.cancel(true);
			return List.of();
		}
		catch (Exception e) {
			logger.error("{} 路检索失败，降级返回空列表", pathName, e);
			return List.of();
		}
	}

}
