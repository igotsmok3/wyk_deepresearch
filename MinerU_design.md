# MinerU PDF 解析集成设计文档

## 1. 背景与问题

### 1.1 现有 PDF 摄入链路

```
用户上传 PDF
    → TikaDocumentReader          # 提取平铺文本，丢失格式
    → DocumentSplitterRouter      # 路由到 PdfStructureSplitter
    → PdfStructureSplitter        # PDFBox 重新读文件，提取书签或按字体启发式切分
        └── 失败时降级到 TokenTextSplitter
    → 元数据富化
    → VectorStore.add()
```

涉及文件：
- `service/VectorStoreDataIngestionService.java` — 摄入主流程
- `rag/splitter/PdfStructureSplitter.java` — PDF 结构化切分
- `service/DocumentSplitterRouter.java` — 按扩展名路由切分器
- `config/rag/RagSplitterConfiguration.java` — 切分器 Bean 注册

### 1.2 现有方案的缺陷

| 问题 | 根因 |
|------|------|
| 多栏布局文字顺序混乱 | PDFBox 文本提取无法处理复杂排版 |
| 表格变成无结构纯文本 | Tika/PDFBox 不做表格识别 |
| 公式丢失或乱码 | 无公式 OCR 能力 |
| 扫描件 PDF 完全无法摄入 | 无 OCR 支持 |
| `PdfStructureSplitter` 依赖本地文件路径 | MultipartFile 上传时无磁盘路径，结构切分直接降级到 TokenTextSplitter |

### 1.3 目标

用 MinerU 精准解析 API 替换 PDF 解析阶段，输出高质量 Markdown，复用现有 `MarkdownStructureSplitter` 完成分块。

---

## 2. MinerU 精准解析 API 概述

**Base URL：** `https://mineru.net`  
**认证：** `Authorization: Bearer {token}`

### 2.1 本地文件上传流程（三步）

```
Step 1  POST /api/v4/file-urls/batch
        body: {"files": [{"name": "xxx.pdf", "data_id": "..."}]}
        → 返回 batch_id + presigned_url[]

Step 2  PUT {presigned_url}
        body: 原始文件字节流（无需 Content-Type）
        → 文件上传完成，系统自动提交解析任务

Step 3  轮询 GET /api/v4/extract-results/batch/{batch_id}
        → state: pending | running | converting | done | failed
        → done 时返回 full_zip_url

Step 4  GET {full_zip_url}  下载 ZIP
        → 解压取 full.md（Markdown 结果）
```

### 2.2 关键限制

- 文件 ≤ 200MB，≤ 200 页
- 预签名 URL 有效期 24 小时
- 每次 batch 请求 ≤ 50 个文件
- 每账号每天 1000 页高优先级额度

### 2.3 解析参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `model_version` | `pipeline` | 解析模型，可选 `vlm`、`MinerU-HTML` |
| `enable_formula` | `true` | 公式识别 |
| `enable_table` | `true` | 表格识别 |
| `language` | `ch` | 文档主语言 |

---

## 3. 集成设计

### 3.1 整体思路

**最小侵入原则**：只在摄入管线的 Reader 阶段替换 PDF 的处理方式，Splitter、元数据富化、VectorStore 写入逻辑均不改动。

**新链路（MinerU 启用时）：**

```
用户上传 PDF
    → MinerUDocumentReader        # 调用 MinerU API，返回含 Markdown 文本的 Document
    → DocumentSplitterRouter      # 路由到 MarkdownStructureSplitter（filename 改为 .md）
    → MarkdownStructureSplitter   # 按标题层级结构切分
    → 元数据富化
    → VectorStore.add()
```

**降级策略**：MinerU API 失败时自动回退到 TikaDocumentReader + PdfStructureSplitter。

### 3.2 新增类

#### 3.2.1 `MinerUProperties`（配置属性）

路径：`config/rag/MinerUProperties.java`  
前缀：`spring.ai.alibaba.deepresearch.rag.mineru`

```
enabled              boolean   false        是否启用 MinerU 解析
api-base-url         String    https://mineru.net
api-token            String    (必填)       通过环境变量 MINERU_API_TOKEN 注入
model-version        String    pipeline     解析模型版本
enable-formula       boolean   true         公式识别
enable-table         boolean   true         表格识别
language             String    ch           文档主语言
polling-interval-ms  long      5000         轮询间隔（毫秒）
max-polling-attempts int       72           最大轮询次数（72×5s=6分钟超时）
connect-timeout-ms   int       10000        HTTP 连接超时
read-timeout-ms      int       30000        HTTP 读取超时（下载 ZIP 时用大值）
```

#### 3.2.2 `MinerUApiClient`（HTTP 客户端）

路径：`service/MinerUApiClient.java`  
职责：封装所有 MinerU API HTTP 调用，与业务逻辑解耦。

