# 设计文档：记忆持久化（Redis 会话存储 + JSON 审计文件）

**日期**：2026-06-25  
**状态**：待实现  
**目标**：实现跨会话连续性，用户能看到历史会话列表；AI 能跨会话记住用户角色偏好；每次图执行完成后保存 OverAllState 到 JSON 文件供审计调试。

---

## 一、现状分析

| 组件 | 当前实现 | 问题 |
|------|---------|------|
| 聊天消息记忆 | `InMemoryChatMemoryRepository` | 应用重启丢失 |
| 用户查询短期记忆 | `ShortUserRoleExtractInMemory`（ConcurrentHashMap） | 重启丢失；userId 硬编码为 `MOCK_USER_ID` |
| 图 Checkpoint | `MemorySaver` | 重启丢失（本次不改） |
| 会话元数据 | 无 | 无法给前端提供历史会话列表 |
| OverAllState 审计 | 无 | 无法事后调试图执行过程 |
| 报告存储 | `ReportRedisService`（Redis 可选）/ `ReportMemoryService` | 已有 Redis 实现，可参考 |

**关键观察**：
- `ShortUserRoleMemoryNode` 中 `USER_ID = "MOCK_USER_ID"` 硬编码，导致所有用户共享一个角色记忆。本次设计继续使用 mock userId（多用户认证体系不在本次范围），但结构上按 userId 隔离，便于后续扩展。
- 现有 `ReportRedisService` 已有 Redis 操作模式，新实现可参考其 `RedisTemplate` 用法。
- `ChatController` 构造时已注册 `GraphObservationLifecycleListener`，可新增自定义 listener 用于审计导出。

---

## 二、Redis Key Schema

```
# 聊天消息（每条 Message 序列化为 JSON 字符串）
deepresearch:chat:{conversationId}:messages        → Redis List<String>

# 会话元数据（前端列表展示用）
deepresearch:session:{sessionId}:meta              → Redis Hash
    fields: title, userId, createdAt, lastMessageAt, status

# 用户的会话列表（按时间倒序，用于分页查询）
deepresearch:user:{userId}:sessions                → Redis Sorted Set
    member = sessionId, score = timestamp(毫秒)

# 用户角色短期记忆（跨会话，按 userId 存）
deepresearch:user:{userId}:short_term_memory       → Redis String（JSON）

# 用户角色提取轨迹（按 conversationId，辅助 merge 逻辑）
deepresearch:user:{userId}:{conversationId}:role_track → Redis List<String>

# 用户查询历史（按 conversationId）
deepresearch:conv:{conversationId}:user_queries    → Redis List<String>
```

**TTL 策略**（均可通过配置覆盖）：
- `chat:*:messages`：30 天
- `session:*:meta`：30 天（与 messages 同步）
- `user:*:sessions`：不设 TTL（Sorted Set 成员随 meta 过期自然失效）
- `user:*:short_term_memory`：180 天（用户角色记忆跨会话长期保留）
- `user:*:*:role_track`：30 天
- `conv:*:user_queries`：30 天

**Key 前缀常量**：统一定义在 `RedisMemoryKeyConstants` 类中。

---

## 三、新增/修改文件清单

### 3.1 新增：`RedisMemoryKeyConstants`

**路径**：`memory/RedisMemoryKeyConstants.java`

```java
public final class RedisMemoryKeyConstants {
    public static final String CHAT_MESSAGES    = "deepresearch:chat:%s:messages";
    public static final String SESSION_META     = "deepresearch:session:%s:meta";
    public static final String USER_SESSIONS    = "deepresearch:user:%s:sessions";
    public static final String USER_SHORT_MEMORY = "deepresearch:user:%s:short_term_memory";
    public static final String ROLE_TRACK       = "deepresearch:user:%s:%s:role_track";
    public static final String USER_QUERIES     = "deepresearch:conv:%s:user_queries";
}
```

---

### 3.2 新增：`RedisChatMemoryRepository`

**路径**：`memory/RedisChatMemoryRepository.java`

实现 Spring AI 的 `ChatMemoryRepository` 接口（`org.springframework.ai.chat.memory.ChatMemoryRepository`）。

**接口方法实现**：

```java
// 保存消息：序列化为 JSON 后 RPUSH，并刷新 TTL
void add(String conversationId, List<Message> messages)

// 读取消息：LRANGE 0 -1，反序列化为 Message 列表
List<Message> findByConversationId(String conversationId)

// 删除会话消息：DEL key
void deleteByConversationId(String conversationId)
```

