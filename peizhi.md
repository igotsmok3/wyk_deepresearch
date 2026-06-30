# DeepResearch 可选配置说明

本文档列出项目中所有可选功能模块及其配置方式。所有配置均在 `src/main/resources/application.yml` 中修改，除非特别注明。

---

## 1. Redis（报告持久化存储）

**默认：关闭**

Redis 启用后，研究报告将存储到 Redis 而非内存，重启后数据不丢失。

```yaml
spring:
  data:
    redis:
      enabled: true          # 改为 true 启用
      host: localhost
      port: 6379
      password: ${REDIS-PASSWORD}   # 设置环境变量 REDIS-PASSWORD
      timeout: 3000
```

- 需要先启动 Redis 服务
- 设置环境变量 `REDIS-PASSWORD`（无密码时留空即可）

---

## 2. 短期记忆（Short-Term Memory）

**默认：开启**

为多轮对话保留上下文，包含两个子功能：对话历史窗口 和 用户角色画像提取。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        short-term-memory:
          enabled: true                  # false 则完全关闭
          memory-type: in-memory         # 目前只支持 in-memory
          conversation-memory:
            max-messages: 100            # 滑动窗口保留的最大消息条数
          user-role-memory:
            guide-scope: every           # NONE / ONCE / EVERY
            update-similarity-threshold: 0.8
            history-user-messages-num: 10
```

`guide-scope` 说明：
- `NONE`：只提取用户画像，不注入到 LLM 提示词
- `ONCE`：仅第一轮注入
- `EVERY`：每轮都注入（默认，效果最好但多消耗 token）

---

## 3. Reflection（结果质量自我反思）

**默认：开启**

ResearcherNode / CoderNode 完成后，让模型自我评估结果质量，不达标则重试。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        reflection:
          enabled: true      # false 则跳过评估，直接标记完成
          max-attempts: 1    # 最多重试次数，超出后强制通过
```

---

## 4. RAG（检索增强生成）

**默认：关闭**

启用后，研究节点可检索本地知识库文档。支持两种向量存储：内存简单模式 和 Elasticsearch。

### 4.1 基本启用（内存模式）

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          enabled: true
          vector-store-type: simple      # 内存存储，重启丢失
          data:
            locations:
              - "classpath:/data/"       # 启动时自动加载该目录下所有文件
```

将知识文档放入 `src/main/resources/data/` 目录即可。

### 4.2 Milvus 模式（推荐用于生产）

持久化向量存储，支持专业知识库和用户会话附件的统一管理。重启不丢失，支持水平扩展。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          enabled: true
          vector-store-type: milvus
          user-file-ttl-days: 7        # 用户会话文件的保留天数，默认7天
          milvus:
            host: localhost
            port: 19530
            database-name: default
            collection-name: deepresearch_vectors  # 专业知识库与用户文件共用同一 collection
            embedding-dimension: 1536              # 需与 Embedding 模型输出维度一致
            username: root             # 可选，无认证时不填
            password: milvus           # 可选
```

**过期清理：**
- 用户会话文件写入时自动设置 `expire_at`（Unix 秒 = 当前时间 + TTL 天数）。
- 内置定时任务每小时自动清理 `expire_at < now` 的用户文件，可通过以下配置覆盖 cron 表达式：

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          milvus:
            cleanup-cron: "0 0 2 * * *"   # 改为每天凌晨2点执行
