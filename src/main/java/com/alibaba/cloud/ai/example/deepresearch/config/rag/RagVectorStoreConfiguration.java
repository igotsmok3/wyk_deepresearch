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

import com.alibaba.cloud.ai.example.deepresearch.rag.retriever.MilvusEsDualPathRetriever;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStoreOptions;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.URI;

/**
 * 向量存储 Bean 配置类，根据配置属性选择创建 {@code SimpleVectorStore} 或 {@code ElasticsearchVectorStore}。
 *
 * <p>
 * 项目职责：属于配置层，仅在 {@code spring.ai.alibaba.deepresearch.rag.enabled=true} 时生效。
 * 通过内部嵌套的条件配置类（{@code SimpleVectorStoreConfiguration} 和
 * {@code ElasticsearchVectorStoreConfiguration}） 按 {@code vector-store-type} 属性值创建对应的
 * {@code VectorStore} Bean（别名 {@code ragVectorStore}）， 供 RAG 检索管道使用。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理；产出的 {@code ragVectorStore} Bean 被 RAG 服务层（如
 * {@code VectorStoreDataIngestionService}、{@code RagNodeService}）注入使用。
 *
 * @author hupei
 */
@Configuration
@ConditionalOnProperty(prefix = RagProperties.RAG_PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(RagProperties.class)
public class RagVectorStoreConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(RagVectorStoreConfiguration.class);

	@Configuration
	@ConditionalOnProperty(prefix = RagProperties.RAG_PREFIX, name = "vector-store-type", havingValue = "simple",
			matchIfMissing = true)
	static class SimpleVectorStoreConfiguration {

		@Bean(name = { "simpleVectorStore", "ragVectorStore" })
		public VectorStore simpleVectorStore(EmbeddingModel embeddingModel, RagProperties ragProperties) {
			logger.info("Initializing SimpleVectorStore.");
			var simpleVectorStore = SimpleVectorStore.builder(embeddingModel).build();
			String storagePath = ragProperties.getSimple().getStoragePath();
			if (StringUtils.hasText(storagePath)) {
				File storageFile = new File(storagePath);
				if (storageFile.exists()) {
					logger.info("Loading SimpleVectorStore from file: {}", storagePath);
					simpleVectorStore.load(storageFile);
				}
			}
			return simpleVectorStore;
		}

	}

	@Configuration
	@ConditionalOnProperty(prefix = RagProperties.RAG_PREFIX, name = "vector-store-type", havingValue = "elasticsearch")
	static class ElasticsearchVectorStoreConfiguration {

		@Bean
		public RestClient elasticsearchRestClient(RagProperties ragProperties) {
			logger.info("Initializing Elasticsearch RestClient.");
			RagProperties.Elasticsearch esProps = ragProperties.getElasticsearch();
			URI uri = URI.create(esProps.getUris());

			CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
			if (StringUtils.hasText(esProps.getUsername())) {
				credentialsProvider.setCredentials(AuthScope.ANY,
						new UsernamePasswordCredentials(esProps.getUsername(), esProps.getPassword()));
			}

			return RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
				.setHttpClientConfigCallback(
						httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
				.build();
		}

		@Bean(name = { "ragElasticsearchVectorStore", "ragVectorStore" })
		public VectorStore elasticsearchVectorStore(RestClient elasticsearchRestClient, EmbeddingModel embeddingModel,
				RagProperties ragProperties) {
			RagProperties.Elasticsearch esProps = ragProperties.getElasticsearch();
			logger.info("Initializing ElasticsearchVectorStore with index: {}", esProps.getIndexName());
			ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
			options.setIndexName(esProps.getIndexName());
			options.setSimilarity(esProps.getSimilarityFunction());
			options.setDimensions(esProps.getDimensions());

			return ElasticsearchVectorStore.builder(elasticsearchRestClient, embeddingModel)
				.options(options)
				.initializeSchema(true)
				.batchingStrategy(new TokenCountBatchingStrategy())
				.build();
		}

	}

	@Configuration
	@ConditionalOnProperty(prefix = RagProperties.RAG_PREFIX, name = "vector-store-type", havingValue = "milvus")
	static class MilvusVectorStoreConfiguration {

		@Bean(name = { "milvusVectorStore", "ragVectorStore" })
		public VectorStore milvusVectorStore(EmbeddingModel embeddingModel, RagProperties ragProperties) {
			logger.info("Initializing MilvusVectorStore.");
			RagProperties.Milvus props = ragProperties.getMilvus();
			ConnectParam.Builder connectBuilder = ConnectParam.newBuilder()
				.withHost(props.getHost())
				.withPort(props.getPort());
			if (StringUtils.hasText(props.getUsername())) {
				connectBuilder.withAuthorization(props.getUsername(), props.getPassword());
			}
			MilvusServiceClient milvusClient = new MilvusServiceClient(connectBuilder.build());
			return MilvusVectorStore.builder(milvusClient, embeddingModel)
				.collectionName(props.getCollectionName())
				.databaseName(props.getDatabaseName())
				.indexType(IndexType.HNSW)
				.indexParameters("{\"M\": 16, \"efConstruction\": 256}")
				.metricType(MetricType.COSINE)
				.initializeSchema(true)
				.batchingStrategy(new TokenCountBatchingStrategy())
				.build();
		}

	}

	/**
	 * Milvus+ES 双路混合检索配置（vector-store-type: milvus-es）。
	 *
	 * <p>
	 * 注册：Milvus 向量库 + ES 写入库 + ES RestClient + 双写包装器（ragVectorStore） + 双路检索器。 检索时 Milvus
	 * 走 HNSW 向量、ES 走 BM25 关键词，应用层 RRF 融合。
	 */
	@Configuration
	@ConditionalOnProperty(prefix = RagProperties.RAG_PREFIX, name = "vector-store-type", havingValue = "milvus-es")
	static class DualMilvusEsVectorStoreConfiguration {

		@Bean
		public VectorStore milvusVectorStore(EmbeddingModel embeddingModel, RagProperties ragProperties) {
			logger.info("Initializing MilvusVectorStore for milvus-es dual mode.");
			RagProperties.Milvus props = ragProperties.getMilvus();
			ConnectParam.Builder connectBuilder = ConnectParam.newBuilder()
				.withHost(props.getHost())
				.withPort(props.getPort());
			if (StringUtils.hasText(props.getUsername())) {
				connectBuilder.withAuthorization(props.getUsername(), props.getPassword());
			}
			MilvusServiceClient milvusClient = new MilvusServiceClient(connectBuilder.build());
			return MilvusVectorStore.builder(milvusClient, embeddingModel)
				.collectionName(props.getCollectionName())
				.databaseName(props.getDatabaseName())
				.indexType(IndexType.HNSW)
				.indexParameters("{\"M\": 16, \"efConstruction\": 256}")
				.metricType(MetricType.COSINE)
				.initializeSchema(true)
				.batchingStrategy(new TokenCountBatchingStrategy())
				.build();
		}

		@Bean
		public RestClient elasticsearchRestClient(RagProperties ragProperties) {
			logger.info("Initializing Elasticsearch RestClient for milvus-es dual mode.");
			RagProperties.Elasticsearch esProps = ragProperties.getElasticsearch();
			URI uri = URI.create(esProps.getUris());

			CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
			if (StringUtils.hasText(esProps.getUsername())) {
				credentialsProvider.setCredentials(AuthScope.ANY,
						new UsernamePasswordCredentials(esProps.getUsername(), esProps.getPassword()));
			}

			return RestClient.builder(new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme()))
				.setHttpClientConfigCallback(
						httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
				.build();
		}

		@Bean
		public VectorStore dualEsVectorStore(RestClient elasticsearchRestClient, EmbeddingModel embeddingModel,
				RagProperties ragProperties) {
			RagProperties.Elasticsearch esProps = ragProperties.getElasticsearch();
			logger.info("Initializing ES write store for milvus-es dual mode with index: {}", esProps.getIndexName());
			ElasticsearchVectorStoreOptions options = new ElasticsearchVectorStoreOptions();
			options.setIndexName(esProps.getIndexName());
			options.setSimilarity(esProps.getSimilarityFunction());
			options.setDimensions(esProps.getDimensions());

			return ElasticsearchVectorStore.builder(elasticsearchRestClient, embeddingModel)
				.options(options)
				.initializeSchema(true)
				.batchingStrategy(new TokenCountBatchingStrategy())
				.build();
		}

		@Bean(name = "ragVectorStore")
		public VectorStore dualWriteVectorStore(VectorStore milvusVectorStore, VectorStore dualEsVectorStore) {
			logger.info("Initializing DualWriteVectorStore (Milvus + ES) for milvus-es dual mode.");
			return new DualWriteVectorStore(milvusVectorStore, dualEsVectorStore);
		}

		@Bean
		public MilvusEsDualPathRetriever milvusEsDualPathRetriever(VectorStore milvusVectorStore,
				RestClient elasticsearchRestClient, RagProperties ragProperties) {
			logger.info("Initializing MilvusEsDualPathRetriever for milvus-es dual mode.");
			return new MilvusEsDualPathRetriever(milvusVectorStore, elasticsearchRestClient, ragProperties);
		}

	}

}
