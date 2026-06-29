# DeepResearch RAG 模块详解

## 一、整体架构概览

RAG（Retrieval Augmented Generation，检索增强生成）模块的核心思想：
**不依赖 LLM 自身的参数知识，而是先从知识库检索相关文档，再将文档内容作为上下文喂给 LLM 生成答案，提升专业性和时效性。**

项目的 RAG 模块分为两条独立链路，共享同一套基础组件：

```
链路一：用户上传文件 RAG
用户上传文件 → VectorStore(user_upload) → user_file_rag 节点

链路二：专业知识库 RAG
配置文件定义知识库 → 触发 professional_kb_decision 节点 → LLM 决策 → professional_kb_rag 节点
                                                                         ↑ 两种实现
                                                               ES 存储(professional_kb_es)
                                                               API 接口(professional_kb_api)
```

RAG 模块整体功能开关：`spring.ai.alibaba.deepresearch.rag.enabled=true`

---

## 二、图中 RAG 相关节点及路由

```
coordinator（决定是否深度研究）
    ↓
rewrite_multi_query（查询优化：压缩→重写→扩展）
    ↓ [rewrite_multi_query_next_node]
    ├── user_upload_file=true  → user_file_rag（用户文件RAG）
    └── 否则                  → background_investigator（普通搜索）

background_investigator（网络搜索完成后）
    ↓
planner → research_team → reporter（报告生成前）
    ↓
professional_kb_decision（LLM决策是否需要专业库）
    ↓ [use_professional_kb]
    ├── true  → professional_kb_rag
    └── false → reporter
```

---

## 三、文件结构

```
config/rag/
  RagProperties.java             # RAG 全部配置项（特性开关、ES参数、知识库定义等）
  RagVectorStoreConfiguration.java  # 创建 VectorStore Bean（SimpleVectorStore 或 ElasticsearchVectorStore）
  RagDataAutoConfiguration.java  # 应用启动时自动摄入初始数据 + 定时扫描目录

rag/
  SourceTypeEnum.java            # 数据来源枚举：user_upload / professional_kb_es / professional_kb_api
  core/
    HybridRagProcessor.java      # RAG 处理器接口（preProcess/hybridRetrieve/postProcess）
    DefaultHybridRagProcessor.java  # 接口的完整实现（三阶段管道）
  transformer/
    HyDeTransformer.java         # HyDE：生成假设性文档替代原始查询向量
  retriever/
    RrfHybridElasticsearchRetriever.java  # ES 混合检索：BM25 + KNN + RRF
  strategy/
    RetrievalStrategy.java       # 检索策略接口
    FusionStrategy.java          # 融合策略接口
    UserFileRetrievalStrategy.java    # 用户文件检索策略
    ProfessionalKbEsStrategy.java     # 专业知识库ES检索策略
    ProfessionalKbApiStrategy.java    # 专业知识库API检索策略
    RrfFusionStrategy.java            # RRF融合+rerank实现
  post/
    DocumentSelectFirstProcess.java   # 后处理：仅保留第一条文档
  kb/
    ProfessionalKbApiClient.java         # 专业知识库API客户端接口
    ProfessionalKbApiClientFactory.java  # 工厂：根据配置创建 DashScope 或 Custom 客户端
    impl/
      DashScopeKbApiClient.java    # DashScope 知识库 API 客户端实现
      CustomKbApiClient.java       # 自定义 HTTP API 客户端实现
    model/
      KbSearchResult.java          # API 返回的搜索结果 DTO

node/
  RewriteAndMultiQueryNode.java  # 查询优化节点（三步优化链）
  RagNode.java                   # RAG 执行节点（检索+生成）
  ProfessionalKbDecisionNode.java  # LLM决策是否使用专业知识库

service/
  VectorStoreDataIngestionService.java  # 文档摄入服务（解析+切分+存储）
  RagNodeService.java            # 创建 user_file_rag 和 professional_kb_rag 节点的工厂服务

dispatcher/
  RewriteAndMultiQueryDispatcher.java  # 读取 rewrite_multi_query_next_node 路由
  ProfessionalKbDispatcher.java         # 读取 use_professional_kb 路由

controller/
  RagDataController.java         # HTTP 接口：上传文档到向量库
```

---

## 四、文档摄入链路（Indexing Pipeline）

### 4.1 摄入流程

```
HTTP 上传 / 启动加载 / 定时扫描
    ↓
TikaDocumentReader（支持 PDF / Word / Markdown / HTML 等 40+ 格式）
    ↓
TokenTextSplitter（按 Token 数切分，默认 chunk=800，overlap=100）
    ↓
元数据富化（添加 source_type / session_id / user_id / kb_id / chunk_id / title 等字段）
    ↓
VectorStore.add(enrichedChunks)（自动调用 EmbeddingModel 向量化后存入）
```

