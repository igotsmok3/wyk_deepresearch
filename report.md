# DeepResearch 报告生成机制详解

## 整体架构

报告生成贯穿三个阶段：**生成**（ReporterNode 调用 LLM）→ **存储**（ReportService 持久化）→ **交付**（ReportController 提供查询/导出/下载）。

```
图流程 (StateGraph)
  └─ ReporterNode
       ├─ 聚合上下文消息
       ├─ 流式调用 LLM (reporterAgent)
       └─ 完成后写入 SessionContextService → ReportService

ReportController (/api/reports/*)
  ├─ GET  /{threadId}          查询报告
  ├─ GET  /{threadId}/exists   检查是否存在
  ├─ DELETE /{threadId}        删除报告
  ├─ POST /export              导出为文件（MD/PDF）
  ├─ GET  /download/{threadId} 下载文件
  └─ GET  /build-html          交互式 HTML（SSE 流）
```

---

## 一、报告生成：ReporterNode

**位置**：`node/ReporterNode.java`  
**触发时机**：StateGraph 流程最后一个工作节点，在 `reporter → END` 边执行。

### 上下文聚合策略

ReporterNode 按固定顺序将多阶段研究成果拼装为 LLM 的 `messages` 列表：

| 优先级 | 来源 | 条件 |
|--------|------|------|
| 1 | 短期用户角色记忆（ShortUserRoleMemory） | `short-term-memory.enabled=true` |
| 2 | 背景调查结果（BackgroundInvestigationNode 产出） | 必选 |
| 3 | 研究计划标题 + 思路（Plan.title / Plan.thought） | `enable_deepresearch=true` |
| 4 | 并行 Researcher / Coder 节点各步骤输出 | `enable_deepresearch=true` |
| 5 | 专业知识库 RAG 检索内容 | `use_professional_kb=true` |

```java
// RESEARCH_FORMAT 模板（注入计划标题和思路）
"# Research Requirements\n\n## Task\n\n{0}\n\n## Description\n\n{1}"
```

### 流式生成与持久化

```
reporterAgent.prompt().messages(messages).stream().chatResponse()
  └─ FluxConverter.mapResult(response → {
       finalReport = response.getText()
       messageWindowChatMemory.add(sessionId, finalReport)  // 写短期记忆
       sessionContextService.addSessionHistory(...)          // 触发 ReportService.saveReport
       return Map("final_report", finalReport)
     })
```

- 使用 `FluxConverter` 将 `Flux<ChatResponse>` 转为图框架的 `Flux<GraphResponse<StreamingOutput>>`，实现边生成边推送 SSE。
- 报告完整文本在流结束后通过 `mapResult` 回调一次性落盘，不在流过程中分片存储。

### Prompt 模板

**位置**：`src/main/resources/prompts/reporter.md`

规定了报告的固定结构：
1. **Key Points**（3-5 条核心结论）
2. **Overview**（背景与问题）
3. **Detailed Analysis**（多维分析：数据驱动、正反观点、案例、前瞻）
4. **Survey Note**（可选，方法论说明）
5. **Key Citations**（末尾引用，禁止行内引用）

同时要求优先使用 Markdown 表格展示对比数据。

---

## 二、报告存储：ReportService

**接口**：`service/ReportService.java`  
提供 `saveReport / getReport / existsReport / deleteReport` 四个方法，以 `threadId` 为主键。

### 双实现策略（条件注入）

| 实现类 | 激活条件 | 存储介质 | 特点 |
|--------|----------|----------|------|
| `ReportMemoryService` | `spring.data.redis.enabled=false`（默认） | `ConcurrentHashMap` | 进程内，重启丢失 |
| `ReportRedisService` | `spring.data.redis.enabled=true` | Redis（key 前缀 `deepresearch:report:`） | 持久化，TTL 24 小时 |

```java
// Redis key 格式
"deepresearch:report:" + threadId
// 过期时间
DEFAULT_EXPIRE_HOURS = 24
```

### SessionContextService 的分层设计

`InMemorySessionContextService` 维护两张内存表：

```
sessionThreadMap: sessionId → List<threadId>   // 会话与线程的映射
sessionHistoryMap: threadId → SessionHistory    // 元信息（不含报告正文）
```

报告正文单独由 `ReportService` 持有，读取时按需回填，避免 `sessionHistoryMap` 因存大文本而内存膨胀。

