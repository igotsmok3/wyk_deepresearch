# RAG 向量存储方案对比测试计划

## 1. 目标与范围

### 1.1 测试目标

对本项目已实现的五种向量存储方案进行系统性对比测试，通过可量化指标评估各方案在不同查询类型下的检索质量与系统性能，为生产场景的方案选型提供数据依据。

### 1.2 测试范围

| 方案标识 | 配置值 | 向量检索 | 关键词检索 | 融合位置 |
|---------|--------|---------|-----------|---------|
| **Simple** | `simple` | SimpleVectorStore（内存暴力扫描） | 无 | 无 |
| **ES-KNN** | `elasticsearch`（hybrid=false） | ES Lucene HNSW | 无 | 无 |
| **ES-Hybrid** | `elasticsearch`（hybrid=true） | ES Lucene HNSW | ES BM25 | ES 引擎内 RRF |
| **Milvus** | `milvus` | Milvus 原生 HNSW | 无 | 无 |
| **Milvus-ES** | `milvus-es` | Milvus 原生 HNSW | ES BM25（纯 BM25，不含 KNN） | 应用层 RRF |

### 1.3 不测试的内容

- 嵌入模型本身的质量差异（固定使用同一模型）
- Rerank 模型的差异（固定参数，对所有方案一致）
- 前端知识库管理 UI 的可用性（属于功能测试，不在本方案范围内）

---

## 2. 设计思路

### 2.1 核心假设与验证方向

本项目各方案的核心差异源于两个维度：

**维度一：检索引擎的底层实现**

ES Lucene HNSW 和 Milvus 原生 HNSW 均基于 HNSW 图算法，但实现细节不同：
- Milvus 是专用向量数据库，针对大规模向量检索深度优化（GPU 加速、多种索引类型）
- ES 的 HNSW 是通用搜索引擎的扩展，向量检索为其次要能力，在超大规模（百万级别 +）时性能更易出现瓶颈

对于本项目的知识库规模（通常数千至数十万级别），两者差异预计在检索延迟和内存效率上体现，而不在召回质量上有显著差距。

**维度二：混合检索（BM25 + 向量）的融合策略**

- ES-Hybrid：融合在 ES 内部完成，调用链短，延迟低；但融合参数（`rank.rrf`）受 ES 版本限制，可调节空间有限
- Milvus-ES：融合在应用层完成（`RrfFusionStrategy`），可灵活调节两路权重（`bm25RrfWeight`、`vectorRrfWeight`）；代价是两次网络 RTT + 应用层 RRF 计算

**核心验证命题：**

1. 对于「语义模糊查询」（如"有关能源转型的政策背景"），向量检索路径（ES-KNN / Milvus）的召回质量是否显著优于纯 BM25？
2. 对于「关键词精确查询」（如"2024 年第三季度碳排放配额"），BM25 路径是否显著提升召回率？
3. Milvus-ES 的应用层 RRF 相比 ES-Hybrid 的引擎内 RRF，在融合效果（NDCG@10）和延迟上的差距是多少？
4. Simple 方案在文档规模增长时的延迟劣化曲线，从而量化规模边界。

### 2.2 评估指标体系

评估指标分为两类：**检索质量指标**和**系统性能指标**，两者缺一不可。

#### 检索质量指标（需要 Ground Truth）

| 指标 | 公式 | 说明 | 目标 |
|------|------|------|------|
| **Recall@K** | 检索到的相关文档数 / 总相关文档数 | 衡量召回完整性 | 主核心指标 |
| **Precision@K** | 检索到的相关文档数 / K | 衡量结果精准性 | 辅助指标 |
| **MRR** | mean(1/rank_of_first_relevant_doc) | 衡量最优结果排位 | 用于判断排序质量 |
| **NDCG@K** | 归一化折损累计增益 | 综合考量排名位置与相关性 | 主核心指标 |
| **Hit Rate@K** | 有至少 1 个相关结果命中 TopK 的查询比例 | 粗粒度召回率 | 快速对比用 |

K 取值：K = 5（对应 `pipeline.topK` 默认值），同时记录 K = 3 和 K = 10 供多视角分析。

#### 系统性能指标（无需 Ground Truth，直接测量）

