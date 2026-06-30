# 结构感知文档切分扩展设计文档

## 背景

项目现有 RAG 摄入管道使用 `TokenTextSplitter` 对所有格式文档做统一的固定 token 数切分（默认 800 tokens，重叠 100 tokens）。该策略实现简单、性能稳定，但对 Markdown、PDF 等有明确内部结构的文档存在语义切断问题——标题、代码块、表格可能被随机拆散，导致检索命中率下降。

本方案在**不修改现有切分策略**的前提下，为 `.md` 和 `.pdf` 两种格式增加结构感知的额外处理路径。

---

## 设计目标

1. 现有 `TokenTextSplitter` 路径完整保留，对其余格式（DOCX、TXT 等）继续生效。
2. `.md` 文件走 Markdown 结构感知切分路径。
3. `.pdf` 文件走 PDF 结构感知切分路径（含表格整体保留）。
4. 两条新路径的输出结果（`List<Document>`）与现有路径格式完全一致，后续元数据富化和 `VectorStore.add()` 步骤无需改动。
5. 所有切分参数配置化，与现有 `RagProperties.TextSplitter` 同级扩展。

---

## 整体架构

```
文件上传 / 启动摄入
        ↓
  TikaDocumentReader（保持不变，负责多格式文本提取）
        ↓
  DocumentSplitterRouter        ← 新增：按文件扩展名路由
    ├── .md   → MarkdownStructureSplitter   ← 新增
    ├── .pdf  → PdfStructureSplitter        ← 新增
    └── 其他  → TokenTextSplitter（现有，保持不变）
        ↓
  元数据富化（保持不变）
        ↓
  VectorStore.add()（保持不变）
```

路由器实现 Spring AI 的 `DocumentTransformer` 接口，替换 `VectorStoreDataIngestionService` 中 `textSplitter.apply(documents)` 的调用点（仅此一处修改）。

---

## 新增模块说明

### 1. DocumentSplitterRouter

**职责**：根据每个 `Document` 携带的原始文件名（元数据中的 `original_filename` 或 Tika 注入的 `source` 字段）判断扩展名，将文档列表分组后分别交给对应的切分器处理，最后合并结果返回。

**路由规则**：

| 扩展名 | 切分器 |
|--------|--------|
| `.md` / `.markdown` | MarkdownStructureSplitter |
| `.pdf` | PdfStructureSplitter |
| 其他 | TokenTextSplitter（现有） |

**说明**：路由器本身不做任何切分逻辑，只做分发和结果合并。

---

### 2. MarkdownStructureSplitter

**依赖**：`flexmark-java` + `flexmark-ext-tables`

**切分逻辑**：

1. 用 flexmark-java 将 Markdown 原文解析为 AST。
2. 遍历 AST，以 `Heading` 节点作为切分边界，按配置的 `splitLevel`（默认 `H2`）决定在哪一级标题处切断。
3. 每个 chunk 的内容 = 当前标题文本 + 该标题到下一个同级/更高级标题之间的所有内容。
4. 遇到 `FencedCodeBlock` 节点：整体保留为独立 chunk，不再内部切分。
5. 遇到 `TableBlock` 节点：整体保留为独立 chunk，不再内部切分。
6. 单个 chunk 超过 `maxChunkSize` 时：退化为对该 chunk 的纯文本再执行一次 `TokenTextSplitter`，保底不丢内容。
7. 每个 chunk 元数据中附加 `heading_path`（如 `第一章 > 1.2 小节`），便于检索时展示来源位置。

**配置参数**（新增到 `RagProperties` 同级）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `splitLevel` | `2` | 在哪一级标题（H1=1, H2=2...）处切断 |
| `maxChunkSize` | 继承 `TextSplitter.maxChunkSize` | 单 chunk token 上限，超出后退化切分 |
| `keepCodeBlockIntact` | `true` | 代码块是否整体保留 |
| `keepTableIntact` | `true` | 表格是否整体保留 |
| `appendHeadingPath` | `true` | 是否在元数据中记录标题路径 |

---

### 3. PdfStructureSplitter

**依赖**：`Apache PDFBox` + `tabula-java`