```

**前提：** 需要本地或远端 Milvus 2.3+ 实例。本地快速启动：

```bash
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:v2.4.0 standalone
```

### 4.3 Milvus+ES 双路模式

**适用场景：** 对比测试不同混合检索方案；或超大规模向量检索（Milvus 原生 HNSW）与 BM25 关键词检索结合的生产场景。

与单 ES 混合检索（`elasticsearch` + `hybrid=true`，ES 引擎内 RRF）不同，本模式将两路检索拆到 Milvus 与 ES 两个独立引擎，在应用层用 `RrfFusionStrategy` 做 RRF 融合，融合权重可自定义。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          enabled: true
          vector-store-type: milvus-es
          user-file-ttl-days: 7
          milvus:
            host: localhost
            port: 19530
            database-name: default
            collection-name: deepresearch_vectors
            embedding-dimension: 1536
            username: root            # 可选
            password: milvus          # 可选
            dual:
              bm25-top-k: 10          # ES BM25 单路返回文档数
              vector-top-k: 10        # Milvus 向量单路返回文档数
              retrieval-timeout-ms: 3000  # 单路超时（毫秒），超时降级为空列表
              bm25-rrf-weight: 1.0    # BM25 列表 RRF 权重（扩展用）
              vector-rrf-weight: 1.0  # 向量列表 RRF 权重（扩展用）
          elasticsearch:
            uris: http://localhost:9200
            index-name: spring-ai-rag-es-index
            dimensions: 1536
            similarity-function: cosine
            hybrid:
              enabled: false          # 双路模式不使用 ES KNN，此项必须为 false
```

**工作原理：**
- 写入：`ragVectorStore` 为 `DualWriteVectorStore`，对 Milvus 和 ES 两端并发双写（best-effort，任一失败仅记日志不中断）。
- 检索：`MilvusEsDualPathRetriever` 并行执行 Milvus 向量检索 + ES BM25 检索，两路结果交给 `RrfFusionStrategy` 做应用层 RRF 融合，融合后不再二次 rerank。
- RRF 排名常数复用 `rag.fusion.rrf.k-constant`（默认 60）。

**注意事项：**
- `elasticsearch.hybrid.enabled` 必须为 `false`（双路模式不让 ES 做向量检索）。
- 写入前确保 Milvus 与 ES 两端服务均可用，否则会出现两端数据不一致（监控双写日志可发现）。
- ES 端会冗余存储向量字段（`ElasticsearchVectorStore` 默认行为），实际检索不使用，属可接受的存储开销。
- TTL 过期清理：`DualWriteVectorStore.delete` 会同时清理 Milvus 与 ES 两端，无需额外配置。

**两端服务 Docker 快速启动：**

```bash
# Milvus
docker run -d --name milvus-standalone \
  -p 19530:19530 -p 9091:9091 \
  milvusdb/milvus:v2.4.0 standalone

# Elasticsearch 8.x（单节点、关闭安全认证，仅本地测试用）
docker run -d --name es-standalone \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.13.0
```

### 4.4 Elasticsearch 模式

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          enabled: true
          vector-store-type: elasticsearch
          elasticsearch:
            uris: http://localhost:9200
            index-name: spring-ai-rag-es-index
            dimensions: 1536
            similarity-function: cosine
            hybrid:
              enabled: false             # 是否启用 BM25+KNN 混合搜索
```

### 4.5 RAG Pipeline 增强选项

```yaml
          pipeline:
            query-expansion-enabled: false          # 查询扩展
            query-translation-enabled: false        # 查询翻译（译成英文再检索）
            hypothetical-document-embedding-enabled: false  # HyDE 假设文档嵌入
            top-k: 5                                # 检索 Top-K 结果数
            similarity-threshold: 0.7
            rerank-enabled: true                    # 重排序
            rerank-top-k: 10
            rerank-threshold: 0.5
```

### 4.6 定时扫描文件夹

```yaml
          data:
            scan:
              enabled: true
              directory: /path/to/watch/folder
              cron: "0 0 * * * *"        # 默认每小时扫描一次
              archive-directory: /path/to/archive
