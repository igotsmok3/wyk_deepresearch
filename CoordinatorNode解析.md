# CoordinatorNode 解析

## 类的作用

协调节点，是图流程中紧接 `short_user_role_memory` 节点的核心入口节点。

其职责是判断用户的提问是否需要启动深度研究流程：
- 若 LLM 触发了工具调用（tool call） → 路由到 `rewrite_multi_query`，开启深度研究。
- 若 LLM 未触发工具调用 → 直接用 LLM 回复用户（简单对话），路由到图的终止节点 `END`。

同时，当短期记忆功能启用时，节点负责维护会话级的对话历史（`MessageWindowChatMemory`）。

---

## 构造方法

```java
public CoordinatorNode(
    ChatClient coordinatorAgent,
    SessionContextService sessionContextService,
    MessageWindowChatMemory messageWindowChatMemory,
    ShortTermMemoryProperties shortTermMemoryProperties
)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `coordinatorAgent` | `ChatClient` | 负责判断是否需要深度研究的 LLM 客户端 |
| `sessionContextService` | `SessionContextService` | 会话上下文服务（当前节点未直接使用，为扩展保留） |
| `messageWindowChatMemory` | `MessageWindowChatMemory` | 滑动窗口式的对话历史存储（按 sessionId 隔离） |
| `shortTermMemoryProperties` | `ShortTermMemoryProperties` | 短期记忆配置，控制是否启用对话历史维护 |

---

## 核心方法

### `apply(OverAllState state)`

**实现自** `NodeAction` 接口，节点入口方法。

| 参数 | 类型 | 说明 |
|------|------|------|
| `state` | `OverAllState` | 图的全局状态 |

**返回值** `Map<String, Object>`：写入图状态的键值对。

| 返回键 | 可能的值 | 说明 |
|--------|---------|------|
| `coordinator_next_node` | `"rewrite_multi_query"` / `END` | 下一节点路由 |
| `deep_research` | `true` / `false` | 是否进入深度研究模式 |
| `output` | LLM 回复文本 | 仅在未触发工具调用时（简单对话）写入 |

**执行流程：**

1. **构建消息列表：**
   - 调用 `TemplateUtil.addShortUserRoleMemory(messages, state)` 注入用户角色记忆（若有）。
   - 添加 `coordinator` 系统提示词（从模板加载）。
   - 若短期记忆开启，从 `messageWindowChatMemory` 读取该 session 的历史 User/Assistant 交替消息并追加。
   - 追加本轮 `UserMessage`。
   - 若短期记忆开启，将本轮 `UserMessage` 写入 `messageWindowChatMemory`。

2. **调用 LLM：**
   - 调用 `coordinatorAgent`，使用阻塞式 `.call()` 获取完整响应。

3. **判断路由：**
   - 检查 `AssistantMessage` 是否包含 `toolCalls`：
     - **有 tool call** → `nextStep = "rewrite_multi_query"`，`deepResearch = true`（触发深度研究）。
     - **无 tool call** → `nextStep = END`，将 LLM 文本写入 `output`；若短期记忆开启，将 `AssistantMessage` 写入历史。

4. 将 `coordinator_next_node` 和 `deep_research` 写入返回 map。

---

## 关键设计点

| 设计点 | 说明 |
|--------|------|
| 工具调用作为分支信号 | `coordinatorAgent` 配置了工具，LLM 是否调用工具即为进入深度研究的判据，无需硬编码关键词匹配 |
| 历史消息维护 | `MessageWindowChatMemory` 按 sessionId 存储，实现多轮对话记忆，窗口大小由配置决定 |
| 阻塞调用 | 本节点使用 `.call()` 而非 `.stream()`，不产生流式输出 |

---

## 与图流程的关系

```
[short_user_role_memory] ──→ [coordinator] ──→ [rewrite_multi_query]（深度研究）
                                            ──→ END（简单对话直接回复）
```

路由键为 `coordinator_next_node`，由图的 dispatcher 读取后决定走向。