| 指标 | 测量方式 | 说明 |
|------|---------|------|
| **P50 检索延迟** | 统计 50 次查询延迟中位数 | 代表典型性能 |
| **P95 检索延迟** | 统计 95 百分位延迟 | 代表尾部性能 |
| **写入吞吐量** | 文档数 / 写入耗时（秒） | 知识库建库效率 |
| **索引内存占用** | JVM Heap / 进程 RSS | 资源消耗 |
| **规模延迟曲线** | 文档数 × 方案 → 延迟矩阵 | 量化规模边界（仅 Simple 方案需重点关注） |

### 2.3 查询集设计策略

参考 BEIR（Benchmarking IR）框架的查询分类思路，将测试查询分为三类，每类各 20 条，共 60 条：

| 查询类型 | 特征 | 示例 | 预期表现 |
|---------|------|------|---------|
| **语义类** | 用概念/意图描述，不含文档原词 | "如何降低供应链的碳足迹？" | 向量检索更优 |
| **关键词类** | 含精确术语、数字、专有名词 | "碳排放配额 2024 Q3 履约截止" | BM25 更优 |
| **混合类** | 兼具语义意图与关键词约束 | "2024 年之后的新能源汽车补贴政策趋势" | 混合检索更优 |

---

## 3. 测试数据集准备

### 3.1 文档集构建

**选材原则**：选取同一主题领域（推荐：能源政策 / 气候变化 / 技术研究报告）下真实文档，避免人工造句导致的分布偏差。

**文档规模**：

| 规模档位 | 文档数 | Chunk 数（按 800 token 分块） | 用途 |
|---------|--------|---------------------------|------|
| 小规模 | 50 篇 | ~500 个 | 全方案测试，含 Simple |
| 中规模 | 500 篇 | ~5000 个 | ES / Milvus / Milvus-ES 测试 |
| 大规模 | 5000 篇 | ~50000 个 | ES / Milvus / Milvus-ES 压力测试 |

Simple 方案不参与中/大规模测试（内存暴力扫描在万级以上延迟不可接受）。

**文档来源建议**（选一即可）：
- 国家发改委、生态环境部公开政策文件（PDF 格式，用项目内置 PDF 解析）
- arXiv 开放论文（英文，适合测试跨语言语义检索）
- 项目内置专业知识库上传接口（`POST /api/rag/professional-kb/batch-upload`）导入

### 3.2 Ground Truth 构建

Ground Truth 是检索质量指标的计算前提，采用以下方法构建：

**方法：半自动标注（LLM-assisted）**

1. 对每条查询，先用 ES-Hybrid（最强基线）检索出 Top-20 候选文档
2. 将候选文档片段 + 查询送入 LLM（DashScope `qwen-max`），让模型对每个候选打「相关性等级」（0=不相关，1=部分相关，2=高度相关）
3. 人工抽查 20% 的标注结果，修正明显错误
4. 最终保存为 `ground_truth.jsonl`，格式：`{"query": "...", "relevant_chunks": [{"chunk_id": "...", "relevance": 2}, ...]}`

**chunk_id 对应关系**：通过 `VectorStoreDataIngestionService` 写入时，在 `Document.metadata` 中写入 `source_file` + `chunk_index` 字段作为唯一标识（需确认现有代码是否已写入此字段）。

### 3.3 查询集文件格式

```
queries.jsonl 格式：
{"id": "q001", "type": "semantic", "text": "..."}
{"id": "q002", "type": "keyword", "text": "..."}
{"id": "q003", "type": "hybrid",  "text": "..."}
```

---

## 4. 环境准备

### 4.1 基础设施

```
所需服务：
- Elasticsearch 8.x（用于 ES-KNN、ES-Hybrid、Milvus-ES 方案）
- Milvus 2.3+（用于 Milvus、Milvus-ES 方案）
- DeepResearch 后端（JVM 堆固定为 4GB 避免 GC 抖动）

推荐本地 Docker 快速启动：

# ES
docker run -d --name es8 -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.13.4

# Milvus（standalone 模式）
wget https://github.com/milvus-io/milvus/releases/latest/download/milvus-standalone-docker-compose.yml \
  -O docker-compose.yml && docker compose up -d
```

### 4.2 方案切换方式

每次切换方案需修改 `application.yml` 中的 `vector-store-type` 并重启应用：

