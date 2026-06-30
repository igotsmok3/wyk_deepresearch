# 任务计划：结构感知文档切分扩展（Markdown + PDF）

## 目标

1. 为 `.md` / `.markdown` 文件新增基于标题层级的结构感知切分路径，代码块、表格整体保留不拆断。
2. 为 `.pdf` 文件新增结构感知切分路径，优先读取书签目录，无书签时启发式识别标题，tabula-java 处理表格。
3. 现有 `TokenTextSplitter` 路径对其他所有格式完整保留，零行为变更。
4. 所有参数配置化，两条新路径均可独立关闭并自动降级回 `TokenTextSplitter`。

详细设计见 [MDPDF设计文档.md](./MDPDF设计文档.md)。

---

## 阶段与任务

### 阶段 1：依赖引入与版本对齐
**目标**：在 `pom.xml` 中引入三个新依赖，确认与 Tika 已有的 PDFBox 传递依赖版本不冲突。

- [ ] 1.1 在 `pom.xml` 中新增 `flexmark-all` 依赖
  - 坐标：`com.vladsch.flexmark:flexmark-all`
  - 确认版本（当前稳定版 0.64.x），添加到 `<dependencies>` 块
- [ ] 1.2 在 `pom.xml` 中新增 `pdfbox` 依赖
  - 坐标：`org.apache.pdfbox:pdfbox`
  - 用 `mvn dependency:tree` 检查 Tika 已传递引入的 PDFBox 版本，显式声明同一版本避免冲突
- [ ] 1.3 在 `pom.xml` 中新增 `tabula-java` 依赖
  - 坐标：`technology.tabula:tabula`
  - 当前稳定版 1.0.5，tabula 依赖 PDFBox 2.x，确认与 1.2 中版本一致
- [ ] 1.4 执行 `mvn clean install -DskipTests` 验证依赖解析无冲突，能正常编译

---

### 阶段 2：配置层扩展
**目标**：在 `RagProperties` 中新增两个配置内部类，与现有 `TextSplitter` 并列，不修改任何已有字段。

- [ ] 2.1 在 `RagProperties.java` 中新增 `MarkdownSplitter` 静态内部类
  - 字段：`enabled`（默认 `true`）、`splitLevel`（默认 `2`）、`maxChunkSize`（默认 `-1`，-1 表示继承 `TextSplitter.maxChunkSize`）、`keepCodeBlockIntact`（默认 `true`）、`keepTableIntact`（默认 `true`）、`appendHeadingPath`（默认 `true`）
  - 补充全部 getter / setter
- [ ] 2.2 在 `RagProperties.java` 中新增 `PdfSplitter` 静态内部类
  - 字段：`enabled`（默认 `true`）、`maxChunkSize`（默认 `-1`）、`headingFontSizeRatio`（默认 `1.2f`）、`extractTables`（默认 `true`）、`tableOutputFormat`（默认 `"markdown"`）
  - 补充全部 getter / setter
- [ ] 2.3 在 `RagProperties` 主类中新增两个字段及对应 getter
  ```java
  private final MarkdownSplitter markdownSplitter = new MarkdownSplitter();
  private final PdfSplitter pdfSplitter = new PdfSplitter();
  ```

---

### 阶段 3：MarkdownStructureSplitter 实现
**目标**：基于 flexmark-java AST 实现按标题层级切分，代码块和表格整体保留。

- [ ] 3.1 新建 `MarkdownStructureSplitter.java`（包路径：`rag/splitter/`）
  - 构造参数：`RagProperties ragProperties`、`TokenTextSplitter fallbackSplitter`
  - 实现 `DocumentTransformer` 接口（`List<Document> apply(List<Document>)`）
  - 内部初始化 flexmark `Parser`，启用 `TablesExtension`
