# 任务计划：RAG 向量存储方案对比测试（Milvus-ES vs ES-Hybrid）

## 目标
按照 RAG_plan.md 执行 Milvus-ES 与 ES-Hybrid 两种方案的对比测试，输出完整测试报告 RAG_ES_Milvus_test.md。

---

## 阶段与任务

### 阶段 A：数据准备
- [x] A.1 选定数据集：BEIR SciFact（5183篇，有现成 Ground Truth，无需 LLM 标注）
- [x] A.2 下载数据集到本地，采样 2000 篇文档（含所有有 qrels 标注的相关文档）
- [x] A.3 选取 60 条查询（分语义/关键词/混合三类，各 20 条）
- [x] A.4 将文档转为 PDF/TXT 格式，准备上传到 RAG API
- [x] A.5 确认 Ground Truth 格式（BEIR qrels → ground_truth.jsonl）

### 阶段 B.0：基础设施准备
- [x] B.0.1 确认 ES 和 Milvus Docker 服务运行正常
- [x] B.0.2 在 RagDataController 添加检索测试端点 `/api/rag/search`（直接调用 RAG 处理器）
- [x] B.0.3 准备应用启动配置（两套 application yml）
- [ ] B.0.4 添加 Python 测试脚本（upload / search / metrics 计算）

### 阶段 B.1：Milvus-ES 方案测试
- [ ] B.1.1 切换配置至 milvus-es，启动应用
- [ ] B.1.2 建库：上传 2000 篇文档，记录写入耗时
- [ ] B.1.3 检索质量测试：60 条查询，记录 Top-5 结果
- [ ] B.1.4 计算 Recall@5、Precision@5、MRR、NDCG@5、Hit Rate@5
- [ ] B.1.5 延迟测试：P50、P95

### 阶段 B.2：ES-Hybrid 方案测试
- [ ] B.2.1 切换配置至 elasticsearch + hybrid=true，启动应用
- [ ] B.2.2 建库：上传同一批文档，记录写入耗时
- [ ] B.2.3 检索质量测试：60 条查询，记录 Top-5 结果
- [ ] B.2.4 计算同一指标集
- [ ] B.2.5 延迟测试：P50、P95

### 阶段 C：分析与报告
- [ ] C.1 汇总指标矩阵（按查询类型 × 方案）
- [ ] C.2 延迟对比
- [ ] C.3 写入 RAG_ES_Milvus_test.md 完整报告

---

## 关键决策

| 决策点 | 结论 | 依据 |
|--------|------|------|
| 数据集 | BEIR SciFact | 有现成 Ground Truth，免 LLM 标注，英文质量好 |
| 文档规模 | 2000 篇（含全部有标注相关文档） | 用户要求（非 5000） |
| 查询数量 | 60 条（20×3 类型） | RAG_plan.md 设计 |
| 检索端点 | 新增 /api/rag/search | 直接测 RAG 层，跳过 LLM 生成 |
| Ground Truth | BEIR qrels 直接使用 | 无需 LLM 标注 |
| 对比方案 | 仅 Milvus-ES 和 ES-Hybrid | 用户要求 |