**消息序列化**：Spring AI `Message` 是接口，需自定义序列化策略。
- 序列化：将 `Message` 转为 `{"type": "user"|"assistant"|"system", "text": "...", "metadata": {...}}` JSON
- 反序列化：按 `type` 字段创建对应子类（`UserMessage` / `AssistantMessage` / `SystemMessage`）
- 使用 `ObjectMapper`（Spring 容器注入），注册 `JavaTimeModule` 处理 `LocalDateTime`

**依赖注入**：`RedisTemplate<String, String>`（已在 `RedisConfig` 中定义）

---

### 3.3 新增：`RedisShortTermMemoryRepository`

**路径**：`memory/RedisShortTermMemoryRepository.java`

实现 `ShortTermMemoryRepository` 接口，行为与 `ShortUserRoleExtractInMemory` 一致，存储介质改为 Redis。

**方法映射**：

| 方法 | Redis 操作 | Key |
|------|-----------|-----|
| `getRecentUserMessages(conversationId, limit)` | LRANGE（取末尾 limit 条）→ 反序列化 | `conv:{conversationId}:user_queries` |
| `getRecentUserQueries(conversationId, limit)` | 同上，返回 text 列表 | 同上 |
| `saveUserQuery(conversationId, messages)` | RPUSH，刷新 TTL | 同上 |
| `findMessageTrack(userId, conversationId)` | LRANGE 0 -1 | `user:{userId}:{conversationId}:role_track` |
| `findLatestExtractMessage(userId, conversationId)` | LINDEX -1（取最后一条） | 同上 |
| `saveOrUpdate(userId, conversationId, messages)` | RPUSH，刷新 TTL | 同上 |
| `deleteBy(userId, conversationId)` | DEL | 两个相关 key |

**特殊处理**：`findLatestExtractMessage` 语义是"最近一条提取结果"，对应 Redis List 的最后一个元素（LINDEX -1），而非整个 track 列表。

---

### 3.4 修改：`MemoryConfig`

**路径**：`config/MemoryConfig.java`

当前 `@ConditionalOnProperty` 控制整个类，修改后按 Redis 是否启用分别注入：

```java
@Configuration
@ConditionalOnProperty(name = "spring.ai.alibaba.deepresearch.short-term-memory.enabled", havingValue = "true")
public class MemoryConfig {

    // Redis 启用时：使用 RedisChatMemoryRepository
    @Bean
    @ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true")
    public MessageWindowChatMemory messageWindowChatMemoryRedis(
            RedisChatMemoryRepository redisChatMemoryRepository,
            ShortTermMemoryProperties props) {
        return MessageWindowChatMemory.builder()
            .chatMemoryRepository(redisChatMemoryRepository)
            .maxMessages(props.getConversationMemory().getMaxMessages())
            .build();
    }

    // Redis 禁用时：保持现有内存实现（降级）
    @Bean
    @ConditionalOnMissingBean(MessageWindowChatMemory.class)
    public MessageWindowChatMemory messageWindowChatMemoryInMemory(ShortTermMemoryProperties props) {
        // 现有逻辑不变
    }
}
```

`ShortTermMemoryRepository` 的 bean 注入同理：`@ConditionalOnProperty` 控制注入 `RedisShortTermMemoryRepository` 或 `ShortUserRoleExtractInMemory`。注意 `ShortUserRoleExtractInMemory` 当前有 `@Component` 注解，需改为条件 bean（加 `@ConditionalOnMissingBean`）。

---

### 3.5 新增：`SessionMetadataService`

**路径**：`service/SessionMetadataService.java`

仅在 Redis 启用时生效（`@ConditionalOnProperty`）。

```java
// 创建会话元数据（首次请求时调用）
void createSession(String sessionId, String userId, String firstMessage)
// → HSET session:meta: title/userId/createdAt/lastMessageAt/status
// → ZADD user:{userId}:sessions score=currentTimeMillis member=sessionId

// 更新最后消息时间（每次收到 assistant 消息后）
void updateLastMessage(String sessionId, String lastMessage)

// 查询用户的会话列表（分页）
List<SessionMetaDTO> listSessions(String userId, int page, int size)
// → ZREVRANGE user:{userId}:sessions offset count
// → 批量 HGETALL session:{sessionId}:meta
```

**`SessionMetaDTO`**（返回给前端）：
```java
record SessionMetaDTO(
    String sessionId,
    String title,       // 首条用户消息截断为 50 字符
    String userId,
    String createdAt,
    String lastMessageAt,
    String status       // "active" | "completed"
)
```

