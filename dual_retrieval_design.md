# 设计方案：Milvus+ES 双路检索模式

## 1. 目标与约束

**目标**：新增 `vector-store-type: milvus-es` 模式，实现 Milvus（HNSW 向量检索）+ ES（BM25 关键词检索）两路并行、应用层 RRF 融合的混合检索，供与现有单 ES 混合检索方案做对比测试。

**约束**：
- 现有四种模式（`simple` / `elasticsearch` / `elasticsearch+hybrid` / `milvus`）代码和行为零改动
- 新模式以可选配置项接入，不影响默认启动
- 复用已有的 `RrfFusionStrategy`，不重复实现 RRF

---

## 2. 整体架构

### 2.1 模式对比

| 模式 | 向量检索 | 关键词检索 | 融合位置 | 写入目标 |
|------|---------|-----------|---------|---------|
| `simple` | SimpleVectorStore | 无 | 无 | SimpleVectorStore |
| `elasticsearch` | ES KNN | 无 | 无 | ES |
| `elasticsearch` + hybrid=true | ES KNN | ES BM25 | ES 引擎内 RRF | ES |
| `milvus` | Milvus HNSW | 无 | 无 | Milvus |
| **`milvus-es`（新增）** | **Milvus HNSW** | **ES BM25** | **应用层 RRF** | **Milvus + ES 双写** |

### 2.2 数据流

```
写入路径（milvus-es 模式）
  VectorStoreDataIngestionService
    └─ ragVectorStore.add(docs)
         └─ DualWriteVectorStore（新）
              ├─ milvusVectorStore.add(docs)   并行写入
              └─ dualEsVectorStore.add(docs)   并行写入

检索路径（milvus-es 模式）
  DefaultHybridRagProcessor.hybridRetrieve()
    └─ dualPathRetriever.retrieve(query, options)   （新分支）
         ├─ Milvus: milvusVectorStore.similaritySearch()
         │    过滤：FilterExpressionBuilder（source_type + session_id）
         │    返回：向量语义相关文档列表
         │
         └─ ES BM25: match query via RestClient
              过滤：BoolQuery term filter（source_type + session_id）
              返回：关键词精确匹配文档列表
                │
                ▼
         RrfFusionStrategy.fuse([milvusResults, esBm25Results])
              RRF 公式：score(doc) = Σ 1/(k + rank_i)
              topK + threshold 截断
                │
                ▼
         融合后的文档列表
```

---

## 3. 新增/修改文件清单

### 3.1 新增文件

| 文件 | 包路径 | 职责 |
|------|--------|------|
| `DualWriteVectorStore.java` | `config/rag/` | `VectorStore` 包装器，写入时并行写 Milvus 和 ES；读取委托 Milvus |
| `MilvusEsDualPathRetriever.java` | `rag/retriever/` | 双路检索核心：Milvus 向量 + ES BM25，返回两路独立结果供 RRF |

### 3.2 修改文件

| 文件 | 改动范围 |
|------|---------|
| `RagVectorStoreConfiguration.java` | 新增内部类 `DualMilvusEsVectorStoreConfiguration`，注册四个 Bean |
| `DefaultHybridRagProcessor.java` | 新增 `ObjectProvider<MilvusEsDualPathRetriever>` 构造参数，新增第三条检索分支 |
| `RagProperties.java` | `Milvus` 嵌套类补充 `dual` 模式专用配置项（BM25 boost 权重等） |
| `application.yml` | 新增 `milvus-es` 模式注释配置示例 |
| `peizhi.md` | 新增 4.3 节说明 `milvus-es` 模式 |
| `CLAUDE.md` | Feature Toggles 补充 `milvus-es` 选项 |

---

## 4. 详细设计

### 4.1 DualWriteVectorStore

**职责**：实现 `VectorStore` 接口，作为 `ragVectorStore` Bean，屏蔽双写复杂性。

**写入语义**：
- `add(docs)`：并发提交 Milvus 和 ES 两个写操作（`CompletableFuture.allOf`），任一失败记录 ERROR 日志但不抛出（best-effort 语义），避免单点失败阻塞整个摄入流程。
- `delete(Filter.Expression)`：串行删除，先 Milvus 后 ES，失败均记录并继续。

