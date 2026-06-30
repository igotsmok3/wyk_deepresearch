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

import com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchProperties;
import org.springframework.ai.vectorstore.elasticsearch.SimilarityFunction;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG（检索增强生成）功能的配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.rag.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，统一管理 RAG 相关的所有配置，包括功能开关、向量存储类型（简单内存/Elasticsearch）、
 * 检索管道参数（TopK、相似度阈值、重排序）、文本分割参数、专业知识库列表及数据加载路径等。
 * 内部嵌套多个静态配置类（{@code Simple}、{@code Pipeline}、{@code Elasticsearch}、{@code Data}、
 * {@code ProfessionalKnowledgeBases}、{@code TextSplitter}）对各功能域进行分组管理。
 *
 * <p>
 * 被使用情况：被 {@code DeepResearchConfiguration}、{@code RagVectorStoreConfiguration}、
 * {@code RagDataAutoConfiguration} 注入使用，是 RAG 子系统的核心配置数据来源。
 *
 * @author hupei
 */
@ConfigurationProperties(prefix = RagProperties.RAG_PREFIX)
public class RagProperties {

	public static final String RAG_PREFIX = DeepResearchProperties.PREFIX + ".rag";

	/**
	 * 是否启用RAG功能，默认为false。
	 */
	private boolean enabled = false;

	/**
	 * 向量存储类型，默认为"simple"，可选值还包括"elasticsearch"。
	 */
	private String vectorStoreType = "simple";

	/**
	 * 请求超时时间，单位为秒，默认为60秒。
	 */
	private Integer timeoutSeconds = 60;

	/**
	 * 重试次数，默认为2次。
	 */
	private Integer retryTimes = 2;

	/**
	 * 简单向量存储配置。
	 */
	private final Simple simple = new Simple();

	/**
	 * RAG增强配置。
	 */
	private final Pipeline pipeline = new Pipeline();

	/**
	 * 数据加载相关的配置
	 */
	private final Data data = new Data();

	/**
	 * Elasticsearch配置属性
	 */
	private final Elasticsearch elasticsearch = new Elasticsearch();

	/**
	 * 专业知识库配置
	 */
	private final ProfessionalKnowledgeBases professionalKnowledgeBases = new ProfessionalKnowledgeBases();

	/**
	 * 文本分割配置
	 */
	private final TextSplitter textSplitter = new TextSplitter();

	/**
	 * Milvus向量存储配置
	 */
	private final Milvus milvus = new Milvus();

	/**
	 * Markdown结构感知切分配置
	 */
	private final MarkdownSplitter markdownSplitter = new MarkdownSplitter();

	/**
	 * PDF结构感知切分配置
	 */
	private final PdfSplitter pdfSplitter = new PdfSplitter();

	/**
	 * MinerU PDF精准解析配置。
	 */
	private final MinerU minerU = new MinerU();

	/**
	 * 用户会话文件的TTL天数，默认7天。
	 */
	private int userFileTtlDays = 7;