### 4.2 三类元数据标签（检索时用于隔离数据）

| source_type | 说明 | 关键元数据字段 |
|---|---|---|
| `user_upload` | 用户本次会话上传的文件 | `session_id`、`user_id`、`file_size` |
| `professional_kb_es` | 管理员预先上传到 ES 的专业文档 | `kb_id`、`kb_name`、`kb_description`、`session_id="professional_kb_es"` |
| `professional_kb_api` | 通过 API 实时查询的外部知识库 | `kb_id`、`api_provider`、`source_url` |

### 4.3 关键类：`VectorStoreDataIngestionService`

```java
// 用户上传文件（携带 session_id 隔离）
ingestionService.processAndStore(file, sessionId, userId);

// 批量上传到专业知识库ES
ingestionService.batchUploadToProfessionalKbEs(files, kbId, kbName, kbDescription, category);
```

---

## 五、向量存储配置（`RagVectorStoreConfiguration`）

### 5.1 两种向量存储模式

**模式1：SimpleVectorStore（开发/测试）**
- 配置：`vector-store-type=simple`（默认）
- 实现：Java HashMap 内存存储，支持 JSON 文件持久化
- 优点：零依赖，快速启动

**模式2：ElasticsearchVectorStore（生产）**
- 配置：`vector-store-type=elasticsearch`
- 实现：ES dense_vector 字段 + KNN 索引
- 优点：支持混合检索（BM25 + 向量），支持亿级文档

```yaml
spring.ai.alibaba.deepresearch.rag:
  enabled: true
  vector-store-type: elasticsearch
  elasticsearch:
    uris: http://localhost:9200
    index-name: deepresearch-rag
    dimensions: 1536
    similarity-function: COSINE
    hybrid:
      enabled: true
      bm25-boost: 1.0
      knn-boost: 1.0
      rrf-window-size: 10
      rrf-rank-constant: 60
```

---

## 六、Pre-Retrieval：查询优化（`RewriteAndMultiQueryNode`）

在检索之前对用户原始提问进行三步优化，提升检索质量。

### 6.1 三步优化链

```
原始查询（口语化、可能含代词引用）
    ↓
① CompressionQueryTransformer（可选，需短期记忆启用）
   - 作用：消解多轮对话中的引用
   - 例："上面那个框架怎么配置？" → "Spring AI Alibaba Graph 如何配置？"
   - 原理：将 MessageWindowChatMemory 历史消息附加到 Query.history，LLM 理解上下文后改写
    ↓
② RewriteQueryTransformer（始终执行）
   - 作用：语义精炼，去除口语化、歧义表达
   - 例："怎么用rag" → "RAG（检索增强生成）的实现原理与使用方法"
   - 默认 Prompt：改写以优化在 vector store 中的检索效果
    ↓
③ MultiQueryExpander（始终执行）
   - 作用：生成 N 条语义变体查询，并行检索后合并，提升召回率
   - 例：1条查询 → N+1 条（包含原始）
   - 结果写入 OverAllState["optimize_queries"]，供后续并行 researcher 使用
```

### 6.2 `Query` 对象的不可变模式

每一步 `transform()` 都返回新的 `Query` 对象，原对象不修改：
```java
// 安全的链式转换
query = compressionQueryTransformer.transform(queryWithHistory);  // 新对象
rewriteQuery = queryTransformer.transform(query);                  // 又是新对象
```

---

## 七、Pre-Retrieval 扩展：HyDE（`HyDeTransformer`）

### 7.1 问题背景

用户提问通常很短（10-50个字），而知识库文档通常很长（几百词）。两者在向量空间中分布差异大，导致语义相似度偏低，检索效果不佳。

### 7.2 HyDE 解法

```
原始查询："RAG 如何提升准确性？"
    ↓ LLM 生成假设性文档
假设文档："RAG（检索增强生成）通过在生成答案前先检索相关文档，
          将外部知识注入到 LLM 的上下文中，显著提升了回答的准确性和时效性。
          具体而言，它解决了 LLM 参数知识过时、幻觉等问题..."
    ↓ 用假设文档的向量去检索真实文档
真实文档（与假设文档处于同一语义空间，相似度更高）
```

### 7.3 配置开关
```yaml
spring.ai.alibaba.deepresearch.rag.pipeline:
  hypothetical-document-embedding-enabled: true
```

---

## 八、Retrieval：混合检索

### 8.1 两种检索模式

**模式A：SimpleVectorStore 纯向量检索**
- 仅 KNN 余弦相似度检索
- 通过 `FilterExpressionBuilder` 构建元数据过滤条件

**模式B：ES 混合检索（`RrfHybridElasticsearchRetriever`）**
- BM25（全文）+ KNN（向量）双路检索
- ES 内置 `rank.rrf` 算法融合两路结果

### 8.2 ES 混合检索原理