---

## 三、报告交付：ReportController

**位置**：`controller/ReportController.java`  
**路由前缀**：`/api/reports`

### API 清单

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/{threadId}` | 查询报告正文，404 时返回 notfound 状态 |
| GET | `/{threadId}/exists` | 检查报告是否存在 |
| DELETE | `/{threadId}` | 删除报告 |
| POST | `/export` | 导出为 Markdown 或 PDF 文件，返回文件路径和下载 URL |
| GET | `/download/{threadId}?format=` | 下载文件（文件不存在时自动生成） |
| GET | `/build-html?threadId=` | 通过 LLM 将报告转为交互式 HTML（SSE 流） |

### 统一响应结构

```java
record ReportResponse<T>(
    String threadId,        // 线程 ID
    String status,          // "success" | "notfound" | "error"
    String message,         // 描述信息
    T data                  // 实际数据（report_information 字段）
)
```

---

## 四、报告导出：ExportService

**位置**：`service/ExportService.java`  
**导出根目录**：由 `spring.ai.alibaba.deepresearch.export.path` 配置，默认 `~/reports`。

### 格式支持

| 格式 | 实现 | 文件名 |
|------|------|--------|
| markdown / md | 直接写文件 | `{threadId}.md` |
| pdf | Markdown → HTML → PDF | `{threadId}.pdf` |

### PDF 转换链路

```
ExportService.saveAsPdf(threadId)
  └─ FormatConversionUtil.convertMarkdownToPdfFile(content, path)
       ├─ HtmlGenerationUtil.markdownToHtml(content)
       │    ├─ commonmark Parser（TablesExtension 支持 GFM 表格）
       │    ├─ HtmlRenderer → HTML 片段
       │    └─ 包裹 XHTML 文档头（GitHub Markdown CSS + 阿里巴巴普惠体字体）
       └─ PdfRendererBuilder（openhtmltopdf + PdfBox）
            ├─ 嵌入字体：AlibabaPuHuiTi-3-55-Regular.ttf
            └─ 写入 .pdf 文件
```

### 下载文件名策略

1. 从报告 Markdown 中提取一级标题（`^# (.+)$`）
2. 若标题存在，使用标题作为下载文件名（UTF-8 URL 编码，RFC 5987 `filename*=UTF-8''` 格式）
3. 否则使用 threadId 作为文件名

---

## 五、交互式 HTML 报告

**入口**：`GET /api/reports/build-html?threadId=`  
**Prompt**：`src/main/resources/prompts/buildInteractiveHtmlPrompt.md`

LLM（`interactionAgent`）将 Markdown 报告一次性转换为完整的单页 HTML 应用：

- **技术栈**：Tailwind CSS（CDN）+ Chart.js（CDN）+ Noto Sans SC（Google Fonts）
- **结构**：非线性主题式 SPA，含顶部固定导航、首页数据图表、主题卡片模块、页脚
- **交互层次**：视觉引导（导航高亮）→ 内容探索（可展开卡片）→ 数据透查（交互式图表）
- **输出**：纯 HTML 文件内容，通过 SSE 流式推送给前端

---

## 六、关键文件索引

| 职责 | 文件路径 |
|------|----------|
| 报告生成节点 | `node/ReporterNode.java` |
| 报告 Prompt | `resources/prompts/reporter.md` |
| 交互式 HTML Prompt | `resources/prompts/buildInteractiveHtmlPrompt.md` |
| 报告服务接口 | `service/ReportService.java` |
| 内存存储实现 | `service/ReportMemoryService.java` |
| Redis 存储实现 | `service/ReportRedisService.java` |
| 会话上下文服务 | `service/InMemorySessionContextService.java` |
| 导出服务 | `service/ExportService.java` |
| 文件操作工具 | `util/export/FileOperationUtil.java` |
| HTML 生成工具 | `util/export/HtmlGenerationUtil.java` |
| 格式转换工具 | `util/export/FormatConversionUtil.java` |
| 报告控制器 | `controller/ReportController.java` |
| 统一响应模型 | `model/response/ReportResponse.java` |
| 导出数据模型 | `model/dto/ExportData.java` |
| 会话历史模型 | `model/SessionHistory.java` |
| 导出配置属性 | `config/export/ExportProperties.java` |
| Agent Bean 配置 | `agents/AgentsConfiguration.java` |
