# GraphProcess.java 详细解析

> 文件路径：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/controller/graph/GraphProcess.java`

---

## 一、类的定位与职责

`GraphProcess` 是图执行的核心调度器，不是 Spring Bean，而是由 `ChatController` 手动实例化后使用。

它负责三件事：

1. **为每次请求分配唯一的图 ID（GraphId）**
2. **将图节点产生的输出流转换为 SSE 事件推送给前端**
3. **管理正在运行的图任务，支持主动终止**

---

## 二、字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionCountMap` | `ConcurrentHashMap<String, Integer>` | 记录每个 sessionId 累计发起的请求次数，用于生成不重复的 threadId |
| `graphTaskFutureMap` | `ConcurrentHashMap<GraphId, Future<?>>` | 存放正在运行的图任务 Future，key 是 GraphId，用于支持取消操作 |
| `executor` | `ExecutorService`（固定10线程池） | 执行图流处理的后台线程池，线程数必须 > 2（图内部也会提交子任务） |
| `compiledGraph` | `CompiledGraph` | Spring AI Alibaba 编译后的状态图，所有图操作都通过它发起 |
| `OBJECT_MAPPER` | `ObjectMapper` | 全局静态 Jackson 序列化工具 |
| `TASK_STOPPED_MESSAGE_TEMPLATE` | `String` 常量 | 任务被终止时推送给前端的 JSON 模板，包含 `__END__` 节点名和终止原因 |

---

## 三、构造方法

```java
public GraphProcess(CompiledGraph compiledGraph)
```

**参数：**
- `compiledGraph`：已编译的状态图实例，由 Spring 容器管理，从外部注入。

**作用：**
保存编译好的图引用，GraphProcess 本身的生命周期与 Controller 相同。

---

## 四、方法逐一解析

### 4.1 `createNewGraphId`

```java
public GraphId createNewGraphId(String sessionId)
```

**参数：**
- `sessionId`：前端传入的会话 ID，标识同一个用户的对话会话。

**返回值：**
- `GraphId`：包含 `sessionId` 和 `threadId` 的记录类。`threadId` 格式为 `"{sessionId}-{count}"`，例如 `"abc123-3"` 表示该会话第3次请求。

**作用：**
为本次请求分配唯一 ID。通过 `ConcurrentHashMap.merge` 原子地对计数器自增，保证并发场景下 threadId 不重复。

**数据流：**
```
sessionId → merge(+1) → count → GraphId(sessionId, "{sessionId}-{count}")
```

---

### 4.2 `handleHumanFeedback`

```java
public void handleHumanFeedback(
    GraphId graphId,
    ChatRequest chatRequest,
    Map<String, Object> objectMap,
    RunnableConfig runnableConfig,
    Sinks.Many<ServerSentEvent<String>> sink
) throws GraphRunnerException
```

**参数：**
- `graphId`：当前图执行的唯一 ID，用于日志和 SSE 消息标记。
- `chatRequest`：前端的请求体，这里主要读取 `interruptFeedback` 字段（用户对计划的反馈文字）。
- `objectMap`：将要写入图状态的数据 Map，方法会向其中追加 `"feedback"` 键。
- `runnableConfig`：图运行配置，包含 `threadId`，用于定位之前被中断的图快照（checkpoint）。
- `sink`：Reactor SSE 发射器，图继续执行后的输出通过它推送给前端。

**返回值：** `void`

**作用：**
处理人工审核节点（`human_feedback`）被中断后用户提交的反馈。流程：
1. 把用户的反馈文字写入 `objectMap`。
2. 从 checkpoint 恢复被中断的图状态快照（`compiledGraph.getState`）。
3. 标记状态为"恢复执行"（`withResume`）并注入人工反馈内容（目标节点为 `research_team`）。
4. 调用 `fluxStreamFromInitialNode` 从断点继续流式执行。
5. 将新的输出流交给 `processStream` 处理并推送 SSE。

**数据流：**
```
chatRequest.interruptFeedback()
    → objectMap["feedback"]
    → compiledGraph.getState(runnableConfig) → StateSnapshot → OverAllState
    → state.withResume() + state.withHumanFeedback(objectMap, "research_team")
    → compiledGraph.fluxStreamFromInitialNode(state, runnableConfig)
    → Flux<NodeOutput>
    → processStream(graphId, flux, sink)
    → sink (SSE 推送给前端)
```

---

### 4.3 `processStream`（Flux 版本，当前主用版本）

```java
public void processStream(
    GraphId graphId,
    Flux<NodeOutput> generator,
    Sinks.Many<ServerSentEvent<String>> sink
)
```

**参数：**
- `graphId`：图实例 ID，用于 SSE 消息中标记来源，以及在 `graphTaskFutureMap` 中管理任务。
- `generator`：`Flux<NodeOutput>`，Spring AI Alibaba 图执行产生的响应式流，每个元素是一个节点的输出。
- `sink`：Reactor SSE 发射器，向 HTTP 长连接推送事件。