**读取语义**：
- `similaritySearch(SearchRequest)`：委托给 `milvusVectorStore`（本方法在 `milvus-es` 模式下不被 `DefaultHybridRagProcessor` 调用，只作为接口合规实现）。

**一致性说明**：双写不保证原子性。极端情况下（如写 Milvus 成功、写 ES 失败），两端数据会短暂不一致，对比测试时可通过日志确认两端写入状态。如需强一致，可在摄入接口层增加幂等重试，但这超出本方案范围。

### 4.2 MilvusEsDualPathRetriever

**职责**：封装双路并行检索和元数据过滤，对外暴露 `retrieve(Query, Map<String,Object>)` 方法，返回两路独立文档列表供调用方做 RRF。

**Milvus 路径**：
- 使用 `FilterExpressionBuilder` 构建 `source_type` + `session_id` 过滤表达式（与现有 `performVectorSearch` 逻辑一致）
- 调用 `milvusVectorStore.similaritySearch(SearchRequest)`
- `topK` 取 `pipeline.topK`

**ES BM25 路径**：
- 构建 ES `BoolQuery`：`must match(content, queryText)` + `filter term(metadata.source_type)` + `filter term(metadata.session_id)`
- 通过 `RestClient` 发起请求（复用 `RrfHybridElasticsearchRetriever` 中已有的 `ElasticsearchClient` 构造方式）
- 不加 `knn` 子句（纯 BM25，不让 ES 做向量检索）
- 结果数量：`rrfWindowSize`（默认 10）

**返回值**：`List<List<Document>>`，第一个列表为 Milvus 结果，第二个为 ES BM25 结果，顺序固定，供 `RrfFusionStrategy.fuse()` 消费。

**超时与降级**：两路并发执行（`CompletableFuture`），单路超时（可配置，默认 3s）时退化为只返回另一路结果，记录 WARN 日志，不抛异常。

### 4.3 DualMilvusEsVectorStoreConfiguration

在 `RagVectorStoreConfiguration` 中新增内部配置类，激活条件：`vector-store-type=milvus-es`。

创建如下四个 Bean：

| Bean 名称 | 类型 | 用途 |
|-----------|------|------|
| `milvusVectorStore` | `MilvusVectorStore` | Milvus 向量检索，HNSW+COSINE |
| `elasticsearchRestClient` | `RestClient` | ES 连接，供 BM25 检索和写入使用 |
| `dualEsVectorStore` | `ElasticsearchVectorStore` | ES 写入端（存储文本+向量供 BM25 索引） |
| `ragVectorStore` | `DualWriteVectorStore` | 注入 `VectorStoreDataIngestionService`，同时写 Milvus 和 ES |

另注册一个 `MilvusEsDualPathRetriever` Bean，持有 `milvusVectorStore`、`elasticsearchRestClient`、`RagProperties` 的引用。

### 4.4 DefaultHybridRagProcessor 改动

新增第三个构造参数（`ObjectProvider<MilvusEsDualPathRetriever>`），在构造函数中：

```
dualPathRetriever = dualPathRetrieverProvider.getIfAvailable()
```

`hybridRetrieve` 方法增加第三条分支（优先级低于现有 ES hybrid 分支）：

```
if (hybridRetriever != null)
    → ES 引擎内 BM25+KNN+RRF（现有逻辑，不变）
else if (dualPathRetriever != null)
    → MilvusEsDualPathRetriever.retrieve() → RrfFusionStrategy.fuse()（新增）
else
    → performVectorSearch()（现有逻辑，不变）
```

**注意**：`dualPathRetriever != null` 时，`postProcess` 中的 `RrfFusionStrategy.process()` 不再重复执行（双路融合已在检索阶段完成），需在 `postProcess` 前判断跳过 rerank，直接返回融合结果。可通过在 options 中传入 `_already_fused=true` 标记实现，或在 `dualPathRetriever` 模式下直接返回融合后列表、跳过 `postProcess`。

### 4.5 新增配置项