- [ ] 3.2 实现核心 AST 遍历方法 `splitByHeadings(String markdownText)`
  - 遍历顶层节点，维护当前 `headingStack`（层级→标题文本的有序栈）
  - 遇到 `Heading` 节点：
    - 层级 ≤ `splitLevel` 时，将已积累内容作为一个 chunk 输出，更新 `headingStack`
    - 层级 > `splitLevel` 时，仅更新 `headingStack`，不切断
  - 遇到 `FencedCodeBlock`（`keepCodeBlockIntact=true`）：将已积累内容先 flush 一个 chunk，再将代码块整体作为独立 chunk 输出（`content_type=code`）
  - 遇到 `TableBlock`（`keepTableIntact=true`）：同上，独立 chunk 输出（`content_type=table`）
  - 其余节点：累积到当前 chunk buffer
- [ ] 3.3 实现 `buildHeadingPath(Deque<String> stack)` 工具方法
  - 将标题栈拼接为 `第一章 > 1.2 小节` 格式字符串
- [ ] 3.4 实现超限退化逻辑
  - 每个 chunk 文本生成后，估算 token 数（字符数 / 3 近似，或用 `EncodingRegistry` 精确计算）
  - 超过 `maxChunkSize` 时，调用 `fallbackSplitter.apply()` 对该 chunk 二次切分
- [ ] 3.5 实现元数据写入
  - 每个输出 chunk 的元数据附加：`heading_path`、`content_type`（`text` / `code` / `table`）、`splitter_type=markdown_structure`
- [ ] 3.6 实现异常降级
  - 整个 `apply()` 方法用 try-catch 包裹，捕获异常时记录 `WARN` 日志，返回 `fallbackSplitter.apply(documents)`

---

### 阶段 4：PdfStructureSplitter 实现
**目标**：实现书签模式和启发式模式两条子路径，tabula-java 处理表格，超限退化。

- [ ] 4.1 新建 `PdfStructureSplitter.java`（包路径：`rag/splitter/`）
  - 构造参数：`RagProperties ragProperties`、`TokenTextSplitter fallbackSplitter`
  - 实现 `DocumentTransformer` 接口
  - 整个 `apply()` 外层用 try-catch 包裹，异常时降级到 `fallbackSplitter`

- [ ] 4.2 实现子路径选择入口 `splitPdf(Resource originalResource)`
  - 用 PDFBox `PDDocument.load()` 加载文件
  - 检查 `doc.getDocumentCatalog().getDocumentOutline() != null` 决定走书签模式还是启发式模式
  - 注意：`apply()` 接收的是 Tika 已提取的 `Document` 列表，原始文件需通过元数据中的 `source` 字段重新加载；若无法获取原始文件，整体降级

- [ ] 4.3 实现书签模式 `splitByOutline(PDDocument doc)`
  - 递归遍历 `PDDocumentOutline`，收集 `List<ChapterSegment>`（标题、起始页、结束页）
  - 用 `PDFTextStripper` 对每个 `ChapterSegment` 的页码范围提取文本
  - 每段文本作为初始 chunk，超限时调用 `fallbackSplitter` 二次切分
  - 传入 `extractTablesFromPages()` 进行表格检测（见 4.5）

- [ ] 4.4 实现启发式模式 `splitByFontSize(PDDocument doc)`
  - 自定义 `PDFTextStripper` 子类，重写 `writeString()` 捕获每行字体大小
  - 统计全文字体大小中位数作为基准正文字体
  - 判定标题条件：字体大小 > 基准 × `headingFontSizeRatio`，且该行字符数 < 80（避免把大字号段落正文误判）
  - 识别出标题列表后，按标题分段，逻辑同 4.3
  - 无法识别任何标题时（标题列表为空），整个文档降级到 `fallbackSplitter`

