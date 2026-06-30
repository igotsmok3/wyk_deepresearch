# 会话日志

## 2026-06-29（第一次会话 - Milvus 升级）

### 本次会话完成
- 分析了当前 RAG 存储架构（SimpleVectorStore，纯内存，无持久化）
- 梳理了三条上传接口的语义差异
- 对比了用户会话附件存 JVM vs Milvus 两种方案，结论：存 Milvus
- 输出设计文档：`task_plan.md`（阶段任务）+ `findings.md`（分析与结论）

---

## 2026-06-29（第二次会话 - RAG 对比测试）

### 任务
执行 Milvus-ES vs ES-Hybrid 对比测试，输出 RAG_ES_Milvus_test.md

### 完成情况
- [x] 读取 RAG_plan.md，确认测试范围
- [x] 确认基础设施（ES 8.13.0 + Milvus v2.4.0 运行中）
- [x] 下载 BEIR SciFact 数据集（5183 篇），采样 2000 篇，选 60 条查询
- [x] 在 RagDataController 添加 `/api/rag/search` 检索测试端点
- [x] 创建两套 Spring Profile 配置文件
- [x] 构建 JAR
- [x] Milvus-ES 测试：上传 2020 chunks（306.6s），60 条查询检索测试
- [x] 发现 ES 内置 RRF 需要企业许可证→实现应用层 fallback
- [x] ES-Hybrid 测试：上传 2020 chunks（313.6s），60 条查询检索测试
- [x] 汇总分析数据
- [x] 生成测试报告 RAG_ES_Milvus_test.md

### 关键数据
| 指标 | Milvus-ES | ES-Hybrid |
|------|-----------|-----------|
| NDCG@5 全集 | 0.849 | 0.860 |
| Recall@5 全集 | 0.943 | 0.954 |
| P50 延迟 | 397ms | 226ms |
| 写入速度 | 6.5 docs/s | 6.4 docs/s |