在 `RagProperties.Milvus` 中新增 `DualMode` 嵌套类：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `dual.bm25-top-k` | int | 10 | ES BM25 单路返回文档数 |
| `dual.vector-top-k` | int | 10 | Milvus 向量单路返回文档数 |
| `dual.retrieval-timeout-ms` | int | 3000 | 单路超时（毫秒） |
| `dual.bm25-rrf-weight` | float | 1.0 | BM25 结果列表在 RRF 中的权重（影响排名计算，扩展用） |
| `dual.vector-rrf-weight` | float | 1.0 | 向量结果列表在 RRF 中的权重 |

RRF k 常数复用现有 `rag.fusion.rrf.k-constant: 60` 配置。

---

## 5. 配置示例（application.yml）

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          enabled: true
          vector-store-type: milvus-es   # 新模式
          milvus:
            host: localhost
            port: 19530
            collection-name: deepresearch_vectors
            embedding-dimension: 1536
            dual:
              bm25-top-k: 10
              vector-top-k: 10
              retrieval-timeout-ms: 3000
          elasticsearch:
            uris: http://localhost:9200
            index-name: spring-ai-rag-es-index
            dimensions: 1536
            similarity-function: cosine
            hybrid:
              enabled: false  # dual 模式下不使用 ES KNN，此项必须 false
```

---

## 6. 对比测试方案

通过切换 `vector-store-type` 来对比两种模式：

| 对比项 | 单 ES（`elasticsearch` + hybrid=true） | Milvus+ES（`milvus-es`） |
|--------|--------------------------------------|------------------------|
| 向量检索引擎 | ES Lucene HNSW | Milvus 原生 HNSW |
| BM25 引擎 | ES（同一实例） | ES（独立 BM25 查询） |
| 融合位置 | ES 引擎内（rank.rrf） | 应用层（RrfFusionStrategy） |
| 融合可控性 | 仅 rankConstant/windowSize | 可自定义每路权重 |

**测试步骤建议**：
1. 准备同一份文档，分别以两种模式写入（注意写入时切换模式，或同一份数据分别写入对应存储）
2. 准备一组查询集（兼顾语义查询和关键词精确查询两类）
3. 记录每种模式下每个查询的 top-K 结果集、召回率、MRR（Mean Reciprocal Rank）
4. 重点关注：纯关键词查询（单 ES BM25 更强）、语义模糊查询（Milvus HNSW 更强）两类极端情况下的差异

---

## 7. 风险与注意事项

1. **双写不一致**：Milvus 写成功但 ES 写失败时，向量检索有结果而 BM25 无结果，融合结果偏向 Milvus 一侧。监控两端写入日志可发现。

2. **ES 存储冗余**：`milvus-es` 模式下 ES 也存了向量字段（`ElasticsearchVectorStore` 默认行为），实际上双路检索不使用 ES KNN，这部分向量占用 ES 存储资源。如存储敏感，可考虑自定义 ES mapping 不存 `dense_vector` 字段，但需绕过 Spring AI 的自动 schema 初始化，增加实现复杂度，不在本方案范围内。

3. **TTL 清理**：`milvus-es` 模式下用户临时文件的 `expire_at` 清理需同时覆盖两端。`MilvusUserSessionCleanupService` 目前只清 Milvus，需同步清理 ES 端的过期文档。这是本方案遗留的一个待办项，需在实现阶段补充。

4. **查询超时叠加**：两路并发超时为 3s，若两路均达到超时上限，总等待时间仍是 3s（并发非串行），不影响整体响应时间上限。

---

## 8. 不需要改动的文件

- `VectorStoreDataIngestionService`：通过 `@Qualifier("ragVectorStore")` 注入，自动拿到 `DualWriteVectorStore`，写入逻辑透明切换，无需修改。
- `UserFileRetrievalStrategy` / `ProfessionalKbEsStrategy`：只设置 options 并调用 `hybridRagProcessor.process()`，新分支在 `DefaultHybridRagProcessor` 内部处理，策略类无感知。
- `RagNode` / `RagNodeService`：不感知底层检索实现，无需修改。
- `MilvusUserSessionCleanupService`：当前只清 Milvus，`milvus-es` 模式下需额外清 ES，但可作为独立补丁处理，不阻塞主方案实现。
