# Dispatcher 实现详解

## 概述

Dispatcher 是 Spring AI Alibaba Graph 中**条件边（Conditional Edge）**的实现载体，接口为 `EdgeAction`。每个 Dispatcher 在节点执行完毕后被图框架调用，返回一个字符串——即下一个要跳转的节点名称（或 `END`）。

```
节点执行 → 写状态 → 图框架调用 Dispatcher.apply() → 返回节点名 → 跳转
```

---

## 核心接口

```java
// Spring AI Alibaba Graph 提供
public interface EdgeAction {
    String apply(OverAllState state) throws Exception;
}
```

`OverAllState` 是贯穿整个图生命周期的全局状态容器，所有节点和 Dispatcher 共享同一个实例。

---

## 路由策略

项目中共有两种路由策略：

### 策略一：状态键路由（主流策略）

节点执行完毕后，将目标节点名**写入** `OverAllState` 中预定的路由键（如 `coordinator_next_node`）；Dispatcher 仅做一件事：**读取该键并返回**。

**优点**：路由逻辑封装在节点内，Dispatcher 保持极简，便于测试和替换。  
**约定**：键名统一为 `{node_name}_next_node`，所有路由键在 `DeepResearchConfiguration` 中以 `ReplaceStrategy` 注册。

```java
// 节点端（写）
updated.put("coordinator_next_node", "rewrite_multi_query");

// Dispatcher 端（读）
return (String) state.value("coordinator_next_node", END);
```

### 策略二：直接条件判断（仅 ProfessionalKbDispatcher 使用）

Dispatcher 直接读取业务布尔标志，在自身内部完成路由决策，不依赖中间路由键。

```java
// 直接读业务标志，Dispatcher 内联决策
Boolean need = state.value("use_professional_kb", false);
return Boolean.TRUE.equals(need) ? "professional_kb_rag" : "reporter";
```

**适用场景**：路由逻辑极简（二选一），且目标节点固定不变。

---

## Dispatcher 清单

| Dispatcher | 读取的状态键 | 缺省值 | 可能的目标节点 |
|---|---|---|---|
| `ShortUserRoleMemoryDispatcher` | `short_user_role_next_node` | `END` | `coordinator`, `END` |
| `CoordinatorDispatcher` | `coordinator_next_node` | `END` | `rewrite_multi_query`, `END` |
| `RewriteAndMultiQueryDispatcher` | `rewrite_multi_query_next_node` | `END` | `background_investigator`, `user_file_rag`, `END` |
| `BackgroundInvestigationDispatcher` | `background_investigation_next_node` | `END` | `planner`, `reporter`, `END` |
| `InformationDispatcher` | `information_next_node` | `END` | `human_feedback`, `research_team`, `planner`, `END` |
| `HumanFeedbackDispatcher` | `human_next_node` | `END` | `planner`, `research_team`, `END` |
| `ResearchTeamDispatcher` | `research_team_next_node` | `"planner"` | `parallel_executor`, `professional_kb_decision` |
| `ProfessionalKbDispatcher` | `use_professional_kb`（布尔） | — | `professional_kb_rag`, `reporter` |

> `ResearchTeamDispatcher` 缺省值为 `"planner"` 而非 `END`，防止状态键缺失时意外终止图。

---

## 图中的注册方式

所有 Dispatcher 在 `DeepResearchConfiguration.deepResearch()` 中以 `addConditionalEdges` 注册，并声明合法目标节点的白名单：

```java
// 示例：InformationDispatcher 注册
stateGraph.addConditionalEdges(
    "information",
    edge_async(new InformationDispatcher()),
    Map.of(
        "reporter",       "reporter",
        "human_feedback", "human_feedback",
        "planner",        "planner",
        "research_team",  "research_team",
        END,              END
    )
);
```

`edge_async(...)` 将同步 `EdgeAction` 包装为异步执行，`Map.of(...)` 声明此边允许跳转的节点集合，图框架会校验 Dispatcher 返回值是否在白名单内。

---

## 完整图流程