- [ ] 4.5 实现表格提取 `extractTablesFromPages(PDDocument doc, int startPage, int endPage)`
  - `extractTables=false` 时直接返回空列表跳过
  - 用 tabula-java `ObjectExtractor` 对指定页码范围检测表格区域
  - 每张表格用 `BasicExtractionAlgorithm` 或 `SpreadsheetExtractionAlgorithm` 提取 `List<List<RectangularTextContainer>>`
  - 按 `tableOutputFormat` 序列化：`markdown` 模式输出 `| col1 | col2 |` 格式，`csv` 模式输出逗号分隔
  - 每个表格返回为独立 `Document`，元数据含 `content_type=table`、`page_range`、`splitter_type=pdf_structure`

- [ ] 4.6 实现元数据写入
  - 文本 chunk：`heading_path`、`content_type=text`、`page_range`、`splitter_type=pdf_structure`
  - 表格 chunk：`content_type=table`、`page_range`、`splitter_type=pdf_structure`

---

### 阶段 5：DocumentSplitterRouter 实现
**目标**：按文件扩展名路由，合并结果，本身不包含任何切分逻辑。

- [ ] 5.1 新建 `DocumentSplitterRouter.java`（包路径：`service/`）
  - 构造参数：`MarkdownStructureSplitter markdownSplitter`、`PdfStructureSplitter pdfSplitter`、`TokenTextSplitter tokenSplitter`、`RagProperties ragProperties`
  - 实现 `DocumentTransformer` 接口
- [ ] 5.2 实现扩展名提取工具方法 `resolveExtension(Document doc)`
  - 优先读 `original_filename` 元数据，其次读 Tika 注入的 `source` 字段
  - 提取小写扩展名（`.md`、`.markdown`、`.pdf`）
- [ ] 5.3 实现 `apply(List<Document> documents)` 路由逻辑
  - 将 documents 按扩展名分组为三组：md 组、pdf 组、其他组
  - md 组：`ragProperties.getMarkdownSplitter().isEnabled()` 为 true 时走 `markdownSplitter`，否则走 `tokenSplitter`
  - pdf 组：`ragProperties.getPdfSplitter().isEnabled()` 为 true 时走 `pdfSplitter`，否则走 `tokenSplitter`
  - 其他组：走 `tokenSplitter`
  - 合并三组结果，**保持原始文档顺序**（md 组在前的文件结果排在前）后返回

---

### 阶段 6：VectorStoreDataIngestionService 接入
**目标**：用 `DocumentSplitterRouter` 替换 `TokenTextSplitter` 的直接调用，仅改一个引用点。

- [ ] 6.1 在 `VectorStoreDataIngestionService` 构造函数中新增 `DocumentSplitterRouter splitterRouter` 参数
  - 将原有 `this.textSplitter = new TokenTextSplitter(...)` 的构造逻辑移至 `DocumentSplitterRouter` 内部（或通过 Spring Bean 注入）
  - 保留 `textSplitter` 字段，供 Router 内部和降级路径使用
- [ ] 6.2 将服务中所有 `textSplitter.apply(documents)` 调用替换为 `splitterRouter.apply(documents)`
  - 涉及方法：`ingest(Resource)`、`batchProcessAndStore()`、`batchProcessAndStoreResources()`、`batchUploadToProfessionalKbEs()`、`batchUploadResourcesToProfessionalKbEs()`
  - 共 5 处调用点，逐一替换，无其他逻辑改动
- [ ] 6.3 在 Spring 配置中将 `MarkdownStructureSplitter`、`PdfStructureSplitter`、`DocumentSplitterRouter` 注册为 Bean
  - 新建 `RagSplitterConfiguration.java`（包路径：`config/rag/`），加 `@ConditionalOnProperty(... rag.enabled = true)` 条件
  - `TokenTextSplitter` Bean 在此处定义（从 `VectorStoreDataIngestionService` 构造函数中的 `new` 调用抽出，改为注入）

---

### 阶段 7：配置示例与文档
**目标**：补充配置说明，确保可开箱测试。

