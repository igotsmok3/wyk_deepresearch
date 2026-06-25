# PlannerNode 解析

## 类的作用

规划节点，负责在收集了背景调查信息后，生成结构化的研究计划（`Plan` 对象）。

计划包含若干 `Step`，每个 Step 标注了类型（`RESEARCH` 或 `PROCESSING`），分别对应后续的 `ResearcherNode` 和 `CoderNode` 执行。规划结果以流式方式输出到前端，同时通过 `planner_content` 键写入图状态供下游节点消费。

支持人工反馈循环：若用户在 `human_feedback` 节点提交了修改意见，规划节点可以接收反馈并重新规划。

---

## 构造方法

```java
public PlannerNode(ChatClient plannerAgent)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `plannerAgent` | `ChatClient` | 专用于生成研究计划的 LLM 客户端 |

构造时初始化 `BeanOutputConverter<Plan>`，约束 LLM 必须输出符合 `Plan` 结构的 JSON。

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
| `planner_content` | `Flux<GraphResponse<StreamingOutput>>` | 规划内容的流式输出生成器，图引擎会将其转发给 SSE 客户端 |

**执行流程：**

1. **构建消息列表（messages）：**

   | 步骤 | 操作 |
   |------|------|
   | 1.1 | `TemplateUtil.addShortUserRoleMemory`：注入用户角色记忆 |
   | 1.2 | `TemplateUtil.getMessage("planner", state)`：加载 planner 系统提示词模板 |
   | 1.3 | `TemplateUtil.getOptQueryMessage(state)`：添加（经过优化的）用户查询 |
   | 1.4 | 若 `enable_deepresearch=true`，将 `background_investigation_results`（背景调查结果列表）依次加入消息 |
   | 1.5 | 若 `feedback_content` 非空，追加用户反馈消息 |
   | 1.6 | 若 RAG 查询结果（`ragContent`）非空，追加该内容 |

2. **注册流式步骤标题：**
   - 向 `state` 注入 `"[正在制定研究计划]"` 作为步骤标题，供前端展示进度。

3. **流式调用 LLM：**
   - 使用 `plannerAgent.prompt(converter.getFormat()).messages(messages).stream().chatResponse()` 发起流式请求。
   - `converter.getFormat()` 将 `Plan` 的 JSON Schema 注入提示词，强制 LLM 按结构输出。

4. **构建流式输出生成器：**
   - 通过 `FluxConverter` 将 `Flux<ChatResponse>` 包装为 `Flux<GraphResponse<StreamingOutput>>`。
   - `mapResult` 中将流式片段拼接后的文本写入 `planner_content` 键，图引擎最终将其解析为 `Plan` 对象。

5. 返回包含生成器的 Map，图引擎负责订阅并驱动流。

---

## 关键设计点

| 设计点 | 说明 |
|--------|------|
| 结构化输出 | `BeanOutputConverter<Plan>` 将 Plan 的字段结构注入到提示词末尾，确保 LLM 输出可被反序列化 |
| 流式输出 | 使用 `Flux` 流式推送，避免长时间等待，同时实时呈现给前端 |
| 人工反馈支持 | `feedback_content` 来自 `human_feedback` 节点，允许用户审核计划后提出修改意见，触发重新规划 |
| RAG 集成 | RAG 检索内容在规划阶段注入，使计划能基于私有知识库定制 |

---

## 与图流程的关系

```
[background_investigator] ──→ [planner] ──→ [human_feedback] ──→ [parallel_executor]
                                        ↑
                              （human_feedback 返回反馈后可重新进入）
```

`planner_content` 最终被反序列化为 `Plan`，`Plan.steps` 中的任务分配给 `ResearcherNode`（`RESEARCH` 类型）和 `CoderNode`（`PROCESSING` 类型）并行执行。