```yaml
spring.ai.alibaba.deepresearch.rag:
  enabled: true
  vector-store-type: <simple | elasticsearch | milvus | milvus-es>
  elasticsearch.hybrid.enabled: <true（ES-Hybrid）| false（其余）>
```

### 4.3 固定变量（跨方案一致）

| 参数 | 固定值 | 说明 |
|------|--------|------|
| Embedding 模型 | `text-embedding-v3`（DashScope） | 维度 1536 |
| topK | 5 | `pipeline.topK` |
| Rerank | 开启，`rerankTopK=10` | 所有方案 rerank 行为一致 |
| 分块大小 | 800 token，overlap=100 | `textSplitter` 默认值 |
| RRF k 常数 | 60 | `rrfRankConstant` 默认值 |

---

## 5. 测试执行步骤

### 阶段 A：数据准备（一次性）

1. **收集文档**：下载目标领域文档，按规模档位整理到三个目录（`docs/small/`、`docs/medium/`、`docs/large/`）
2. **构建 Ground Truth**：
   - 以 ES-Hybrid 方案运行，对 60 条查询各调用 `/chat/stream`（关闭全局搜索，仅开启 RAG），收集 Top-20 候选文档
   - 调用 LLM 打分，人工复核，生成 `ground_truth.jsonl`
3. **固定随机种子**：确保各方案测试时查询顺序相同，排除顺序效应

### 阶段 B：各方案测试（对每种方案重复执行）

**B1. 建库**

- 启动对应方案配置，通过 `POST /api/rag/professional-kb/batch-upload` 批量写入文档
- 记录写入完成耗时（写入吞吐量）
- 等待索引刷新完成（ES 等待 1s refresh，Milvus 等待 segment flush）

**B2. 检索质量测试**

- 对 60 条查询逐条发起检索请求（调用 `/chat/stream` 或直接调用内部 RAG 检索接口）
- 记录每条查询的 Top-K 结果（chunk_id 列表）
- 与 `ground_truth.jsonl` 对比，计算各质量指标

**B3. 延迟测试**

- 对同一查询集（60 条）并发度=1（串行）执行，记录每条查询从发起到收到第一个文档的耗时（不含生成阶段）
- 计算 P50、P95

**B4. 规模延迟测试（仅需做一次，多方案共用结果）**

- 固定查询集（任意 10 条），在文档数 = 100、500、1000、5000、10000、50000 节点分别测试延迟
- Simple 方案只测到 5000（预计在此规模已超过可接受延迟阈值）

**B5. 内存与资源采集**

- 在 B2 测试期间，每 10 秒采样一次 JVM Heap Used（通过 `/actuator/metrics/jvm.memory.used` 或 JFR）
- 记录峰值内存占用

### 阶段 C：分析与对比

按查询类型（语义/关键词/混合）和文档规模（小/中/大）拆分，绘制指标矩阵。

---

## 6. 预期结果矩阵

以下为基于理论分析的预期，实际测试结果可能有出入，以数据为准。

### 6.1 检索质量（NDCG@5）预期排名

| 查询类型 | 预期最优 | 预期次优 | 预期最差 |
|---------|---------|---------|---------|
| 语义类 | Milvus-ES ≈ ES-Hybrid | Milvus ≈ ES-KNN | Simple |
| 关键词类 | ES-Hybrid ≈ Milvus-ES | ES-KNN ≈ Milvus | Simple |
| 混合类 | Milvus-ES（应用层 RRF 可调权重） | ES-Hybrid | 纯向量 < 纯 BM25 |

**假设 Milvus-ES 在混合类可能略优于 ES-Hybrid 的依据**：应用层 RRF 可以对不同来源结果施加差异化权重（`bm25RrfWeight`/`vectorRrfWeight`），而 ES-Hybrid 的 `rank.rrf` 参数在精调上灵活性更低。

### 6.2 系统性能预期

| 方案 | P50 延迟预期 | 规模敏感性 | 写入速度 |
|------|------------|----------|---------|
| Simple | 低（<50ms）但线性增长 | 高（O(N)） | 最快 |
| ES-KNN | 中（50~150ms） | 低（HNSW O(logN)） | 中 |
| ES-Hybrid | 中（80~200ms，多了 BM25） | 低 | 中 |
| Milvus | 中低（40~120ms，专用引擎） | 低 | 中快 |
| Milvus-ES | 中高（100~300ms，两次 RTT） | 低 | 最慢（双写） |

