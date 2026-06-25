# CoderNode 解析

## 类的作用

编码节点，负责执行研究计划（`Plan`）中类型为 `PROCESSING` 的步骤。

与 `ResearcherNode` 类似，图中会动态创建多个 `CoderNode` 实例（`coder_1`、`coder_2` 等）并行运行，每个实例通过 `executorNodeId` 区分并认领属于自己的步骤。`CoderNode` 不执行搜索，直接将步骤描述（含反射历史）传给 LLM，生成代码、数据处理脚本或计算分析结果，以流式方式写回状态。

同样支持反射机制和 MCP 工具挂载。

---

## 构造方法

```java
public CoderNode(
    ChatClient coderAgent,
    String executorNodeId,
    ReflectionProcessor reflectionProcessor,
    McpProviderFactory mcpFactory
)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `coderAgent` | `ChatClient` | 专用于代码生成/数据处理的 LLM 客户端 |
| `executorNodeId` | `String` | 节点编号（`"1"`, `"2"` 等），用于区分并行节点 |
| `reflectionProcessor` | `ReflectionProcessor` | 处理反射逻辑，可为 null |
| `mcpFactory` | `McpProviderFactory` | MCP 工具提供者工厂，可为 null |

`nodeName` 在构造时被赋值为 `"coder_" + executorNodeId`。

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
| `coder_content_{executorNodeId}` | `Flux<GraphResponse<StreamingOutput>>` | 编码结果流式生成器 |

**执行流程：**

1. 从 `state` 获取当前计划，调用 `findAssignedStep` 找到本节点负责的 `PROCESSING` 类型步骤。
2. 若无分配步骤，直接返回空 map。
3. **处理反射逻辑：** 若 `reflectionProcessor` 非 null，检查步骤反射状态；不需要继续则跳过。
4. 将步骤状态标记为 `"processing_<nodeName>"`。
5. **构建消息：** 调用 `buildTaskMessageWithReflectionHistory` 生成任务描述，包含：
   - 步骤标题和描述。
   - 当前 locale（语言环境，从 state 读取，默认 `"en-US"`）。
   - 若有反射历史，附加历史尝试记录和改进指令。
6. **挂载 MCP 工具：** 若 `mcpFactory` 非 null，创建 MCP 提供者并绑定工具。
7. **流式调用 LLM：** 发起流式请求，`doOnError` 处理错误时更新步骤状态。
8. **注册步骤标题：** 通过 `NodeStepTitleUtil.registerStepTitle` 在状态中注册步骤标题，前端据此显示进度。
9. **构建流输出生成器：** `mapResult` 中将步骤完成状态和编码结果写入 `assignedStep`，并将内容写入 `coder_content_{id}` 键。
10. 返回包含生成器的 map；异常时调用 `StateUtil.handleStepError` 更新步骤错误状态。

---

### `findAssignedStep(Plan currentPlan)` *(private)*

**作用：** 遍历计划步骤，找到类型为 `PROCESSING` 且属于本节点（通过 `ReflectionUtil.shouldProcessStep` 判断）的步骤。

| 参数 | 说明 |
|------|------|
| `currentPlan` | 当前研究计划 |

**返回值** `Plan.Step`：找到则返回步骤对象，否则返回 `null`。

`ResearcherNode` 认领 `RESEARCH` 类型步骤，`CoderNode` 认领 `PROCESSING` 类型步骤，两者通过步骤类型互相隔离。

---

### `buildTaskMessageWithReflectionHistory(Plan.Step step, String locale)` *(private)*

**作用：** 构建传给 LLM 的完整任务描述，包括步骤标题、描述、locale，以及反射历史（若有）。

| 参数 | 说明 |
|------|------|
| `step` | 当前步骤 |
| `locale` | 语言环境标识，如 `"zh-CN"` 或 `"en-US"` |

**返回值** `String`：Markdown 格式的任务描述文本。

相比 `ResearcherNode` 的同名方法，增加了 `## Locale` 部分，且反射提示词针对代码改进（"avoid previously identified code issues"）。

---

## 与 ResearcherNode 的对比

| 维度 | ResearcherNode | CoderNode |
|------|---------------|-----------|
| 步骤类型 | `RESEARCH` | `PROCESSING` |
| 是否搜索 | 是（调用搜索 API） | 否 |
| 智能 Agent 选择 | 是 | 否 |
| Locale 注入 | 否 | 是 |
| 结果键 | `researcher_content_{id}` | `coder_content_{id}` |

---

## 与图流程的关系

```
[parallel_executor] ──→ [researcher_1] ──┐
                    ──→ [coder_1]     ──┤──→ [research_team（汇聚）] ──→ [reporter]
                    ──→ [coder_2]     ──┘
```

`CoderNode` 与 `ResearcherNode` 并行执行，结果通过 `coder_content_{id}` 键写入状态，由 `ReporterNode` 汇聚后生成最终报告。
