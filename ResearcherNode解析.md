# ResearcherNode 解析

## 类的作用

研究员节点，负责执行研究计划（`Plan`）中类型为 `RESEARCH` 的步骤。

图中会动态创建多个 `ResearcherNode` 实例（`researcher_1`、`researcher_2` 等）并行运行，每个实例通过 `executorNodeId` 区分，各自认领属于自己的步骤。每个步骤的执行过程是：搜索互联网信息 → 将搜索结果注入提示词 → 调用 LLM 生成研究内容 → 将结果流式写回状态。

支持反射（Reflection）机制：若步骤执行结果质量不佳，可通过反射历史记录重新执行以改进。

支持智能 Agent 路由：根据问题类型，可将任务路由到领域专业化 Agent 而非默认的 `researchAgent`。

---

## 构造方法

```java
public ResearcherNode(
    ChatClient researchAgent,
    String executorNodeId,
    ReflectionProcessor reflectionProcessor,
    McpProviderFactory mcpFactory,
    SearchFilterService searchFilterService,
    SmartAgentDispatcherService smartAgentDispatcher,
    SmartAgentProperties smartAgentProperties,
    JinaCrawlerService jinaCrawlerService
)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `researchAgent` | `ChatClient` | 默认研究 LLM 客户端 |
| `executorNodeId` | `String` | 该实例的编号（`"1"`, `"2"` 等），用于区分并行节点和认领步骤 |
| `reflectionProcessor` | `ReflectionProcessor` | 处理反射逻辑的组件，可为 null（反射功能未启用时） |
| `mcpFactory` | `McpProviderFactory` | MCP 工具提供者工厂，用于动态挂载 MCP 工具到 LLM 请求 |
| `searchFilterService` | `SearchFilterService` | 搜索结果过滤服务 |
| `smartAgentDispatcher` | `SmartAgentDispatcherService` | 智能 Agent 调度服务 |
| `smartAgentProperties` | `SmartAgentProperties` | 智能 Agent 配置（是否启用、Agent 列表等） |
| `jinaCrawlerService` | `JinaCrawlerService` | Jina 网页抓取服务，用于增强搜索结果内容 |

内部还创建了 `SearchInfoService` 和 `SmartAgentSelectionHelperService` 实例。

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
| `researcher_content_{executorNodeId}` | `Flux<GraphResponse<StreamingOutput>>` | 研究内容流式生成器 |
| `site_information` | `List<Map<String,String>>` | 累积的搜索结果站点信息 |

**执行流程：**

1. 从 `state` 中获取当前计划，调用 `findAssignedStep` 找到本节点负责的步骤。
2. 若无分配步骤，直接返回空 map。
3. **处理反射逻辑：** 若 `reflectionProcessor` 不为 null，检查该步骤是否需要反射处理；若不需要继续（已完成反射），跳过执行。
4. 将步骤状态标记为 `"processing_<nodeName>"`。
5. **构建消息列表：**
   - 注入用户角色记忆。
   - 调用 `buildTaskMessageWithReflectionHistory` 构建任务描述（含反射历史）。
   - 添加引用格式提醒（要求 LLM 不在正文内联引用，在末尾集中列出参考文献）。
6. **执行搜索：** 调用 `searchInfoService.searchInfo` 搜索互联网，结果追加到消息和 `site_information`。
7. **智能 Agent 选择：** 调用 `selectSmartAgent` 根据步骤内容决定使用哪个 Agent。
8. **挂载 MCP 工具：** 若 `mcpFactory` 非 null，创建 MCP 提供者并将工具绑定到请求。
9. **流式调用 LLM：** 发起流式请求，`doOnError` 处理错误时更新步骤状态。
10. **构建流输出生成器：** `mapResult` 中将步骤状态更新为完成，将研究结果写入 `assignedStep.executionRes`，并写入 `researcher_content_{id}` 键。
11. 返回包含生成器的 map。

---

### `findAssignedStep(Plan currentPlan)` *(private)*

**作用：** 遍历计划中所有 `Step`，找到类型为 `RESEARCH` 且 `ReflectionUtil.shouldProcessStep` 返回 true（即分配给本节点且尚未完成）的步骤。

| 参数 | 说明 |
|------|------|
| `currentPlan` | 当前研究计划 |

**返回值** `Plan.Step`：找到的步骤；未找到则返回 `null`。

步骤的认领机制依赖 `executionStatus` 字段：未开始的步骤包含节点编号信息，`ReflectionUtil.shouldProcessStep` 据此判断是否属于本节点。

---

### `buildTaskMessage(Plan.Step step)` *(private)*

**作用：** 构建不含反射历史的基础任务描述，用于搜索阶段的查询词。

| 参数 | 说明 |
|------|------|
| `step` | 当前步骤对象 |

**返回值** `String`：包含步骤标题和描述的 Markdown 文本（`originTaskContent`，用于搜索关键词）。

---

### `buildTaskMessageWithReflectionHistory(Plan.Step step)` *(private)*

**作用：** 在基础任务描述基础上，若步骤存在反射历史，追加历史尝试记录和改进要求，指导 LLM 避免重复之前的问题。

| 参数 | 说明 |
|------|------|
| `step` | 当前步骤对象 |

**返回值** `String`：完整任务描述文本（含反射历史，用于 LLM 提示词）。

---

### `selectSmartAgent(Plan.Step step, String taskContent, OverAllState state)` *(private)*

**作用：** 若智能 Agent 功能开启，根据步骤标题和描述的语义，选择最合适的专业化 Agent；否则返回默认 `researchAgent`。

| 参数 | 说明 |
|------|------|
| `step` | 当前步骤 |
| `taskContent` | 任务描述文本 |
| `state` | 图状态（传递给选择服务） |

**返回值** `AgentSelectionResult`：包含选中的 `ChatClient`、Agent 类型说明、是否使用了智能 Agent，以及需要写回状态的额外键值对。

---

## 与图流程的关系

```
[parallel_executor] ──→ [researcher_1] ──┐
                    ──→ [researcher_2] ──┤──→ [research_team（汇聚）] ──→ [reporter]
                    ──→ [coder_1]     ──┘
```

多个 `ResearcherNode` 并行执行，通过 `researcher_content_{id}` 键将各自结果写入状态，`research_team` 节点（或 reporter）负责汇聚所有并行结果。