```
用户查询文本
    ↓ 并行执行
┌─────────────────────────────┐    ┌─────────────────────────────┐
│ BM25（match 查询）           │    │ KNN（knn 向量查询）           │
│ - 匹配 content 字段          │    │ - 查询文本 → EmbeddingModel  │
│ - 词频×逆文档频率             │    │ - 向量余弦相似度              │
│ - 擅长精确关键词              │    │ - 擅长同义词/语义相关         │
└─────────────────────────────┘    └─────────────────────────────┘
                    ↓ ES 内置 rank.rrf 融合
                 RRF 排序结果（取 windowSize 条）
```

### 8.3 RRF 公式

```
score(doc) = Σ 1 / (k + rank_i)

其中：
- rank_i = 文档在第 i 路检索结果中的排名（从1开始）
- k = 60（默认平滑常数，防止排名第1的文档得分过高）
- 文档同时出现在两路结果中：score = 1/(60+rank_bm25) + 1/(60+rank_knn)
```

### 8.4 数据隔离过滤

检索时通过 `buildFilterExpression()` 构建 ES Bool 过滤条件，确保用户只能检索到自己的数据：

```java
// 用户文件检索：只返回当前 session_id 的 user_upload 数据
options.put("source_type", "user_upload");
options.put("session_id", sessionId);

// 专业知识库ES检索：只返回 professional_kb_es 数据
options.put("source_type", "professional_kb_es");
options.put("session_id", "professional_kb_es");  // 固定值，所有专业知识库共用
```

---

## 九、Post-Retrieval：结果后处理

### 9.1 RRF Rerank（`RrfFusionStrategy`）

当 `rerankEnabled=true` 时（默认），对检索结果按 `source_type` 分组，再次执行 RRF 融合排序：

```
检索到的文档列表（可能来自多条扩展查询，混杂多个来源）
    ↓ 按 source_type 分组
[user_upload 组]    [professional_kb_es 组]
    ↓ RRF 跨组融合排序
最终排序结果（topK 截断，过滤 score < threshold 的文档）
```

**RRF Rerank 的意义**：当使用了 MultiQueryExpander 生成 3 条查询时，每条查询各自检索到 5 条文档，共 15 条。RRF Rerank 将这 15 条按排名信息重新打分合并，去除冗余，输出最终 top-10。

### 9.2 DocumentSelectFirstProcess

当 `postProcessingSelectFirstEnabled=true` 且 `rerankEnabled=false` 时，直接只取排名第一的文档，大幅节省 LLM context。

---

## 十、专业知识库（Professional Knowledge Base）

### 10.1 两种接入方式

**方式1：ES 存储（`ProfessionalKbEsStrategy`）**
- 管理员提前通过 `POST /api/rag/professional-kb/upload` 上传文档
- 文档经过摄入管道（Tika → 切分 → 向量化）存入 ES，`source_type=professional_kb_es`
- 检索时走 `DefaultHybridRagProcessor`，支持 BM25 + KNN 混合检索

**方式2：API 实时查询（`ProfessionalKbApiStrategy`）**
- 外部知识库通过 REST API 提供搜索能力（如 DashScope 知识库 API）
- `ProfessionalKbApiClientFactory` 根据配置创建对应客户端（`DashScopeKbApiClient` 或 `CustomKbApiClient`）
- 检索时直接调用外部 API，将结果转为 `Document` 格式后进行后处理

### 10.2 LLM 决策节点（`ProfessionalKbDecisionNode`）

在生成最终报告前，由 LLM 判断是否需要查专业知识库：

```
构建决策 Prompt：
  "用户查询：XXX
   可用的专业知识库：
   1. [finance-kb] 金融市场知识库 - 描述：包含A股、港股市场数据..."
   请分析是否需要查询以上专业知识库，返回 SELECTED: [kb_id1, ...]"
    ↓ LLM 响应
解析 SELECTED 格式 → 写入 OverAllState["selected_knowledge_bases"]
```

配置示例：
```yaml
spring.ai.alibaba.deepresearch.rag.professional-knowledge-bases:
  decision-enabled: true
  knowledge-bases:
    - id: finance-kb
      name: 金融知识库
      description: 包含 A 股、港股历史行情及研报数据
      type: api                    # api 或 elasticsearch
      enabled: true
      priority: 10
      api:
        provider: dashscope        # dashscope 或 custom
        api-key: ${DASHSCOPE_KB_API_KEY}
        model: text-search-babbage-001
        max-results: 5
```

---

## 十一、RAG 节点执行逻辑（`RagNode`）