```
START
  └─→ short_user_role_memory
        └─→ [ShortUserRoleMemoryDispatcher]
              ├─→ coordinator
              │     └─→ [CoordinatorDispatcher]
              │           ├─→ rewrite_multi_query
              │           │     └─→ [RewriteAndMultiQueryDispatcher]
              │           │           ├─→ background_investigator
              │           │           │     └─→ [BackgroundInvestigationDispatcher]
              │           │           │           ├─→ planner
              │           │           │           │     └─→ information
              │           │           │           │           └─→ [InformationDispatcher]
              │           │           │           │                 ├─→ human_feedback
              │           │           │           │                 │     └─→ [HumanFeedbackDispatcher]
              │           │           │           │                 │           ├─→ planner (重新规划)
              │           │           │           │                 │           └─→ research_team
              │           │           │           │                 ├─→ research_team
              │           │           │           │                 │     └─→ [ResearchTeamDispatcher]
              │           │           │           │                 │           ├─→ parallel_executor
              │           │           │           │                 │           │     └─→ researcher_N / coder_N
              │           │           │           │                 │           │           └─→ research_team (汇聚)
              │           │           │           │                 │           └─→ professional_kb_decision
              │           │           │           │                 │                 └─→ [ProfessionalKbDispatcher]
              │           │           │           │                 │                       ├─→ professional_kb_rag → reporter
              │           │           │           │                 │                       └─→ reporter
              │           │           │           │                 └─→ planner (重试)
              │           │           │           └─→ reporter (快捷路径)
              │           │           └─→ user_file_rag → background_investigator
              │           └─→ END (直接回答)
              └─→ END
```

---

## 各 Dispatcher 路由逻辑详解

### ShortUserRoleMemoryDispatcher

图的入口路由器，节点加载用户短期记忆后写入路由键。正常情况恒定跳转到 `coordinator`。

```java
return (String) state.value("short_user_role_next_node", END);
```

---

### CoordinatorDispatcher

判断是否触发深度研究：
- LLM **调用了工具** → `"rewrite_multi_query"`（深度研究路径）
- LLM **直接文本回答** → `END`（简单问答，本轮结束）

```java
// CoordinatorNode 写入决策
if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
    nextStep = "rewrite_multi_query";  // 触发深度研究
} // else nextStep = END
updated.put("coordinator_next_node", nextStep);
```

---

### RewriteAndMultiQueryDispatcher

对查询改写和扩展后，根据是否有用户上传文件决定路径：
- 有文件 → `"user_file_rag"` → `background_investigator`
- 无文件 → `"background_investigator"`（直接背景调查）

---

### BackgroundInvestigationDispatcher

背景调查完成后：
- 问题需要深入研究 → `"planner"`
- 问题较简单可直接回答 → `"reporter"`（快捷路径，跳过完整研究流程）

---

### InformationDispatcher

对 Planner 输出的计划进行反序列化，处理三种情况：

| 情况 | 目标节点 |
|---|---|
| 解析成功 + 未启用自动接受 | `human_feedback`（等待用户确认） |
| 解析成功 + 自动接受 | `research_team`（直接研究） |
| 解析失败 + 未超出重试次数 | `planner`（重新规划） |
| 解析失败 + 超出重试次数 | `END` |

---

### HumanFeedbackDispatcher

人工介入检查点，`ChatController` 在此节点前配置 interrupt。用户反馈决定后续走向：
- 用户**拒绝/修改**计划 → `"planner"`（重新规划）
- 用户**接受**计划 → `"research_team"`

---

### ResearchTeamDispatcher

并行任务汇聚点，每个 `researcher_N` / `coder_N` 执行完毕都会汇聚到此节点：
- 还有未完成步骤 → `"parallel_executor"`（继续分发）
- 所有步骤完成 → `"professional_kb_decision"`

缺省值为 `"planner"` 而非 `END`，是防御性设计。

---

### ProfessionalKbDispatcher

唯一采用"直接条件判断"策略的 Dispatcher，逻辑极简：

```java
Boolean need = state.value("use_professional_kb", false);
return Boolean.TRUE.equals(need) ? "professional_kb_rag" : "reporter";
```

`use_professional_kb` 由 `ProfessionalKbDecisionNode` 在分析研究结果后写入。

---

## 关键设计点

1. **解耦路由决策与路由执行**：节点负责"决定去哪里"，Dispatcher 负责"告诉框架去哪里"，两者职责清晰。

2. **状态键命名约定**：所有路由键遵循 `{node_name}_next_node` 格式，在 `DeepResearchConfiguration` 中以 `ReplaceStrategy` 注册（允许覆盖写入）。

3. **白名单校验**：`addConditionalEdges` 的 `Map` 参数声明了此条件边的合法目标集合，图框架会在运行时校验 Dispatcher 返回值，防止跳转到未声明节点。

4. **安全缺省值**：大多数 Dispatcher 缺省返回 `END`，防止状态键意外缺失时图陷入无限循环。`ResearchTeamDispatcher` 例外，缺省返回 `"planner"` 以保证并行汇聚逻辑的健壮性。
