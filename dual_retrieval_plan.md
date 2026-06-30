# 任务计划：Milvus+ES 双路混合检索实现

## 目标

1. 新增 `vector-store-type: milvus-es` 模式，实现 Milvus（HNSW 向量检索）+ ES（BM25 关键词检索）两路并行、应用层 RRF 融合的混合检索。
2. 不破坏现有 `simple` / `elasticsearch` / `milvus` 三种模式的代码与行为。
3. 为未来与单 ES 混合检索（`elasticsearch` + `hybrid=true`）进行对比测试提供基础。

详细设计见 [dual_retrieval_design.md](./dual_retrieval_design.md)。

---

## 阶段与任务

### 阶段 1：配置层扩展
**目标**：让 Spring 容器识别 `milvus-es` 模式，注册所需 Bean，不影响其他模式。

- [ ] 1.1 在 `RagProperties.Milvus` 中新增 `DualMode` 嵌套配置类
  - 字段：`bm25TopK`（默认10）、`vectorTopK`（默认10）、`retrievalTimeoutMs`（默认3000）、`bm25RrfWeight`（默认1.0f）、`vectorRrfWeight`（默认1.0f）
  - 补充对应 getter/setter
- [ ] 1.2 在 `RagVectorStoreConfiguration` 中新增内部类 `DualMilvusEsVectorStoreConfiguration`
  - 激活条件：`@ConditionalOnProperty(... havingValue = "milvus-es")`
  - 创建 `milvusVectorStore` Bean（HNSW + COSINE，`initializeSchema=true`）
  - 创建 `elasticsearchRestClient` Bean（复用 `ElasticsearchVectorStoreConfiguration` 中相同的构建逻辑）
  - 创建 `dualEsVectorStore` Bean（`ElasticsearchVectorStore`，名称区别于 `ragVectorStore`，仅供写入）
  - 创建 `ragVectorStore` Bean（类型为 `DualWriteVectorStore`，见阶段2）

---

### 阶段 2：DualWriteVectorStore（写入层）
**目标**：实现 `VectorStore` 包装器，屏蔽双写复杂性，对 `VectorStoreDataIngestionService` 透明。

- [ ] 2.1 新建 `DualWriteVectorStore.java`（包路径：`config/rag/`）
  - 构造参数：`VectorStore milvusStore`、`VectorStore esStore`
  - `add(List<Document>)`：`CompletableFuture.allOf` 并发写两端，任一失败记录 ERROR、不抛出异常
  - `delete(Filter.Expression)`：串行执行，先 Milvus 后 ES，失败记录 ERROR 继续执行
  - `similaritySearch(SearchRequest)`：委托给 `milvusStore`（接口合规实现，此方法在 `milvus-es` 模式下不被检索路径直接调用）
  - 添加写入结果日志（成功写入数量、失败端标识）

---

### 阶段 3：MilvusEsDualPathRetriever（检索层）
**目标**：封装 Milvus 向量检索 + ES BM25 两路并行检索逻辑，返回两路独立文档列表。

- [ ] 3.1 新建 `MilvusEsDualPathRetriever.java`（包路径：`rag/retriever/`）
  - 构造参数：`VectorStore milvusVectorStore`、`RestClient restClient`、`RagProperties ragProperties`
  - 内部构建 `ElasticsearchClient`（复用 `RrfHybridElasticsearchRetriever` 相同的 `JacksonJsonpMapper` + `RestClientTransport` 方式）
  - `retrieve(Query query, Map<String,Object> options)` 返回 `List<List<Document>>`
    - **Milvus 路径**：使用 `FilterExpressionBuilder` 构建 `source_type` + `session_id` 过滤，调用 `milvusVectorStore.similaritySearch()`，`topK` 取 `dual.vectorTopK`
    - **ES BM25 路径**：构建 `BoolQuery`（`must match(content)` + `filter term(metadata.source_type)` + `filter term(metadata.session_id)`），**不加 `knn` 子句**，`size` 取 `dual.bm25TopK`
    - 两路通过 `CompletableFuture` 并发执行，超时（`dual.retrievalTimeoutMs`）时对应路返回空列表并记录 WARN
    - 返回 `[milvusResults, esBm25Results]`（固定顺序）
- [ ] 3.2 在 `DualMilvusEsVectorStoreConfiguration` 中注册 `MilvusEsDualPathRetriever` Bean
  - 注入 `milvusVectorStore`、`elasticsearchRestClient`、`RagProperties`

---

### 阶段 4：DefaultHybridRagProcessor 接入双路分支
**目标**：在现有两条检索路径之间插入第三条分支，不影响现有逻辑。

- [ ] 4.1 在构造函数中新增 `ObjectProvider<MilvusEsDualPathRetriever> dualPathRetrieverProvider` 参数
  - `this.dualPathRetriever = dualPathRetrieverProvider.getIfAvailable()`