**调用时机**：在 `ChatController` 中 `graphProcess.createNewGraphId()` 之后，当 Redis 启用时调用 `sessionMetadataService.createSession()`。

---

### 3.6 新增：`AuditExportService`

**路径**：`service/AuditExportService.java`

```java
// 将 OverAllState 序列化为 JSON 写文件
void exportState(String sessionId, OverAllState state)
// 文件路径：{auditBasePath}/{sessionId}/{yyyyMMddHHmmss}-state.json
// 序列化：复用 DeepResearchStateSerializer 或 ObjectMapper
```

**触发时机**：在 `ChatController` 构造时注册的 `CompileConfig.withLifecycleListener()` 中，监听 `reporter` 节点执行完成事件，调用 `exportState()`。

**配置开关**：`spring.ai.alibaba.deepresearch.memory.audit.enabled=true`（默认 true）。

当 `audit.enabled=false` 或 `AI_DEEPRESEARCH_EXPORT_PATH` 未配置时，跳过写文件。

---

### 3.7 新增：`SessionController`

**路径**：`controller/SessionController.java`

```
GET /session/list?userId={userId}&page=0&size=20
    → 调用 SessionMetadataService.listSessions()
    → 返回 ApiResponse<List<SessionMetaDTO>>

GET /session/{sessionId}/messages?lastN=50
    → 调用 RedisChatMemoryRepository（或 MessageWindowChatMemory）
    → 返回 ApiResponse<List<MessageDTO>>
```

仅当 Redis 启用时，`SessionMetadataService` 存在，接口才有意义。Redis 禁用时返回空列表（或 404），不抛异常。

---

### 3.8 提示词工程：跨会话用户角色记忆注入

**背景**：当前 `ShortUserRoleMemoryNode` 的 `saveOrUpdateShortTermMemory()` 方法会读取 `findLatestExtractMessage(userId, conversationId)` 获取历史角色记忆，用于 merge 决策。改用 Redis 后，`findLatestExtractMessage` 从 Redis 读取，行为不变。

**跨会话增强**（新增逻辑）：

当前短期记忆是按 `userId:conversationId` 存储的，新会话开始时没有历史记忆，每次从零提取。要实现跨会话记忆，需要在会话开始时将"上一会话的提取结果"作为初始种子注入。

**方案**：在 `ShortUserRoleMemoryNode.extractShortTermMemory()` 中，当 `historyUserMessages` 为空（表示新会话第一轮对话）时，从 Redis 读取 `user:{userId}:short_term_memory` 作为历史背景，修改 prompt 注入。

**Prompt 修改**（`prompts/memory/short/shortmemory-extract.md`）：

在 `# Available Data` 部分增加可选字段：

```markdown
# Available Data (Current Conversation Only)
- Current User Message: {{ last_user_message }}
- History User Messages: {{ history_user_messages }}
- Cross-Session User Background (from previous conversations, may be empty): {{ cross_session_memory }}
```

并在 `# Note` 部分增加：

```markdown
- If `cross_session_memory` is provided and non-empty, use it as prior knowledge about the user. 
  Combine it with current session evidence to produce a more accurate role assessment.
  Do NOT blindly copy it — re-evaluate based on current conversation context.
```

**代码修改**（`ShortUserRoleMemoryNode`）：

```java
// 在 extractShortTermMemory() 构建消息时：
String crossSessionMemory = "";
if (StringUtils.isEmpty(historyUserMessages)) {
    // 新会话第一轮，尝试加载跨会话记忆
    Message crossMemory = shortTermMemoryRepository.findCrossSessionMemory(USER_ID);
    if (crossMemory != null) {
        crossSessionMemory = crossMemory.getText();
    }
}
// 将 crossSessionMemory 传入 TemplateUtil.getShortMemoryExtractMessage()
```

需在 `ShortTermMemoryRepository` 接口新增方法：

```java
// 读取用户跨会话长期角色记忆（按 userId，不按 conversationId）
Message findCrossSessionMemory(String userId);

// 保存用户跨会话长期角色记忆（图执行完成时，将最终角色记忆写入）
void saveCrossSessionMemory(String userId, Message memory);
```

`RedisShortTermMemoryRepository` 实现：对应 `user:{userId}:short_term_memory` key。

**保存时机**：图执行到 reporter 节点完成后（与审计文件导出同一 lifecycle 回调），将当前会话最终的用户角色记忆写入 `cross_session_memory` key。

---

## 四、配置项变更

