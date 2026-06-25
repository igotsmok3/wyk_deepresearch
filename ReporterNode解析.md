# ReporterNode 解析

## 类的作用

报告节点，是深度研究流程的最后一个执行节点。

其职责是汇聚所有并行研究员节点（`ResearcherNode`）和编码节点（`CoderNode`）的输出，连同背景调查结果、专业知识库内容，一起构建完整的上下文，调用 LLM 生成最终研究报告，并将报告以流式方式推送给客户端，同时持久化保存到会话历史。

---

## 构造方法

```java
public ReporterNode(
    ChatClient reporterAgent,
    ReportService reportService,
    SessionContextService sessionContextService,
    MessageWindowChatMemory messageWindowChatMemory,
    ShortTermMemoryProperties shortTermMemoryProperties
)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `reporterAgent` | `ChatClient` | 专用于生成最终报告的 LLM 客户端 |
| `reportService` | `ReportService` | 报告导出/存储服务（当前代码中注入但在 `apply` 中未直接调用，可能在流结束后使用） |
| `sessionContextService` | `SessionContextService` | 会话上下文服务，用于保存会话历史 |
| `messageWindowChatMemory` | `MessageWindowChatMemory` | 对话历史存储，若短期记忆开启则将最终报告写入历史 |
| `shortTermMemoryProperties` | `ShortTermMemoryProperties` | 短期记忆配置 |

---

## 核心方法

### `apply(OverAllState state)`

**实现自** `NodeAction` 接口，节点入口方法。

| 参数 | 类型 | 说明 |
|------|------|------|
| `state` | `OverAllState` | 图的全局状态 |

**返回值** `Map<String, Object>`：

| 键 | 值类型 | 说明 |
|----|--------|------|
| `final_report` | `Flux<GraphResponse<StreamingOutput>>` | 最终报告的流式生成器 |
| `thread_id` | `String` | 当前图线程 ID |

**执行流程：**

1. 从 `state` 读取 `threadId`（图线程 ID）和 `sessionId`（会话 ID）。

2. **构建消息列表：**

   | 步骤 | 内容 |
   |------|------|
   | 注入用户角色记忆 | `TemplateUtil.addShortUserRoleMemory(messages, state)` |
   | 背景调查结果 | 读取 `background_investigation_results` 列表，逐条加入消息 |
   | 研究计划格式消息 | 使用 `RESEARCH_FORMAT` 模板，注入 `currentPlan.title` 和 `currentPlan.thought` |
   | 并行节点研究内容 | 调用 `StateUtil.getParallelMessages`，汇聚所有 `researcher_*` 和 `coder_*` 节点的执行结果 |
   | 专业知识库内容 | 若 `use_professional_kb=true` 且 RAG 内容非空，追加知识库检索结果 |

3. **注册步骤标题：** 向状态写入 `"[报告生成]"` 步骤标题。

4. **流式调用 LLM：** 使用 `reporterAgent.prompt().messages(messages).stream().chatResponse()` 发起流式请求。

5. **构建流输出生成器：** 在 `mapResult` 中：
   - 获取完整报告文本 `finalReport`。
   - 若短期记忆开启，将报告作为 `AssistantMessage` 写入 `messageWindowChatMemory`（维护多轮对话历史）。
   - 调用 `sessionContextService.addSessionHistory` 将报告持久化到会话历史（`GraphId` 由 sessionId + threadId 组成）。
   - 返回 `Map.of("final_report", finalReport, "thread_id", threadId)`。

6. 返回包含生成器和 `thread_id` 的结果 map。

---

## 关键常量

```java
private static final String RESEARCH_FORMAT =
    "# Research Requirements\n\n## Task\n\n{0}\n\n## Description\n\n{1}";
```

用 `MessageFormat.format` 填充计划标题（`{0}`）和计划思考（`{1}`），作为报告生成的结构化框架提示。

---

## 关键设计点

| 设计点 | 说明 |
|--------|------|
| 结果汇聚 | `StateUtil.getParallelMessages` 按最大步骤数遍历所有并行节点的 `researcher_content_*` 和 `coder_content_*` 键，统一收集 |
| 持久化时机 | 报告持久化发生在流式结果的 `mapResult` 回调中，即 LLM 完整响应到达后才执行 |
| 短期记忆联动 | 报告写入 `messageWindowChatMemory` 后，下一轮对话的 `CoordinatorNode` 可以读取到历史报告作为上下文 |
| 错误隔离 | 报告保存失败（异常）只记录日志，不影响流式输出继续推送到客户端 |

---

## 与图流程的关系

```
[researcher_1] ──┐
[researcher_2] ──┤
[coder_1]     ──┤──→ [research_team] ──→ [reporter] ──→ END
[coder_2]     ──┘
```

`ReporterNode` 是深度研究路径的终止节点，执行完成后图流程结束，最终报告通过 SSE 推送给前端，并通过 `ReportController` 供后续查询和导出使用。