**返回值：** `void`（异步执行，立即返回）

**作用：**
将图的节点输出流（`Flux<NodeOutput>`）异步转换为 SSE 事件并推送给前端。每个 `NodeOutput` 有两种类型：
- `StreamingOutput`（LLM 流式 token）→ 调用 `buildLLMNodeContent` 构建内容
- 普通 `NodeOutput`（非流式节点结果）→ 调用 `buildNormalNodeContent` 构建内容

任务 `Future` 存入 `graphTaskFutureMap`，以便后续可被 `stopGraph` 取消。

**数据流：**
```
Flux<NodeOutput>
    ├─ 每个 StreamingOutput → buildLLMNodeContent → JSON字符串 → sink.tryEmitNext(SSE)
    ├─ 每个普通 NodeOutput  → buildNormalNodeContent → JSON字符串 → sink.tryEmitNext(SSE)
    ├─ doOnComplete → sink.tryEmitComplete() + 从Map移除
    └─ doOnError   → sink.tryEmitNext(错误SSE) + sink.tryEmitError()
```

---

### 4.4 `processStream`（AsyncGenerator 版本，已废弃）

```java
@Deprecated
public void processStream(
    GraphId graphId,
    AsyncGenerator<NodeOutput> generator,
    Sinks.Many<ServerSentEvent<String>> sink
)
```

**参数：** 与 Flux 版本相同，只是 `generator` 类型为 `AsyncGenerator<NodeOutput>`（旧版阻塞式迭代器）。

**作用：**
旧版实现，用 `while(true)` 循环阻塞迭代图输出。逻辑与 Flux 版本相同，但通过手动检查 `Thread.currentThread().isInterrupted()` 来支持中断。已被 Reactor Flux 版本取代，标注 `@Deprecated`，保留供向后兼容。

**错误处理细节：**
- `CancellationException / ExecutionException` → 推送"服务异常"并关闭 sink
- `InterruptedException` → 推送"用户终止"并正常关闭 sink（`tryEmitComplete`，不报错）
- `next.isError()` → 提取节点执行异常，推送具体错误信息并移除 Map 中的任务，防止重试

---

### 4.5 `stopGraph`

```java
public boolean stopGraph(GraphId graphId)
```

**参数：**
- `graphId`：要终止的图实例 ID。

**返回值：**
- `false`：该 graphId 对应的任务不存在（已完成或从未启动）
- `true`：任务已完成或成功发出取消信号

**作用：**
从 `graphTaskFutureMap` 中取出对应 `Future` 并调用 `future.cancel(true)`。`true` 参数表示如果线程正在阻塞等待，则发送中断信号（`InterruptedException`）。在旧版 `AsyncGenerator` 实现中，中断信号会被 `catch(InterruptedException)` 捕获并推送"用户终止"消息；在 Flux 版本中，中断由 Reactor 的调度器处理。

**数据流：**
```
graphId → graphTaskFutureMap.remove(graphId) → future.cancel(true) → 线程收到中断信号
```

---

### 4.6 `safeObjectToJson`（私有）

```java
private String safeObjectToJson(Object object)
```

**参数：**
- `object`：任意对象。

**返回值：**
- 序列化后的 JSON 字符串；序列化失败时返回 `"{}"`，不抛出异常。

**作用：**
Jackson 序列化的安全包装，避免在日志/推送路径上因序列化失败引发异常链式崩溃。

---

### 4.7 `buildLLMNodeContent`（私有）

```java
private String buildLLMNodeContent(
    String nodeName,
    GraphId graphId,
    StreamingOutput streamingOutput,
    NodeOutput output
)
```

**参数：**
- `nodeName`：当前输出的节点名，例如 `"reporter_llm_stream"`。
- `graphId`：图实例 ID，写入响应供前端路由。
- `streamingOutput`：LLM 流式输出，包含 `chunk`（单个 token 文本）和完整 `chatResponse`。
- `output`：节点完整输出，用于读取状态中的 `{nodeName}_step_title`。

**返回值：**
- JSON 字符串，包含 `{nodeName, step_title, visible, finishReason, graphId}`；若节点名不在 `StreamNodePrefixEnum` 中则返回空字符串（不推送）。

**作用：**
将 LLM 流式 token 封装成前端可消费的 JSON 格式。关键逻辑：
1. 用 `StreamNodePrefixEnum.match(nodeName)` 判断是否是需要处理的流式节点（前缀匹配）。
2. 从状态中取 `{nodeName}_step_title` 作为展示标题。
3. `visible` 字段由枚举配置决定：`planner_llm_stream` 是 `false`（不展示给用户），其余均为 `true`。
4. `chunk` 优先，`chunk == null` 时降级读 `chatResponse` 的完整文本（兼容非流式场景）。