**切分逻辑分两条子路径**，运行时按 PDF 是否含书签自动选择：

#### 子路径 A：书签模式（PDF 含 `/Outlines`）

1. 用 PDFBox 读取 `PDDocumentOutline`（书签树），递归遍历得到章节标题 + 页码范围。
2. 用 `PDFTextStripper` 按页码范围提取各章节文本，每章节作为初始 chunk。
3. 单章节超过 `maxChunkSize` 时，退化为 `TokenTextSplitter` 再切。
4. 检测每章节内是否含表格（见下方表格处理）。

#### 子路径 B：启发式模式（PDF 无书签）

1. 用 PDFBox 逐行提取文本，同时获取每行的字体大小。
2. 统计全文字体大小分布，将明显大于正文基准字体（默认：超出 `1.2 倍`）且单独成行的文本判定为标题。
3. 按识别出的标题进行切分，逻辑同子路径 A 的章节切分。
4. 无法识别任何标题时，整个文档退化为 `TokenTextSplitter`。

#### 表格处理（两条子路径通用）

1. 用 tabula-java 对每个页码范围执行表格检测。
2. 检测到表格的区域：将表格整体序列化为 `Markdown 表格格式` 字符串，作为独立 chunk。
3. 同页的非表格文本区域：正常参与上述文本切分流程。
4. 每个表格 chunk 元数据中附加 `content_type=table`、`page_range` 字段。

**配置参数**（新增）：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `maxChunkSize` | 继承 `TextSplitter.maxChunkSize` | 单 chunk token 上限 |
| `headingFontSizeRatio` | `1.2` | 判定为标题的字体倍数阈值（启发式模式） |
| `extractTables` | `true` | 是否启用 tabula-java 表格提取 |
| `tableOutputFormat` | `markdown` | 表格序列化格式，可选 `markdown` / `csv` |

---

## 依赖变更

在 `pom.xml` 中新增以下依赖：

| 依赖 | 用途 |
|------|------|
| `com.vladsch.flexmark:flexmark-all` | Markdown AST 解析 |
| `org.apache.pdfbox:pdfbox` | PDF 文本提取、书签读取、字体信息 |
| `technology.tabula:tabula` | PDF 表格区域检测与提取 |

注：Tika 内部已依赖部分 PDFBox，需注意版本对齐，避免冲突。

---

## 代码修改范围

| 文件 | 修改类型 | 说明 |
|------|----------|------|
| `VectorStoreDataIngestionService.java` | 微改 | 将 `textSplitter.apply(documents)` 替换为 `splitterRouter.apply(documents)`，其余不变 |
| `RagProperties.java` | 扩展 | 新增 `MarkdownSplitter`、`PdfSplitter` 两个配置内部类，与现有 `TextSplitter` 并列 |
| 新增 `DocumentSplitterRouter.java` | 新增 | 路由器，`service/` 目录下 |
| 新增 `MarkdownStructureSplitter.java` | 新增 | `rag/splitter/` 目录下 |
| 新增 `PdfStructureSplitter.java` | 新增 | `rag/splitter/` 目录下 |

**现有文件不删除、不重构**，影响面最小。

---

## 降级与兜底策略

- 任意新切分器抛出异常时，捕获后自动降级为 `TokenTextSplitter` 处理，记录 `WARN` 日志，不阻断摄入流程。
- 两个新切分器均可通过配置开关独立关闭：
  - `spring.ai.alibaba.deepresearch.rag.markdown-splitter.enabled=false` → `.md` 退回 TokenTextSplitter
  - `spring.ai.alibaba.deepresearch.rag.pdf-splitter.enabled=false` → `.pdf` 退回 TokenTextSplitter

---

## 元数据扩展

新路径产出的 chunk 在现有基础元数据之上额外附加：

| 字段 | 来源 | 说明 |
|------|------|------|
| `heading_path` | Markdown / PDF | 祖先标题路径，如 `第一章 > 1.2` |
| `content_type` | 两者 | `text` / `code` / `table` |
| `page_range` | PDF | 该 chunk 来源页码范围，如 `3-5` |
| `splitter_type` | 两者 | `markdown_structure` / `pdf_structure` / `token`，便于调试和统计 |
