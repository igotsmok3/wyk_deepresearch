# 研究与发现

## 当前 RAG 存储架构分析（第一次会话）

### 现状
- 所有 chunk 写入同一个 `ragVectorStore` Bean（`SimpleVectorStore`，纯 JVM 堆内存）
- `SimpleVectorStore` 只有启动时 load，无自动 save，**重启即全部丢失**
- 三条上传接口底层都调用 `VectorStoreDataIngestionService.vectorStore.add(chunks)`

### 三条接口的语义区分
| 接口 | 用途 | 元数据 | 当前存储 |
|------|------|--------|----------|
| `/upload` | 系统公共文档（管理员手动导入） | 无额外元数据 | SimpleVectorStore（内存） |
| `/user/batch-upload` | 用户当前会话附件 | source_type=user_upload, session_id, user_id | SimpleVectorStore（内存） |
| `/professional-kb/upload` | 领域专业知识库（持久域知识） | source_type=professional_kb_es, kb_id, kb_name, kb_description | SimpleVectorStore（内存） |

---

## 方案对比：用户会话附件存在哪里？

### 方案 A：继续存 JVM 内存（SimpleVectorStore）
**优点：**
- 响应极快，无网络 IO
- 实现简单，无额外依赖
- 天然隔离：进程退出数据消失，无需手动清理

**缺点：**
- 重启即丢失，用户体验差（上传的文件下次对话失效）
- 多实例部署时无法共享（负载均衡打到不同节点则检索不到）
- 大量文件会撑大堆内存，影响服务稳定性
- 无法跨会话复用（同一用户下次对话需重新上传）

### 方案 B：存入 Milvus 向量数据库
**优点：**
- 持久化，重启不丢失
- 多实例共享，支持水平扩展
- 可按 session_id / user_id 做元数据过滤，隔离性与内存方案相同
- 可设置 TTL（Milvus 支持 collection 级别 TTL），到期自动清理临时数据

**缺点：**
- 引入外部依赖（Milvus 服务需单独运维）
- 写入/查询有网络延迟（通常 < 10ms，可接受）
- 需要增加 TTL 清理策略，防止临时文件长期占用存储

### 结论
**用户会话附件存入 Milvus**，理由：
1. 当前 `/user/batch-upload` 已经有 session_id 元数据，Milvus 过滤查询与内存方案语义等价
2. 专业知识库也用 Milvus，统一存储后端降低架构复杂度
3. 通过 TTL 或定期清理策略控制临时数据生命周期，比"重启即丢"更可控

---

## Milvus 引入要点
- Spring AI 官方支持 `MilvusVectorStore`（`spring-ai-milvus-store` 依赖）
- 通过 `@ConditionalOnProperty` 控制开关，不影响现有 simple/ES 模式
- Collection 设计：可复用同一 collection，通过 `source_type` 字段区分专业知识库与用户文件
- 用户文件 TTL 策略：Milvus 支持按字段值设置 TTL（`expire_at` 字段），或外部定期删除 session 过期数据

---

## RAG 对比测试关键发现（第二次会话）

### ES-Hybrid 企业许可证限制
- ES 8.x Basic 许可证**不支持** `rank.rrf`（需要白金/企业许可证）
- 项目的 `RrfHybridElasticsearchRetriever` 使用了 `rank.rrf`，在 Basic 许可证下会报 `security_exception`
- **解决方案**：实现了应用层 fallback（分别执行 KNN + BM25 两路 ES 查询，应用层 RRF 融合）
- 代码位置：`RrfHybridElasticsearchRetriever.searchTwoPaths()` + `DefaultHybridRagProcessor` 中的异常捕获

### Milvus-ES 双路延迟问题
- 在 macOS Docker 环境下，Milvus-ES 的 P50 延迟（397ms）明显高于预期（RAG_plan.md 预测 100~300ms）
- 原因：Docker 网络桥接增加了 Milvus gRPC 的 RTT，加上两路并行等待最慢一路

### 两方案的实际性能对比
| 维度 | Milvus-ES | ES-Hybrid（应用层 RRF） | 胜出 |
|------|-----------|------------------------|------|
| 检索质量（NDCG@5） | 0.849 | 0.860 | ES-Hybrid（差距不显著） |
| 混合查询召回（Recall@5） | 0.945 | 0.980 | ES-Hybrid（+3.5pp） |
| 精确排名（MRR 混合类） | 0.750 | 0.724 | Milvus-ES |
| 检索延迟（P50） | 397ms | 226ms | ES-Hybrid（快 43%） |
| 运维复杂度 | 高（双写、一致性风险） | 低（单一后端） | ES-Hybrid |
| 百万级扩展性 | 更好（专用向量引擎） | 一般（ES KNN 有内存瓶颈） | Milvus-ES |

### 推荐
- 常规规模（< 100万文档）：**ES-Hybrid**（延迟低、质量相当、运维简单）
- 超大规模或需 GPU 加速：**Milvus-ES**
