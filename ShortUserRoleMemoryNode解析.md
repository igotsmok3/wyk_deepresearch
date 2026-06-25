# ShortUserRoleMemoryNode 解析

## 类的作用

短期用户角色记忆节点，是图流程中的第一个节点（在 `coordinator` 之前执行）。

其核心职责是：通过分析用户的历史提问，用 LLM 提取并持久化"用户角色画像"（职业、偏好、领域背景等），并在后续对话中将该记忆注入到提示词，从而让各节点的 LLM 调用能针对用户身份个性化响应。

功能开关由 `ShortTermMemoryProperties.enabled` 控制；若关闭，节点直接透传到 `coordinator`。

---

## 构造方法

```java
public ShortUserRoleMemoryNode(
    ChatClient shortMemoryAgent,
    ShortTermMemoryProperties shortTermMemoryProperties,
    ShortTermMemoryRepository shortTermMemoryRepository
)
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `shortMemoryAgent` | `ChatClient` | 专用于短期记忆提取/更新的 LLM 客户端 |
| `shortTermMemoryProperties` | `ShortTermMemoryProperties` | 短期记忆配置（开关、历史轮数、引导范围等） |
| `shortTermMemoryRepository` | `ShortTermMemoryRepository` | 短期记忆的存取仓库（读写历史提问和记忆结果） |

构造时还初始化了 `BeanOutputConverter<ShortUserRoleExtractResult>`，用于将 LLM 返回的 JSON 文本反序列化为结构化对象。

---

## 核心方法

### `apply(OverAllState state)`

**实现自** `NodeAction` 接口，是节点的入口方法，被图引擎自动调用。

| 参数 | 类型 | 说明 |
|------|------|------|
| `state` | `OverAllState` | 图的全局状态，包含当前轮查询、会话ID、各节点间共享的键值对 |

**返回值** `Map<String, Object>`：写入图状态的键值对。

| 返回键 | 值 | 说明 |
|--------|----|------|
| `short_user_role_next_node` | `"coordinator"` | 固定路由到下一节点 |
| `short_user_role_memory` | JSON 字符串 / `""` | 提取到的用户角色记忆（`""` 表示首轮或 ONCE 模式下后续轮次清空） |

**执行流程：**
1. 若功能未开启，直接返回路由到 `coordinator`。
2. 调用 `buildHistoryUserMessages` 获取近 N 轮历史提问。
3. 调用 `extractShortTermMemory` 让 LLM 提取本轮用户角色信息。
4. 调用 `saveOrUpdateShortTermMemory` 与历史记忆融合后持久化。
5. 根据 `guideScope`（`NONE` / `ONCE` / `ALWAYS`）决定是否将记忆写入状态供下游使用。
6. 任何异常均降级处理（记录日志后路由到 `coordinator`）。

---

### `buildHistoryUserMessages(OverAllState state)` *(private)*

**作用：** 从记忆仓库中读取该会话近 N 轮用户提问，格式化为"第N轮, 用户消息:xxx"字符串；同时将本轮用户提问保存到仓库。

| 参数 | 说明 |
|------|------|
| `state` | 从中提取 `sessionId` 和 `query` |

**返回值** `String`：格式化的历史提问字符串；若无历史则返回 `""`。

---

### `saveUserQuery(OverAllState state)` *(private)*

**作用：** 将当前轮的用户提问包装为 `UserMessage`（带时间戳元数据），写入 `ShortTermMemoryRepository`。

| 参数 | 说明 |
|------|------|
| `state` | 用于获取 `sessionId` 和 `query` |

**返回值：** 无（void）。

---

### `extractShortTermMemory(OverAllState state, String historyUserMessages)` *(private)*

**作用：** 构建提示词消息，调用 `shortMemoryAgent` 让 LLM 从当前提问和历史提问中提取用户角色信息，将返回的 JSON 反序列化为 `ShortUserRoleExtractResult`，并填充元数据。

| 参数 | 说明 |
|------|------|
| `state` | 用于填充结果的 userId、conversationId 等字段 |
| `historyUserMessages` | 格式化的历史提问字符串，注入到提取提示词中 |

**返回值** `ShortUserRoleExtractResult`：LLM 解析出的本轮用户角色结构化结果。

---

### `callShortMemoryAgent(List<Message> messages)` *(private)*

**作用：** 封装对 `shortMemoryAgent` 的调用，注入输出格式约束并返回完整 `ChatResponse`。

| 参数 | 说明 |
|------|------|
| `messages` | 传入 LLM 的消息列表 |

**返回值** `ChatResponse`：LLM 的完整响应对象。

---

### `fillResult(OverAllState state, ShortUserRoleExtractResult result)` *(private)*

**作用：** 将会话级元数据（固定 userId、当前 query、conversationId、创建时间）填入提取结果对象。

| 参数 | 说明 |
|------|------|
| `state` | 提取 sessionId 和 query |
| `result` | 待填充的提取结果 |

**返回值：** 无，直接修改 `result` 对象。

---

### `saveOrUpdateShortTermMemory(OverAllState state, ShortUserRoleExtractResult currentResult)` *(private)*

**作用：** 融合当前提取结果与历史记忆：
- 无历史记忆 → 直接保存当前结果。
- 当前置信度 ≥ 历史置信度 → 调用 `mergeAndUpdateShortTermMemory` 让 LLM 融合后保存。
- 当前置信度 < 历史置信度 → 保持历史记忆，仅更新交互次数和时间戳。

| 参数 | 说明 |
|------|------|
| `state` | 用于获取 sessionId |
| `currentResult` | 本轮 LLM 提取结果 |

**返回值** `ShortUserRoleExtractResult`：最终用于写入图状态的记忆结果（指令跟随最新输入）。

---

### `mergeAndUpdateShortTermMemory(OverAllState state, ShortUserRoleExtractResult current, ShortUserRoleExtractResult latest)` *(private)*

**作用：** 读取完整的历史记忆轨迹，构建 update 提示词，调用 LLM 对当前结果和历史结果进行智能融合，将融合后结果持久化。

| 参数 | 说明 |
|------|------|
| `state` | 用于获取 sessionId 和 query |
| `current` | 当前轮提取结果 |
| `latest` | 最近一次历史记忆结果 |

**返回值** `ShortUserRoleExtractResult`：融合后的最终记忆结果。

---

## 关键字段说明

| 字段 | 说明 |
|------|------|
| `USER_ID = "MOCK_USER_ID"` | 当前硬编码的用户标识，实际场景中应替换为真实用户 ID |
| `DATE_TIME_FORMATTER` | 时间格式 `yyyy-MM-dd HH:mm:ss`，用于记录创建/更新时间 |
| `converter` | `BeanOutputConverter<ShortUserRoleExtractResult>`，约束 LLM 输出格式并反序列化 |

---

## 与图流程的关系

```
[short_user_role_memory] ──→ [coordinator]
```

本节点始终路由到 `coordinator`，路由键为 `short_user_role_next_node`。提取到的用户角色记忆通过 `short_user_role_memory` 键写入 `OverAllState`，由 `TemplateUtil.addShortUserRoleMemory(messages, state)` 在后续各节点构建提示词时注入。