在 `application.yml` 新增配置段：

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        memory:
          redis:
            chat-messages-ttl-days: 30        # 聊天消息 TTL（天）
            role-track-ttl-days: 30           # 角色提取轨迹 TTL
            cross-session-memory-ttl-days: 180 # 跨会话记忆 TTL
          audit:
            enabled: true                      # 是否开启 OverAllState 审计文件
            base-path: ${AI_DEEPRESEARCH_EXPORT_PATH:/tmp/deepresearch}/audit
```

新增配置类 `RedisMemoryProperties`（嵌套在 `DeepResearchProperties` 或独立 `@ConfigurationProperties`）：

```java
@ConfigurationProperties(prefix = "spring.ai.alibaba.deepresearch.memory")
public class MemoryPersistenceProperties {
    private RedisMemoryConfig redis = new RedisMemoryConfig();
    private AuditConfig audit = new AuditConfig();

    // getters/setters...

    public static class RedisMemoryConfig {
        private int chatMessagesTtlDays = 30;
        private int roleTrackTtlDays = 30;
        private int crossSessionMemoryTtlDays = 180;
    }

    public static class AuditConfig {
        private boolean enabled = true;
        private String basePath = "/tmp/deepresearch/audit";
    }
}
```

---

## 五、关键约束与注意事项

1. **Redis 降级**：所有 Redis 相关 bean 均通过 `@ConditionalOnProperty(name = "spring.data.redis.enabled", havingValue = "true")` 控制。Redis 禁用时，`MemoryConfig` 自动注入内存实现，行为与当前完全一致。

2. **userId 硬编码**：当前 `ShortUserRoleMemoryNode` 中 `USER_ID = "MOCK_USER_ID"` 不在本次修改范围内。新的 Redis 实现仍使用此 mock userId，但 key 结构已按 userId 隔离，后续接入真实认证时只需修改 `USER_ID` 的来源。

3. **Message 序列化**：Spring AI `Message` 接口没有默认的 Jackson 反序列化支持，需实现自定义序列化器。重点处理 `UserMessage`、`AssistantMessage`、`SystemMessage` 三种类型，metadata 中的 `LocalDateTime` 需要 `JavaTimeModule`。

4. **`ShortUserRoleExtractInMemory` 的 `@Component` 冲突**：目前该类有 `@Component` 注解，Redis 启用时会同时注入两个 `ShortTermMemoryRepository` 实现，导致 Spring 报 bean 冲突。需将 `@Component` 改为 `@ConditionalOnMissingBean(ShortTermMemoryRepository.class)` 或去掉 `@Component` 改为在配置类中显式注册。

5. **`TemplateUtil.getShortMemoryExtractMessage()` 参数扩展**：该方法目前接受 `lastUserMessage` 和 `historyUserMessages` 两个参数，需增加 `crossSessionMemory` 参数并更新 prompt 模板的变量绑定。

6. **`AuditExportService` 的线程安全**：`reporter` 节点可能并发执行（多个 session），文件写入需确保路径唯一（按 sessionId + timestamp），无需加锁。

7. **`SessionController` 的用户鉴权**：当前接口设计为传入 `userId` query 参数，未做认证校验。生产环境需要从 token/session 中提取 userId，本次设计保持开放，便于后续集成。

---

## 六、验证步骤

1. 启动 Redis，将 `spring.data.redis.enabled=true`，启动应用
2. 发起对话（`POST /chat/stream`），成功响应后：
   - 检查 `GET /session/list?userId=MOCK_USER_ID` 是否返回会话记录
   - 用 `redis-cli LRANGE deepresearch:chat:{conversationId}:messages 0 -1` 验证消息已存储
3. 重启应用，再次调用 `GET /session/list`，确认会话记录仍在
4. 重新加载该 session，发起后续对话，验证 AI 回复中体现了历史角色偏好（`short_user_role_memory` 非空）
5. 等待图执行到 reporter 节点完成，检查 `{audit.base-path}/{sessionId}/` 下是否生成 JSON 文件
6. 将 `spring.data.redis.enabled=false` 重启，确认应用正常运行，历史功能不可用但不报错
7. 运行 `mvn test -DskipTests=false` 确认现有测试无回归

---

## 七、不在本次范围

- RAG Milvus + ES 双通道（独立任务）
- 前端历史会话列表 UI（API 就绪后单独实现）
- 图 Checkpoint 持久化（`MemorySaver` → Redis，当前不需要跨重启恢复执行中任务）
- 多用户认证集成（userId 仍使用 mock）