- [ ] 4.2 在 `hybridRetrieve` 方法的 `else if` 位置插入双路分支
  ```
  if (hybridRetriever != null)        → 现有 ES hybrid 逻辑（不变）
  else if (dualPathRetriever != null) → 新增：调用 dualPathRetriever.retrieve() 获取两路结果
                                          → RrfFusionStrategy.fuse(twoLists) 应用层 RRF 融合
  else                                → 现有 performVectorSearch()（不变）
  ```
- [ ] 4.3 在 `postProcess` 方法中增加跳过重复 rerank 的判断
  - 当通过双路路径执行时，RRF 已在检索阶段完成，`postProcess` 跳过 `rrfFusionStrategy.process()`，直接返回已融合的文档列表
  - 实现方式：在 `hybridRetrieve` 返回时携带 `_already_fused` 标记（通过包装类或 options 传递），或在 `process()` 顶层通过模式判断跳过

---

### 阶段 5：配置与文档
**目标**：补充配置示例和文档，确保可开箱测试。

- [ ] 5.1 在 `application.yml` 新增 `milvus-es` 模式注释配置示例（紧接现有 Milvus 注释块之后）
- [ ] 5.2 在 `peizhi.md` 新增 4.3 节"Milvus+ES 双路模式"
  - 说明适用场景（对比测试 / 超大规模向量 + BM25 结合）
  - 完整 YAML 配置示例（含 Milvus + ES 两端参数）
  - 注意事项：`elasticsearch.hybrid.enabled` 必须为 `false`；写入前确保两端服务可用
  - 两端服务 Docker 快速启动命令
- [ ] 5.3 在 `CLAUDE.md` Feature Toggles 部分补充 `milvus-es` 选项说明

---

### 阶段 6：TTL 清理补丁（milvus-es 模式专项）
**目标**：`milvus-es` 模式下用户临时文件的 TTL 清理需覆盖 ES 端。

- [ ] 6.1 评估改造方案
  - 方案 A：在 `MilvusUserSessionCleanupService` 中注入可选的 ES `RestClient`，`milvus-es` 模式下同步清理 ES
  - 方案 B：新建 `DualModeCleanupService`，仅在 `milvus-es` 模式下激活（`@ConditionalOnBean(name = "dualEsVectorStore")`），分别调用 Milvus delete 和 ES delete
- [ ] 6.2 实现选定方案
  - ES 端删除：`BoolQuery filter(source_type=user_upload) + range(expire_at < now)`，通过 `ElasticsearchClient.deleteByQuery()` 执行
  - Milvus 端删除：沿用 `VectorStore.delete(Filter.Expression)`（现有逻辑）
- [ ] 6.3 确认 `@EnableScheduling` 已覆盖新 Service（现有 `RagDataAutoConfiguration` 已有，无需重复添加）

---

## 关键决策记录

| 决策点 | 结论 | 依据 |
|--------|------|------|
| 双写一致性策略 | Best-effort，任一失败记录日志不抛出 | 摄入失败可重试，对比测试场景对短暂不一致容忍度高 |
| ES 是否存向量字段 | 是（`ElasticsearchVectorStore` 默认行为，不干预） | 避免绕过 Spring AI 自动 schema，降低实现复杂度；存储浪费可接受 |
| 双路检索并发超时 | 单路 3s，超时返回空列表继续融合 | 防止单路慢影响整体，降级有损但不阻断 |
| rerank 跳过逻辑 | 双路路径在检索阶段完成 RRF，postProcess 跳过 rerank | 避免对已融合结果二次 RRF，导致排序被破坏 |
| TTL 清理 | 作为独立阶段6处理，不阻塞主流程 | 主流程是写入+检索，清理是异步后台任务 |
| 现有模式改动 | 零改动 | 新分支通过 `ObjectProvider` 注入，`getIfAvailable()` 返回 null 时走原有逻辑 |

---

## 依赖与前提

- Milvus 2.3+ 实例（本地 Docker 或远端）
- Elasticsearch 8.x 实例（含 `dense_vector` 支持，实际双路模式下不使用 ES KNN）
- `spring-ai-starter-vector-store-milvus` 依赖已在 `pom.xml` 中（上一个任务已添加）
- `spring-ai-elasticsearch-store` 依赖已在 `pom.xml` 中（原有 ES 模式已添加）

---

## 文件变更清单

| 文件 | 变更类型 | 阶段 |
|------|---------|------|
| `config/rag/RagProperties.java` | 修改：新增 `DualMode` 嵌套类 | 1 |
| `config/rag/RagVectorStoreConfiguration.java` | 修改：新增 `DualMilvusEsVectorStoreConfiguration` 内部类 | 1 |
| `config/rag/DualWriteVectorStore.java` | **新建** | 2 |
| `rag/retriever/MilvusEsDualPathRetriever.java` | **新建** | 3 |
| `rag/core/DefaultHybridRagProcessor.java` | 修改：新增构造参数、新增检索分支 | 4 |
| `src/main/resources/application.yml` | 修改：新增注释配置示例 | 5 |
| `peizhi.md` | 修改：新增 4.3 节 | 5 |
| `CLAUDE.md` | 修改：Feature Toggles 补充 | 5 |
| `service/MilvusUserSessionCleanupService.java` | 修改或新建：补充 ES 端清理 | 6 |