```

---

## 5. 专业知识库（Professional Knowledge Bases）

配置在 `src/main/resources/application-kb.yml`（项目默认已 include 该 profile）。

启用 RAG 后，可额外挂载外部专业知识库（如医学、法律等），由 LLM 自动判断是否需要查询。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          professional-knowledge-bases:
            decision-enabled: true       # LLM 自动决策是否查询知识库
            knowledge-bases:
              - id: "medical_kb"
                name: "医学专业知识库"
                description: "包含医学相关知识，LLM 根据此描述判断是否调用"
                type: "api"              # api 或 elasticsearch
                enabled: true
                priority: 10            # 数字越小优先级越高
                api:
                  provider: "dashscope" # dashscope 或 custom
                  url: "https://dashscope.aliyuncs.com/api/v1/services/knowledge-base/text-search"
                  api-key: "${DASHSCOPE_API_KEY}"
                  model: "text-embedding-v2"
                  timeout-ms: 30000
                  max-results: 5
```

---

## 6. MCP（Model Context Protocol 工具扩展）

**默认：关闭**

让 Agent 通过 MCP 协议调用外部工具服务（如高德地图等）。

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true    # Spring AI MCP 客户端总开关
        type: ASYNC
    alibaba:
      deepresearch:
        mcp:
          enabled: true                          # DeepResearch MCP 节点开关
          config-location: classpath:mcp-config.json  # MCP 服务器配置文件路径
```

编辑 `src/main/resources/mcp-config.json` 配置具体 MCP 服务器：

```json
{
  "researchAgent": {
    "mcp-servers": [
      {
        "url": "https://mcp.amap.com?key=${AMAP_API_KEY}",
        "sse-endpoint": "/sse",
        "description": "高德地图服务",
        "enabled": true
      }
    ]
  }
}
```

---

## 7. Smart Agents（智能搜索平台路由）

**默认：关闭**

根据问题类型自动选择最合适的搜索平台（学术用 OpenAlex、旅游用 OpenTripMap 等）。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        smart-agents:
          enabled: true
          search-platform-mapping:
            academic_research:
              primary: openalex
            lifestyle_travel:
              primary: opentripmap
            encyclopedia:
              primary: wikipedia
            data_analysis:
              primary: worldbankdata
            general_research:
              primary: tavily
```

同时需要启用对应的工具调用：

```yaml
spring:
  ai:
    alibaba:
      toolcalling:
        openalex:
          enabled: true
        opentripmap:
          enabled: true
          api-key: ${OPENTRIPMAP_API_KEY}
        wikipedia:
          enabled: true
        worldbankdata:
          enabled: true
```

---

## 8. 搜索引擎（Search Engines）

**默认启用：Tavily、阿里云 AI 搜索、百度、SerpApi**

可按需开关各搜索引擎：

```yaml
spring:
  ai:
    alibaba:
      toolcalling:
        tavilysearch:
          enabled: true
          api-key: ${TAVILY_API_KEY}
        aliyunaisearch:
          enabled: true
          api-key: ${ALIYUN_AI_SEARCH_API_KEY}
          base-url: ${ALIYUN_AI_SEARCH_BASE_URL}
        baidu:
          search:
            enabled: true
        serpapi:
          enabled: true
          api-key: ${SERPAPI_KEY}
        jinacrawler:
          enabled: false             # Jina 网页抓取，默认关闭
          api-key: ${JINA_API_KEY}
      deepresearch:
        search-list:                 # 实际参与调度的搜索引擎列表
          - tavily
          - aliyun
          - baidu
          - serpapi
```

---

## 9. 可观测性（Observability / LangFuse）

配置在 `src/main/resources/application-observability.yml`（项目默认已 include 该 profile）。

通过 OpenTelemetry 将 trace 数据发送到 LangFuse，用于调试和监控 LLM 调用链路。

```yaml
otel:
  exporter:
    otlp:
      endpoint: "https://us.cloud.langfuse.com/api/public/otel"
      headers:
        Authorization: "Basic ${YOUR_BASE64_ENCODED_CREDENTIALS}"
```

将 LangFuse 的 `public_key:secret_key` Base64 编码后设置为环境变量 `YOUR_BASE64_ENCODED_CREDENTIALS`。