```java
// 步骤1：从 OverAllState 获取查询文本和用户上下文
queryText = StateUtil.getQuery(state);
options.put("session_id", sessionId);
options.put("user_id", userId);

// 步骤2：执行完整 RAG 管道（前处理→检索→后处理）
documents = hybridRagProcessor.process(new Query(queryText), options);

// 步骤3：将文档内容拼接为上下文
context = documents.stream().map(Document::getText).join("\n");

// 步骤4：上下文 + 问题 → LLM 生成答案（流式）
Flux<ChatResponse> stream = ragAgent.prompt()
    .messages(new UserMessage(context))
    .user(queryText)
    .stream().chatResponse();

// 步骤5：流式结果写入 OverAllState["rag_content"]，推送给前端 SSE
```

---

## 十二、`RagNodeService`：节点工厂

`RagNodeService` 是创建 RAG 节点的工厂，`DeepResearchConfiguration` 调用它来创建图节点：

```java
// 在 DeepResearchConfiguration 中
stateGraph.addNode("user_file_rag",        ragNodeService.createUserFileRagNode());
stateGraph.addNode("professional_kb_rag",  ragNodeService.createProfessionalKbRagNode());
```

两个节点使用相同的 `RagNode` 实现，区别仅在于 `source_type` 过滤条件不同（由调用方写入 options）。

---

## 十三、完整 RAG 调用链路总结

### 用户文件 RAG 完整链路

```
1. 用户上传文件（POST /api/rag/user/batch-upload）
   → TikaDocumentReader 解析
   → TokenTextSplitter 切分（chunk=800 token，overlap=100）
   → 添加元数据：source_type=user_upload, session_id=xxx
   → EmbeddingModel 向量化
   → VectorStore.add()

2. 用户提问，coordinator 判断需要深度研究
   → RewriteAndMultiQueryNode：
     CompressionQueryTransformer（可选）
     → RewriteQueryTransformer（重写）
     → MultiQueryExpander（扩展 N 条）
     → 写入 OverAllState["optimize_queries"]
     → 路由到 user_file_rag

3. RagNode（user_file_rag 实例）：
   → options: {source_type: "user_upload", session_id: xxx}
   → DefaultHybridRagProcessor.process()
     ① preProcess：翻译/扩展/HyDE（按配置）
     ② hybridRetrieve：ES混合检索 或 SimpleVectorStore 向量检索（带 session_id 过滤）
     ③ postProcess：RRF rerank（topK 截断）
   → 文档内容 → ragAgent LLM → 流式答案
   → 写入 OverAllState["rag_content"]
```

### 专业知识库 RAG 完整链路

```
1. 管理员上传文档或配置外部 API（application.yml）

2. research_team 执行完成，进入 professional_kb_decision
   → ProfessionalKbDecisionNode：
     LLM 分析查询 + 知识库描述
     → 写入 OverAllState["use_professional_kb", "selected_knowledge_bases"]

3. ProfessionalKbDispatcher 路由：
   → use_professional_kb=true → professional_kb_rag
   → false → reporter

4. RagNode（professional_kb_rag 实例）：
   - ES 模式：options: {source_type: "professional_kb_es"}
     → DefaultHybridRagProcessor（同上）
   - API 模式：ProfessionalKbApiStrategy
     → 调用 DashScopeKbApiClient.search() 或 CustomKbApiClient.search()
     → KbSearchResult → Document
     → hybridRagProcessor.postProcess()（RRF rerank）
   → ragAgent LLM → 答案注入报告
```

---

## 十四、RAG 相关配置速查

```yaml
spring.ai.alibaba.deepresearch.rag:
  enabled: false                       # 总开关，默认关闭
  vector-store-type: simple            # simple / elasticsearch

  pipeline:
    query-expansion-enabled: false     # MultiQueryExpander（扩展为多条查询）
    query-translation-enabled: false   # TranslationQueryTransformer（查询翻译）
    hypothetical-document-embedding-enabled: false  # HyDE
    query-translation-language: English
    top-k: 5                           # 向量检索返回数量
    similarity-threshold: 0.7          # 向量相似度阈值
    deduplication-enabled: true        # 多查询结果去重
    rerank-enabled: true               # RRF rerank（默认启用）
    rerank-top-k: 10
    rerank-threshold: 0.5
    post-processing-select-first-enabled: false  # 只取第一条

  text-splitter:
    default-chunk-size: 800
    overlap: 100
    min-chunk-size-to-split: 5
    max-chunk-size: 10000
    keep-separator: true

  data:
    locations:                         # 启动时自动摄入
      - classpath:/data/*.md
    scan:
      enabled: false                   # 定时扫描目录
      directory: /path/to/scan
      cron: "0 0 * * * *"
      archive-directory: /path/to/archive

  elasticsearch:
    uris: http://localhost:9200
    index-name: spring-ai-rag-es-index
    dimensions: 1536
    similarity-function: COSINE
    hybrid:
      enabled: false
      bm25-boost: 1.0
      knn-boost: 1.0
      rrf-window-size: 10
      rrf-rank-constant: 60
```