- [ ] 7.1 在 `application.yml` 新增结构感知切分配置注释示例块（紧接现有 `text-splitter` 块之后）
  ```yaml
  # markdown-splitter 和 pdf-splitter 默认启用，可按需关闭
  markdown-splitter:
    enabled: true
    split-level: 2
    keep-code-block-intact: true
    keep-table-intact: true
    append-heading-path: true
  pdf-splitter:
    enabled: true
    heading-font-size-ratio: 1.2
    extract-tables: true
    table-output-format: markdown
  ```
- [ ] 7.2 在 `peizhi.md` 新增"结构感知切分"章节
  - 说明 Markdown 和 PDF 两条路径的适用场景
  - 完整配置 YAML 示例
  - 注意事项：PDF 书签模式需要 PDF 由 Word/LaTeX 等工具规范生成；扫描版 PDF 不支持
- [ ] 7.3 在 `CLAUDE.md` Feature Toggles 部分补充两个开关说明
  - `spring.ai.alibaba.deepresearch.rag.markdown-splitter.enabled`
  - `spring.ai.alibaba.deepresearch.rag.pdf-splitter.enabled`

---

## 关键决策记录

| 决策点 | 结论 | 依据 |
|--------|------|------|
| 接入点位置 | 替换 `textSplitter.apply()` 调用，不改摄入流程其他部分 | 修改面最小，元数据富化和存储逻辑完全不动 |
| Router 分组依据 | 读元数据 `original_filename`，回退读 Tika `source` 字段 | Tika 提取后 Document 不直接携带原始 Resource，需从元数据还原扩展名 |
| PDF 原始文件获取 | 通过 `source` 元数据字段重新加载 PDDocument，无法获取时整体降级 | PDFBox 需要原始二进制，Tika 输出的纯文本无字体信息 |
| token 数估算 | 字符数 / 3 近似（中文约 1.5 字符/token，英文约 4 字符/token），不引入 tiktoken | 避免额外依赖，近似值足够控制超限降级阈值 |
| 表格序列化格式 | 默认输出 Markdown 表格 | 与向量检索上下文语义更自然，LLM 处理 Markdown 表格更准确 |
| 降级粒度 | 以单个 Document 为粒度降级，不影响同批次其他文件 | 一个文件解析失败不阻断整批摄入 |
| Bean 注册条件 | `@ConditionalOnProperty(rag.enabled=true)` | 与现有 RAG 子系统生命周期绑定，RAG 未启用时不加载任何切分器 Bean |
| 现有模式改动 | `TokenTextSplitter` 逻辑和字段保留，Router 持有其引用作为降级工具 | 保证其他格式文件行为完全不变 |

---

## 依赖与前提

- `spring.ai.alibaba.deepresearch.rag.enabled=true`（RAG 功能必须开启）
- PDFBox 要求 Java 8+（已满足，项目为 Java 17）
- tabula-java 1.0.5 依赖 PDFBox 2.x，需确认与 Tika 传递的 PDFBox 版本一致（阶段 1 验证）
- 扫描版 PDF（图片型）不在支持范围，此类文件直接降级到 `TokenTextSplitter`

---

## 文件变更清单

| 文件 | 变更类型 | 阶段 |
|------|---------|------|
| `pom.xml` | 修改：新增 3 个依赖 | 1 |
| `config/rag/RagProperties.java` | 修改：新增 `MarkdownSplitter`、`PdfSplitter` 内部类及字段 | 2 |
| `rag/splitter/MarkdownStructureSplitter.java` | **新建** | 3 |
| `rag/splitter/PdfStructureSplitter.java` | **新建** | 4 |
| `service/DocumentSplitterRouter.java` | **新建** | 5 |
| `config/rag/RagSplitterConfiguration.java` | **新建** | 6 |
| `service/VectorStoreDataIngestionService.java` | 修改：构造参数 + 5 处调用替换 | 6 |
| `src/main/resources/application.yml` | 修改：新增配置注释示例 | 7 |
| `peizhi.md` | 修改：新增结构感知切分章节 | 7 |
| `CLAUDE.md` | 修改：Feature Toggles 补充 | 7 |