---

## 7. 指标计算方法

### 7.1 NDCG@K 计算示例

对查询 q，设相关性等级 rel(doc) ∈ {0, 1, 2}：

```
DCG@K = sum_{i=1}^{K} (2^rel(doc_i) - 1) / log2(i + 1)

IDCG@K = 对 ground truth 按相关性降序排列后的 DCG@K

NDCG@K = DCG@K / IDCG@K
```

最终取所有查询的 NDCG@K 均值。

### 7.2 MRR 计算

```
MRR = (1/|Q|) * sum_{q∈Q} 1 / rank_q

rank_q = 查询 q 的第一个相关文档（rel >= 1）在结果中的排名
若 TopK 内无相关文档，该查询 MRR 贡献为 0
```

### 7.3 Recall@K 计算

```
Recall@K = |检索结果中相关文档集 ∩ Ground Truth 相关文档集| / |Ground Truth 相关文档集|
```

---

## 8. 结果记录模板

每种方案测试完成后填写以下表格：

### 方案：____________  文档规模：____________  测试日期：____________

#### 检索质量

| 查询类型 | Recall@5 | Precision@5 | MRR | NDCG@5 | Hit Rate@5 |
|---------|---------|------------|-----|--------|-----------|
| 语义类（20 条） | | | | | |
| 关键词类（20 条） | | | | | |
| 混合类（20 条） | | | | | |
| **全集均值** | | | | | |

#### 系统性能

| 指标 | 值 |
|------|---|
| P50 检索延迟（ms） | |
| P95 检索延迟（ms） | |
| 写入吞吐量（docs/s） | |
| 峰值 JVM Heap（MB） | |

---

## 9. 已知限制与注意事项

### 9.1 Ground Truth 偏差风险

Ground Truth 由 ES-Hybrid 方案的检索结果作为候选集，可能对 ES-Hybrid 方案有系统性高估（因为 ES-Hybrid 检索到的文档天然会出现在候选集中）。

**缓解方案**：额外补充 Milvus-ES 方案的 Top-20 候选结果，取两路候选的并集作为标注候选集，保证覆盖更广的相关文档空间。

### 9.2 Milvus-ES 双写一致性

`DualWriteVectorStore` 采用 Best-effort 双写，极端情况下两端数据不一致。测试前需：
1. 确认写入日志中无 ERROR（两端均写成功）
2. 通过 ES 和 Milvus 各自的文档计数 API 确认写入数量一致

### 9.3 Simple 方案 TTL 机制缺失

Simple 方案不支持用户文件 TTL 自动清理（`MilvusUserSessionCleanupService` 仅处理 Milvus 端），测试时需手动清理 `vector_store.json` 防止跨测试数据污染。

### 9.4 Rerank 对指标的影响

Rerank 会显著改变 TopK 结果排序，可能掩盖底层检索引擎的排序差异。建议：
- **关闭 Rerank** 记录一组「原始检索」指标（`pipeline.rerankEnabled=false`）
- **开启 Rerank** 记录一组「经 Rerank 后」指标
- 对比两组，量化 Rerank 的增益幅度

### 9.5 Milvus-ES 延迟双计

Milvus-ES 的延迟天然高于其他方案（两次网络 RTT），评估时需结合质量提升幅度综合判断，不应单纯因延迟更高就否定该方案。可用「延迟-质量权衡曲线」（Quality vs. Latency scatter plot）可视化对比。

---

## 10. 验收标准

本测试方案的最终产出包括：

1. **结果表格**：覆盖 5 种方案 × 3 种文档规模（Simple 仅小规模）× 5 个指标的完整矩阵
2. **延迟曲线图**：文档规模 vs. P50/P95 延迟的折线图（格式不限，可 Excel/Python matplotlib）
3. **结论摘要**（≤500 字）：明确回答以下问题：
   - 哪种方案在语义查询下最优？差异是否显著（NDCG 差值 ≥ 0.05 为显著）？
   - 关键词查询下，混合检索（ES-Hybrid / Milvus-ES）相比纯向量检索（ES-KNN / Milvus）的 Recall@5 提升是否 ≥ 10%？
   - Simple 方案的延迟临界点（延迟超过 500ms 时对应的文档数）是多少？
   - 综合考量质量与延迟，推荐哪种方案用于哪种场景？