---

## 10. 并行节点数量

控制同时运行的 Researcher 和 Coder 节点数，影响并行研究能力和资源消耗。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        parallel-node-count:
          researcher: 4    # 并行研究节点数
          coder: 4         # 并行代码节点数
        max-iterations: 50 # 图执行最大迭代次数
```

---

## 11. 报告导出

指定研究报告导出到本地文件系统的路径。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        export:
          path: ${AI_DEEPRESEARCH_EXPORT_PATH}
```

设置环境变量 `AI_DEEPRESEARCH_EXPORT_PATH` 为目标目录路径。

---

## 配置速查表

| 功能 | 配置项 | 默认值 | 所需环境变量 |
|------|--------|--------|-------------|
| Redis 持久化 | `spring.data.redis.enabled` | `false` | `REDIS-PASSWORD` |
| 短期记忆 | `...short-term-memory.enabled` | `true` | — |
| Reflection 反思 | `...reflection.enabled` | `true` | — |
| RAG 知识库 | `...rag.enabled` | `false` | — |
| MCP 工具扩展 | `...mcp.enabled` + `spring.ai.mcp.client.enabled` | `false` | 各 MCP 服务对应 key |
| Smart Agents | `...smart-agents.enabled` | `false` | 各搜索平台 API key |
| Tavily 搜索 | `...tavilysearch.enabled` | `true` | `TAVILY_API_KEY` |
| Jina 爬虫 | `...jinacrawler.enabled` | `false` | `JINA_API_KEY` |
| LangFuse 可观测 | `otel.exporter.otlp.*` | 已配置但需填 key | `YOUR_BASE64_ENCODED_CREDENTIALS` |
| 报告导出 | `...export.path` | — | `AI_DEEPRESEARCH_EXPORT_PATH` |
| Markdown 结构切分 | `...rag.markdown-splitter.enabled` | `true` | — |
| PDF 结构切分 | `...rag.pdf-splitter.enabled` | `true` | — |

---

## 结构感知文档切分

**默认：启用（需 RAG 已开启）**

RAG 摄入文档时，对 `.md` / `.markdown` 和 `.pdf` 文件使用结构感知切分策略，而非简单按 Token 数量均匀切分。其他格式文件仍走原有 `TokenTextSplitter` 路径，行为不变。

### Markdown 切分路径

按标题层级（`split-level`）划定 chunk 边界：层级 ≤ `split-level` 的标题触发新 chunk；更深的标题不切断，只更新元数据中的 `heading_path`。代码块（fenced code block）和 GFM 表格各自独立成一个 chunk，不被切断。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          markdown-splitter:
            enabled: true
            split-level: 2            # h1/h2 处切断，h3+ 不切
            keep-code-block-intact: true
            keep-table-intact: true
            append-heading-path: true # 在元数据 heading_path 中记录标题路径
```

**适用场景**：规范的技术文档、README、Wiki；需要按章节检索的内容。

### PDF 切分路径

优先读取 PDF 书签目录（outline）按章节切分；无书签时启发式检测字体大小较大的行作为标题边界。

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          pdf-splitter:
            enabled: true
            heading-font-size-ratio: 1.2  # 字体大小 > 正文中位数 × 1.2 视为标题
            extract-tables: true          # 预留，当前版本不生效
            table-output-format: markdown
```

**适用场景**：由 Word / LaTeX / 专业工具生成的有书签 PDF；或字体排版规范的 PDF 文档。

**不适用**：扫描版 PDF（图片型）、无明显字体大小差异的 PDF，这类文件会自动降级到 `TokenTextSplitter`。

### 关闭结构感知切分

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          markdown-splitter:
            enabled: false   # 所有 .md 文件退回 TokenTextSplitter
          pdf-splitter:
            enabled: false   # 所有 .pdf 文件退回 TokenTextSplitter
```