	// Getters
	public Data getData() {
		return data;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getVectorStoreType() {
		return vectorStoreType;
	}

	public void setVectorStoreType(String vectorStoreType) {
		this.vectorStoreType = vectorStoreType;
	}

	public Simple getSimple() {
		return simple;
	}

	public Pipeline getPipeline() {
		return pipeline;
	}

	public Elasticsearch getElasticsearch() {
		return elasticsearch;
	}

	public ProfessionalKnowledgeBases getProfessionalKnowledgeBases() {
		return professionalKnowledgeBases;
	}

	public void setTimeoutSeconds(Integer timeoutSeconds) {
		this.timeoutSeconds = timeoutSeconds;
	}

	public Integer getTimeoutSeconds() {
		return timeoutSeconds;
	}

	public void setRetryTimes(Integer retryTimes) {
		this.retryTimes = retryTimes;
	}

	public Integer getRetryTimes() {
		return retryTimes;
	}

	public TextSplitter getTextSplitter() {
		return textSplitter;
	}

	public Milvus getMilvus() {
		return milvus;
	}

	public MarkdownSplitter getMarkdownSplitter() {
		return markdownSplitter;
	}

	public PdfSplitter getPdfSplitter() {
		return pdfSplitter;
	}

	public MinerU getMinerU() {
		return minerU;
	}

	public int getUserFileTtlDays() {
		return userFileTtlDays;
	}

	public void setUserFileTtlDays(int userFileTtlDays) {
		this.userFileTtlDays = userFileTtlDays;
	}

	/**
	 * 简单向量存储配置。
	 */
	public static class Simple {

		/**
		 * 简单向量存储的存储路径，默认为"vector_store.json"。
		 */
		private String storagePath = "vector_store.json";

		public String getStoragePath() {
			return storagePath;
		}

		public void setStoragePath(String storagePath) {
			this.storagePath = storagePath;
		}

	}

	/**
	 * RAG增强配置。
	 */
	public static class Pipeline {

		/**
		 * 是否启用查询扩展功能，默认为false。
		 */
		private boolean queryExpansionEnabled = false;

		/**
		 * 是否启用查询翻译功能，默认为false。
		 */
		private boolean queryTranslationEnabled = false;

		/**
		 * 是否启用假设性文档嵌入功能，默认为false。
		 */
		private boolean hypotheticalDocumentEmbeddingEnabled = false;

		/**
		 * 查询翻译的目标语言，默认为"English"。
		 */
		private String queryTranslationLanguage = "English";

		/**
		 * 是否启用后处理选择第一个结果功能，默认为false。
		 */
		private boolean postProcessingSelectFirstEnabled = false;

		/**
		 * 搜索配置
		 */
		private int topK = 5;

		private double similarityThreshold = 0.7;

		private boolean deduplicationEnabled = true;

		/**
		 * 后处理配置
		 */
		private boolean rerankEnabled = true;

		private int rerankTopK = 10;

		private double rerankThreshold = 0.5;

		// Getters and Setters for Pipeline properties...
		public boolean isQueryExpansionEnabled() {
			return queryExpansionEnabled;
		}

		public void setQueryExpansionEnabled(boolean queryExpansionEnabled) {
			this.queryExpansionEnabled = queryExpansionEnabled;
		}

		public boolean isQueryTranslationEnabled() {
			return queryTranslationEnabled;
		}

		public void setQueryTranslationEnabled(boolean queryTranslationEnabled) {
			this.queryTranslationEnabled = queryTranslationEnabled;
		}

		public boolean isHypotheticalDocumentEmbeddingEnabled() {
			return hypotheticalDocumentEmbeddingEnabled;
		}

		public void setHypotheticalDocumentEmbeddingEnabled(boolean hypotheticalDocumentEmbeddingEnabled) {
			this.hypotheticalDocumentEmbeddingEnabled = hypotheticalDocumentEmbeddingEnabled;
		}

		public String getQueryTranslationLanguage() {
			return queryTranslationLanguage;
		}

		public void setQueryTranslationLanguage(String queryTranslationLanguage) {
			this.queryTranslationLanguage = queryTranslationLanguage;
		}

		public boolean isPostProcessingSelectFirstEnabled() {
			return postProcessingSelectFirstEnabled;
		}

		public void setPostProcessingSelectFirstEnabled(boolean postProcessingSelectFirstEnabled) {
			this.postProcessingSelectFirstEnabled = postProcessingSelectFirstEnabled;
		}

		public int getTopK() {
			return topK;
		}

		public void setTopK(int topK) {
			this.topK = topK;
		}

		public double getSimilarityThreshold() {
			return similarityThreshold;
		}

		public void setSimilarityThreshold(double similarityThreshold) {
			this.similarityThreshold = similarityThreshold;
		}

		public boolean isDeduplicationEnabled() {
			return deduplicationEnabled;
		}

		public void setDeduplicationEnabled(boolean deduplicationEnabled) {
			this.deduplicationEnabled = deduplicationEnabled;
		}

		public boolean isRerankEnabled() {
			return rerankEnabled;
		}

		public void setRerankEnabled(boolean rerankEnabled) {
			this.rerankEnabled = rerankEnabled;
		}

		public int getRerankTopK() {
			return rerankTopK;
		}

		public void setRerankTopK(int rerankTopK) {
			this.rerankTopK = rerankTopK;
		}

		public double getRerankThreshold() {
			return rerankThreshold;
		}

		public void setRerankThreshold(double rerankThreshold) {
			this.rerankThreshold = rerankThreshold;
		}

	}

	/**
	 * Elasticsearch配置
	 */
	public static class Elasticsearch {

		/**
		 * Elasticsearch索引名称，默认为"spring-ai-rag-es-index"。
		 */
		private String indexName = "spring-ai-rag-es-index";

		/**
		 * 向量维度，默认为1536。
		 */
		private int dimensions = 1536;

		/**
		 * Elasticsearch连接URI，例如"http://localhost:9200"。
		 */
		private String uris;

		/**
		 * Elasticsearch用户名。
		 */
		private String username;

		/**
		 * Elasticsearch密码。
		 */
		private String password;

		/**
		 * 相似度函数配置。
		 */
		private SimilarityFunction similarityFunction;

		/**
		 * 混合搜索配置
		 */
		private final Hybrid hybrid = new Hybrid();

		// Getters and Setters
		public String getIndexName() {
			return indexName;
		}

		public void setIndexName(String indexName) {
			this.indexName = indexName;
		}

		public int getDimensions() {
			return dimensions;
		}

		public void setDimensions(int dimensions) {
			this.dimensions = dimensions;
		}

		public String getUris() {
			return uris;
		}

		public void setUris(String uris) {
			this.uris = uris;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public SimilarityFunction getSimilarityFunction() {
			return similarityFunction;
		}

		public void setSimilarityFunction(SimilarityFunction similarityFunction) {
			this.similarityFunction = similarityFunction;
		}

		public Hybrid getHybrid() {
			return hybrid;
		}

		/**
		 * 混合搜索配置。 混合搜索结合了BM25和KNN搜索，使用RRF算法进行结果融合。
		 */
		public static class Hybrid {

			/**
			 * 是否启用混合搜索，默认为false。
			 */
			private boolean enabled = false;

			/**
			 * BM25搜索的权重。
			 */
			private float bm25Boost = 1.0f;

			/**
			 * KNN搜索的权重。
			 */
			private float knnBoost = 1.0f;

			/**
			 * RRF算法中的窗口大小。
			 */
			private int rrfWindowSize = 10;

			/**
			 * RRF算法中的排名常数。
			 */
			private int rrfRankConstant = 60;

			public boolean isEnabled() {
				return enabled;
			}

			public void setEnabled(boolean enabled) {
				this.enabled = enabled;
			}

			public float getBm25Boost() {
				return bm25Boost;
			}

			public void setBm25Boost(float bm25Boost) {
				this.bm25Boost = bm25Boost;
			}

			public float getKnnBoost() {
				return knnBoost;
			}

			public void setKnnBoost(float knnBoost) {
				this.knnBoost = knnBoost;
			}

			public int getRrfWindowSize() {
				return rrfWindowSize;
			}

			public void setRrfWindowSize(int rrfWindowSize) {
				this.rrfWindowSize = rrfWindowSize;
			}

			public int getRrfRankConstant() {
				return rrfRankConstant;
			}

			public void setRrfRankConstant(int rrfRankConstant) {
				this.rrfRankConstant = rrfRankConstant;
			}

		}

	}

	/**
	 * 数据加载相关的配置。
	 */
	public static class Data {

		/**
		 * 应用启动时加载的数据源位置. 支持ant-style patterns, e.g., "classpath:/data/*.md",
		 * "file:/path/to/docs/"
		 */
		private List<String> locations = new ArrayList<>();

		/**
		 * 定时扫描文件夹的配置
		 */
		private final Scan scan = new Scan();

		public List<String> getLocations() {
			return locations;
		}

		public void setLocations(List<String> locations) {
			this.locations = locations;
		}

		public Scan getScan() {
			return scan;
		}

		public static class Scan {

			/**
			 * 是否启用定时扫描，默认为false。
			 */
			private boolean enabled = false;

			/**
			 * 要扫描的目录路径。
			 */
			private String directory;

			/**
			 * 定时任务的cron表达式，默认每小时执行一次。
			 */
			private String cron = "0 0 * * * *";

			/**
			 * 处理完成后的文件归档目录。
			 */
			private String archiveDirectory;

			// Getters and Setters
			public boolean isEnabled() {
				return enabled;
			}

			public void setEnabled(boolean enabled) {
				this.enabled = enabled;
			}

			public String getDirectory() {
				return directory;
			}

			public void setDirectory(String directory) {
				this.directory = directory;
			}

			public String getCron() {
				return cron;
			}

			public void setCron(String cron) {
				this.cron = cron;
			}

			public String getArchiveDirectory() {
				return archiveDirectory;
			}

			public void setArchiveDirectory(String archiveDirectory) {
				this.archiveDirectory = archiveDirectory;
			}

		}

	}

	/**
	 * 专业知识库配置
	 */
	public static class ProfessionalKnowledgeBases {

		/**
		 * 是否启用专业知识库决策，默认为true
		 */
		private boolean decisionEnabled = true;

		/**
		 * 专业知识库列表
		 */
		private List<KnowledgeBase> knowledgeBases = new ArrayList<>();

		public boolean isDecisionEnabled() {
			return decisionEnabled;
		}

		public void setDecisionEnabled(boolean decisionEnabled) {
			this.decisionEnabled = decisionEnabled;
		}

		public List<KnowledgeBase> getKnowledgeBases() {
			return knowledgeBases;
		}

		public void setKnowledgeBases(List<KnowledgeBase> knowledgeBases) {
			this.knowledgeBases = knowledgeBases;
		}

		/**
		 * 单个专业知识库配置
		 */
		public static class KnowledgeBase {

			/**
			 * 知识库ID
			 */
			private String id;

			/**
			 * 知识库名称
			 */
			private String name;

			/**
			 * 知识库描述，用于大模型判断是否需要查询
			 */
			private String description;

			/**
			 * 知识库类型：api, elasticsearch
			 */
			private String type = "api";

			/**
			 * API配置
			 */
			private final Api api = new Api();

			/**
			 * 是否启用
			 */
			private boolean enabled = true;

			/**
			 * 优先级，数字越小优先级越高
			 */
			private int priority = 100;

			public String getId() {
				return id;
			}

			public void setId(String id) {
				this.id = id;
			}

			public String getName() {
				return name;
			}

			public void setName(String name) {
				this.name = name;
			}

			public String getDescription() {
				return description;
			}

			public void setDescription(String description) {
				this.description = description;
			}

			public String getType() {
				return type;
			}

			public void setType(String type) {
				this.type = type;
			}

			public Api getApi() {
				return api;
			}

			public boolean isEnabled() {
				return enabled;
			}

			public void setEnabled(boolean enabled) {
				this.enabled = enabled;
			}

			public int getPriority() {
				return priority;
			}

			public void setPriority(int priority) {
				this.priority = priority;
			}

			/**
			 * API配置
			 */
			public static class Api {

				/**
				 * API类型：dashscope, custom
				 */
				private String provider = "dashscope";

				/**
				 * API URL
				 */
				private String url;

				/**
				 * API Key
				 */
				private String apiKey;

				/**
				 * 模型名称（适用于dashscope等）
				 */
				private String model;

				/**
				 * 请求超时时间（毫秒）
				 */
				private int timeoutMs = 30000;

				/**
				 * 最大返回结果数
				 */
				private int maxResults = 5;

				public String getProvider() {
					return provider;
				}

				public void setProvider(String provider) {
					this.provider = provider;
				}

				public String getUrl() {
					return url;
				}

				public void setUrl(String url) {
					this.url = url;
				}

				public String getApiKey() {
					return apiKey;
				}

				public void setApiKey(String apiKey) {
					this.apiKey = apiKey;
				}

				public String getModel() {
					return model;
				}

				public void setModel(String model) {
					this.model = model;
				}

				public int getTimeoutMs() {
					return timeoutMs;
				}

				public void setTimeoutMs(int timeoutMs) {
					this.timeoutMs = timeoutMs;
				}

				public int getMaxResults() {
					return maxResults;
				}

				public void setMaxResults(int maxResults) {
					this.maxResults = maxResults;
				}

			}

		}

	}

	/**
	 * 文本分割配置
	 */
	public static class TextSplitter {

		/**
		 * 默认分块大小（token数量），默认800
		 */
		private int defaultChunkSize = 800;

		/**
		 * 分块重叠大小（token数量），默认100
		 */
		private int overlap = 100;

		/**
		 * 最小分块大小（token数量），默认5
		 */
		private int minChunkSizeToSplit = 5;

		/**
		 * 最大分块大小（token数量），默认10000
		 */
		private int maxChunkSize = 10000;

		/**
		 * 是否保持分隔符，默认true
		 */
		private boolean keepSeparator = true;

		/**
		 * 是否启用调试模式，默认false
		 */
		private boolean debugMode = false;

		public int getDefaultChunkSize() {
			return defaultChunkSize;
		}

		public void setDefaultChunkSize(int defaultChunkSize) {
			this.defaultChunkSize = defaultChunkSize;
		}

		public int getOverlap() {
			return overlap;
		}

		public void setOverlap(int overlap) {
			this.overlap = overlap;
		}

		public int getMinChunkSizeToSplit() {
			return minChunkSizeToSplit;
		}

		public void setMinChunkSizeToSplit(int minChunkSizeToSplit) {
			this.minChunkSizeToSplit = minChunkSizeToSplit;
		}

		public int getMaxChunkSize() {
			return maxChunkSize;
		}

		public void setMaxChunkSize(int maxChunkSize) {
			this.maxChunkSize = maxChunkSize;
		}

		public boolean isKeepSeparator() {
			return keepSeparator;
		}

		public void setKeepSeparator(boolean keepSeparator) {
			this.keepSeparator = keepSeparator;
		}

		public boolean isDebugMode() {
			return debugMode;
		}

		public void setDebugMode(boolean debugMode) {
			this.debugMode = debugMode;
		}

	}

	/**
	 * Milvus向量存储配置。
	 */
	public static class Milvus {

		/**
		 * Milvus服务主机地址，默认为"localhost"。
		 */
		private String host = "localhost";

		/**
		 * Milvus服务端口，默认为19530。
		 */
		private int port = 19530;

		/**
		 * Milvus数据库名称，默认为"default"。
		 */
		private String databaseName = "default";

		/**
		 * Milvus集合名称，默认为"deepresearch_vectors"。
		 */
		private String collectionName = "deepresearch_vectors";

		/**
		 * 向量维度，需与Embedding模型输出维度一致，默认1536。
		 */
		private int embeddingDimension = 1536;

		/**
		 * Milvus用户名（可选）。
		 */
		private String username;

		/**
		 * Milvus密码（可选）。
		 */
		private String password;

		/**
		 * Milvus+ES 双路混合检索模式（vector-store-type: milvus-es）专用配置。
		 */
		private final DualMode dual = new DualMode();

		public DualMode getDual() {
			return dual;
		}

		public String getHost() {
			return host;
		}

		public void setHost(String host) {
			this.host = host;
		}

		public int getPort() {
			return port;
		}

		public void setPort(int port) {
			this.port = port;
		}

		public String getDatabaseName() {
			return databaseName;
		}

		public void setDatabaseName(String databaseName) {
			this.databaseName = databaseName;
		}

		public String getCollectionName() {
			return collectionName;
		}

		public void setCollectionName(String collectionName) {
			this.collectionName = collectionName;
		}

		public int getEmbeddingDimension() {
			return embeddingDimension;
		}

		public void setEmbeddingDimension(int embeddingDimension) {
			this.embeddingDimension = embeddingDimension;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		/**
		 * Milvus+ES 双路混合检索模式配置。 仅在 {@code vector-store-type: milvus-es} 时生效，
		 * 控制两路单独的返回数量、超时及 RRF 融合权重。
		 */
		public static class DualMode {

			/**
			 * ES BM25 单路返回文档数，默认10。
			 */
			private int bm25TopK = 10;

			/**
			 * Milvus 向量单路返回文档数，默认10。
			 */
			private int vectorTopK = 10;

			/**
			 * 单路检索超时（毫秒），超时返回空列表并继续融合，默认3000。
			 */
			private int retrievalTimeoutMs = 3000;

			/**
			 * BM25 结果列表在 RRF 中的权重（扩展用），默认1.0。
			 */
			private float bm25RrfWeight = 1.0f;

			/**
			 * 向量结果列表在 RRF 中的权重（扩展用），默认1.0。
			 */
			private float vectorRrfWeight = 1.0f;

			public int getBm25TopK() {
				return bm25TopK;
			}

			public void setBm25TopK(int bm25TopK) {
				this.bm25TopK = bm25TopK;
			}

			public int getVectorTopK() {
				return vectorTopK;
			}

			public void setVectorTopK(int vectorTopK) {
				this.vectorTopK = vectorTopK;
			}

			public int getRetrievalTimeoutMs() {
				return retrievalTimeoutMs;
			}

			public void setRetrievalTimeoutMs(int retrievalTimeoutMs) {
				this.retrievalTimeoutMs = retrievalTimeoutMs;
			}

			public float getBm25RrfWeight() {
				return bm25RrfWeight;
			}

			public void setBm25RrfWeight(float bm25RrfWeight) {
				this.bm25RrfWeight = bm25RrfWeight;
			}

			public float getVectorRrfWeight() {
				return vectorRrfWeight;
			}

			public void setVectorRrfWeight(float vectorRrfWeight) {
				this.vectorRrfWeight = vectorRrfWeight;
			}

		}

	}

	/**
	 * Markdown结构感知切分配置。
	 */
	public static class MarkdownSplitter {

		/**
		 * 是否启用Markdown结构感知切分，默认true。
		 */
		private boolean enabled = true;

		/**
		 * 按标题层级切分的层级深度，默认2（即h1、h2处切断，h3+不切断）。
		 */
		private int splitLevel = 2;

		/**
		 * 每个chunk的最大token数，-1表示继承TextSplitter.maxChunkSize。
		 */
		private int maxChunkSize = -1;

		/**
		 * 是否保持代码块整体不拆断，默认true。
		 */
		private boolean keepCodeBlockIntact = true;

		/**
		 * 是否保持表格整体不拆断，默认true。
		 */
		private boolean keepTableIntact = true;

		/**
		 * 是否在每个chunk的元数据中附加heading路径，默认true。
		 */
		private boolean appendHeadingPath = true;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getSplitLevel() {
			return splitLevel;
		}

		public void setSplitLevel(int splitLevel) {
			this.splitLevel = splitLevel;
		}

		public int getMaxChunkSize() {
			return maxChunkSize;
		}

		public void setMaxChunkSize(int maxChunkSize) {
			this.maxChunkSize = maxChunkSize;
		}

		public boolean isKeepCodeBlockIntact() {
			return keepCodeBlockIntact;
		}

		public void setKeepCodeBlockIntact(boolean keepCodeBlockIntact) {
			this.keepCodeBlockIntact = keepCodeBlockIntact;
		}

		public boolean isKeepTableIntact() {
			return keepTableIntact;
		}

		public void setKeepTableIntact(boolean keepTableIntact) {
			this.keepTableIntact = keepTableIntact;
		}

		public boolean isAppendHeadingPath() {
			return appendHeadingPath;
		}

		public void setAppendHeadingPath(boolean appendHeadingPath) {
			this.appendHeadingPath = appendHeadingPath;
		}

	}

	/**
	 * PDF结构感知切分配置。
	 */
	public static class PdfSplitter {

		/**
		 * 是否启用PDF结构感知切分，默认true。
		 */
		private boolean enabled = true;

		/**
		 * 每个chunk的最大token数，-1表示继承TextSplitter.maxChunkSize。
		 */
		private int maxChunkSize = -1;

		/**
		 * 启发式识别标题的字体大小比例阈值（相对于正文字体中位数），默认1.2。
		 */
		private float headingFontSizeRatio = 1.2f;

		/**
		 * 是否提取表格，默认true（当前版本暂不支持，预留配置）。
		 */
		private boolean extractTables = true;

		/**
		 * 表格输出格式，支持markdown或csv，默认markdown。
		 */
		private String tableOutputFormat = "markdown";

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public int getMaxChunkSize() {
			return maxChunkSize;
		}

		public void setMaxChunkSize(int maxChunkSize) {
			this.maxChunkSize = maxChunkSize;
		}

		public float getHeadingFontSizeRatio() {
			return headingFontSizeRatio;
		}

		public void setHeadingFontSizeRatio(float headingFontSizeRatio) {
			this.headingFontSizeRatio = headingFontSizeRatio;
		}

		public boolean isExtractTables() {
			return extractTables;
		}

		public void setExtractTables(boolean extractTables) {
			this.extractTables = extractTables;
		}

		public String getTableOutputFormat() {
			return tableOutputFormat;
		}

		public void setTableOutputFormat(String tableOutputFormat) {
			this.tableOutputFormat = tableOutputFormat;
		}

	}

	/**
	 * MinerU PDF精准解析配置。
	 */
	public static class MinerU {

		private boolean enabled = false;

		private String apiBaseUrl = "https://mineru.net";

		private String apiToken;

		private String modelVersion = "pipeline";

		private boolean enableFormula = true;

		private boolean enableTable = true;

		private String language = "ch";

		private long pollingIntervalMs = 5000L;

		private int maxPollingAttempts = 72;

		private int connectTimeoutMs = 10000;

		private int readTimeoutMs = 60000;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getApiBaseUrl() {
			return apiBaseUrl;
		}

		public void setApiBaseUrl(String apiBaseUrl) {
			this.apiBaseUrl = apiBaseUrl;
		}

		public String getApiToken() {
			return apiToken;
		}

		public void setApiToken(String apiToken) {
			this.apiToken = apiToken;
		}

		public String getModelVersion() {
			return modelVersion;
		}

		public void setModelVersion(String modelVersion) {
			this.modelVersion = modelVersion;
		}

		public boolean isEnableFormula() {
			return enableFormula;
		}

		public void setEnableFormula(boolean enableFormula) {
			this.enableFormula = enableFormula;
		}

		public boolean isEnableTable() {
			return enableTable;
		}

		public void setEnableTable(boolean enableTable) {
			this.enableTable = enableTable;
		}

		public String getLanguage() {
			return language;
		}

		public void setLanguage(String language) {
			this.language = language;
		}

		public long getPollingIntervalMs() {
			return pollingIntervalMs;
		}

		public void setPollingIntervalMs(long pollingIntervalMs) {
			this.pollingIntervalMs = pollingIntervalMs;
		}

		public int getMaxPollingAttempts() {
			return maxPollingAttempts;
		}

		public void setMaxPollingAttempts(int maxPollingAttempts) {
			this.maxPollingAttempts = maxPollingAttempts;
		}

		public int getConnectTimeoutMs() {
			return connectTimeoutMs;
		}

		public void setConnectTimeoutMs(int connectTimeoutMs) {
			this.connectTimeoutMs = connectTimeoutMs;
		}

		public int getReadTimeoutMs() {
			return readTimeoutMs;
		}

		public void setReadTimeoutMs(int readTimeoutMs) {
			this.readTimeoutMs = readTimeoutMs;
		}

	}

}