**数据流：**
```
nodeName → StreamNodePrefixEnum.match() → prefixEnum
streamingOutput.chunk() or chatResponse.getText() → textContent
output.state().value(nodeName + "_step_title") → stepTitle
chatResponse.getResult().getMetadata().getFinishReason() → finishReason
→ Map{nodeName→textContent, step_title, visible, finishReason, graphId}
→ JSON字符串
```

---

### 4.8 `buildNormalNodeContent`（私有）

```java
private String buildNormalNodeContent(
    GraphId graphId,
    String nodeName,
    NodeOutput output
)
```

**参数：**
- `graphId`：图实例 ID，写入响应供前端路由。
- `nodeName`：当前节点名，例如 `"coordinator"`、`"planner"`。
- `output`：节点完整输出，包含该节点执行后的 `OverAllState` 状态数据。

**返回值：**
- JSON 字符串（`NodeResponse` 序列化结果），包含 `{nodeName, graphId, displayTitle, content, siteInformation}`；若节点不在 `NodeNameEnum` 中、`displayTitle` 为空、且 `content` 和 `siteInformation` 均为空，则返回空字符串（不推送）。

**作用：**
将非 LLM 流式节点的输出封装为前端事件。不同节点推送的 `content` 字段内容不同：

| 节点 | content 内容 |
|------|-------------|
| `__START__` | `{"query": "用户提问"}` |
| `coordinator` | 状态中的 `deep_research` 字段（是否深度调研的决策） |
| `rewrite_multi_query` / `human_feedback` / `__END__` | 完整的 `state.data()` Map |
| `planner` | 状态中的 `planner_content`（研究计划文本） |
| `research_team` | `Boolean`：`researchTeamContent` 是否等于 `"reporter"`（表示是否流程推进到报告节点） |
| `reporter` | 状态中的 `final_report`（最终报告内容） |
| 其他 | 空字符串（不推送） |

**数据流：**
```
nodeName → NodeNameEnum.fromNodeName() → nodeEnum
output.state().data() → 按 switch 分支提取对应字段 → content
output.state().value("site_information") → siteInformation
nodeEnum.displayTitle() → displayTitle
→ NodeResponse(nodeName, graphId, displayTitle, content, siteInformation)
→ JSON字符串
```

---

## 五、整体数据流图

```
前端 POST /chat/stream
        │
        ▼
ChatController
  ├─ createNewGraphId(sessionId) → GraphId(sessionId, threadId)
  ├─ compiledGraph.fluxStream(inputs, runnableConfig) → Flux<NodeOutput>
  └─ processStream(graphId, flux, sink)
              │
              ▼
        executor.submit(异步任务)
              │
        Flux<NodeOutput>.subscribe()
              │
        ┌─────┴─────────────────────────┐
        │ output instanceof              │
        │ StreamingOutput?               │ 普通 NodeOutput
        ▼                               ▼
  buildLLMNodeContent()        buildNormalNodeContent()
    - 前缀匹配判断是否处理          - NodeNameEnum 枚举匹配
    - 提取 chunk/text               - switch 分支提取内容
    - 提取 stepTitle/finishReason   - 提取 site_information
    → JSON                          → NodeResponse JSON
              │
              ▼
    sink.tryEmitNext(ServerSentEvent)
              │
              ▼
        前端 SSE 消费
```

---

## 六、终止流程

```
前端 POST /chat/stop
        │
        ▼
ChatController.stopGraph(graphId)
        │
        ▼
GraphProcess.stopGraph(graphId)
        │
        ├─ graphTaskFutureMap.remove(graphId) → Future<?>
        └─ future.cancel(true)
                │
                ▼
        (旧版) 线程 InterruptedException → 推送"用户终止" SSE → sink.tryEmitComplete()
        (新版) Reactor 调度器感知中断   → doOnError 触发    → sink.tryEmitError()
```

---

## 七、关键设计点

1. **线程池大小 > 2 的原因**：`processStream` 在线程池中提交任务，旧版 `AsyncGenerator` 实现内部又会向同一线程池提交 `generator::next` 子任务，如果线程池只有1或2个线程，会死锁（主任务等待子任务，子任务等不到线程）。

2. **GraphId 作为 Map 的 Key**：`GraphId` 是 Java `record`，自动生成基于 `sessionId + threadId` 的 `equals/hashCode`，可安全作为 `ConcurrentHashMap` 的 Key。

3. **`sink.tryEmitNext` vs `sink.emitNext`**：使用 `try` 版本是非阻塞的，不会因为下游背压或并发冲突抛出异常，适合在回调/订阅内部使用。

4. **`StreamNodePrefixEnum.visible = false` 的意义**：`planner_llm_stream` 节点的 LLM 输出不希望直接显示给用户（可能是内部推理过程），通过 `visible` 字段让前端决定是否渲染。