```java
// 主要方法签名
BatchUploadResponse requestUploadUrls(List<FileUploadRequest> files);
void uploadFile(String presignedUrl, byte[] content);
BatchResultResponse pollBatchResult(String batchId);
String downloadAndExtractMarkdown(String zipUrl);
```

内部数据模型（静态内部类或单独 record）：

| 类名 | 字段 |
|------|------|
| `FileUploadRequest` | `name: String`, `dataId: String` |
| `BatchUploadResponse` | `batchId: String`, `fileUrls: List<String>` |
| `BatchResultResponse` | `state: String`, `fullZipUrl: String`, `errMsg: String` |

HTTP 客户端使用 Spring 的 `RestClient`（Spring Boot 3.2+）。

#### 3.2.3 `MinerUDocumentReader`（文档读取器）

路径：`rag/reader/MinerUDocumentReader.java`  
职责：接收原始文件，编排三步 API 调用 + 轮询，返回携带 Markdown 文本的 `Document` 列表。

```java
// 实现 DocumentReader 接口（可选，也可直接返回 List<Document>）
public List<Document> read(String filename, byte[] content, Map<String, Object> baseMetadata);
```

关键行为：
- 调用 `MinerUApiClient.requestUploadUrls` → `uploadFile` → 轮询 `pollBatchResult`
- 超时或失败时抛出 `MinerUParseException`
- 解压 ZIP，读取 `full.md` 内容
- 构造 `Document`，metadata 中 `original_filename` 值设为 `<原文件名>.md`（让 `DocumentSplitterRouter` 路由到 `MarkdownStructureSplitter`）

#### 3.2.4 `MinerUConfiguration`（Bean 注册）

路径：`config/rag/MinerUConfiguration.java`  
条件：`@ConditionalOnProperty(prefix="...", name="mineru.enabled", havingValue="true")`

注册 `MinerUProperties`、`MinerUApiClient`、`MinerUDocumentReader` 三个 Bean。

### 3.3 改动现有类

#### `VectorStoreDataIngestionService`

在所有涉及 PDF 的读取处，替换 Reader 选择逻辑：

```java
// 伪代码
private List<Document> readDocuments(Resource resource, String filename) {
    if (minerUEnabled && isPdf(filename)) {
        try {
            return minerUDocumentReader.read(filename, resource.getContentAsByteArray(), ...);
        } catch (MinerUParseException e) {
            logger.warn("MinerU failed, falling back to Tika: {}", e.getMessage());
        }
    }
    return new TikaDocumentReader(resource).get();
}
```

三个受影响的方法均复用此私有方法：
- `ingest(Resource resource)`
- `batchProcessAndStore(List<MultipartFile>, ...)`
- `batchProcessAndStoreResources(List<Resource>, ...)`

`MinerUDocumentReader` 通过 `ObjectProvider` 注入，RAG 未启用时为 null。

#### `RagProperties`

新增 `MinerU` 静态内部类，并在 `RagProperties` 中添加 `minerU` 字段及 getter/setter。

---

## 4. 配置示例

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          enabled: true
          mineru:
            enabled: true
            api-token: ${MINERU_API_TOKEN}   # 通过环境变量注入，不硬编码
            model-version: pipeline
            enable-formula: true
            enable-table: true
            language: ch
            polling-interval-ms: 5000
            max-polling-attempts: 72
```

---

## 5. 错误处理策略

| 场景 | 处理方式 |
|------|----------|
| 网络超时 / API 4xx | 抛出 `MinerUParseException`，上层降级到 Tika |
| 轮询超时（state 长期非 done） | 抛出 `MinerUParseException` 并记录 `batchId` 用于排查 |
| state=failed | 抛出 `MinerUParseException`，附带 `errMsg` |
| ZIP 下载失败 | 抛出 `MinerUParseException` |
| `full.md` 为空 | 降级到 Tika |
| MinerU 未启用 | 完全不调用 API，直接走原有链路 |

---

## 6. 不改动的部分

- `DocumentSplitterRouter` — 无需改动，`.md` 扩展名路由逻辑已存在
- `MarkdownStructureSplitter` — 直接复用
- `RagDataController` — 无需改动
- 所有元数据富化逻辑 — 无需改动
- 非 PDF 文件的处理逻辑 — 完全不受影响

---

## 7. 新增文件清单

```
src/main/java/.../
├── config/rag/
│   ├── MinerUProperties.java          # 新增
│   └── MinerUConfiguration.java       # 新增
├── rag/
│   └── reader/
│       └── MinerUDocumentReader.java  # 新增
└── service/
    └── MinerUApiClient.java           # 新增
```

改动文件：
- `config/rag/RagProperties.java` — 新增 `MinerU` 内部类和字段
- `service/VectorStoreDataIngestionService.java` — 注入 MinerU reader，替换 PDF 读取逻辑
- `src/main/resources/application.yml` — 新增 `mineru.*` 配置项（默认 disabled）
