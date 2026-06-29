6# DeepResearch MCP 模块详解

## 1. 什么是 MCP

MCP（Model Context Protocol）是 Anthropic 提出的开放协议，允许 AI 模型（LLM）在运行时动态调用外部工具/服务。在本项目中，MCP Server 以 HTTP + SSE 方式暴露工具列表，Spring AI 的 MCP 客户端连接后，可将这些工具注入给 ChatClient，让 Agent 在推理过程中自动调用。

---

## 2. 整体架构

```
请求入口
  │
  ▼
ChatRequest.mcpSettings（前端传入的运行时 MCP 配置）
  │
  ▼
OverAllState（贯穿整个图执行的状态容器）
  │
  ├─ McpAssignNodeConfiguration.agent2mcpConfigWithRuntime
  │     ↑ 静态配置（mcp-config.json）+ 动态配置（mcp_settings）合并
  │
  ▼
McpProviderFactory.createProvider(state, agentName)
  │
  ├─ McpClientUtil.createMcpProvider(...)
  │     ├─ 读取 agent 对应的 Server 列表
  │     ├─ McpConfigMergeUtil.createAgent2McpTransports(...)  → WebFluxSseClientTransport
  │     ├─ McpClient.async(...).build()
  │     └─ client.initialize().block(2min)  ← MCP 握手
  │
  ▼
AsyncMcpToolCallbackProvider（包含所有可调用 Tool）
  │
  ▼
requestSpec.toolCallbacks(mcpProvider.getToolCallbacks())  → ChatClient 执行
```

---

## 3. 文件说明

### 3.1 配置文件 `mcp-config.json`

**路径**：`src/main/resources/mcp-config.json`

```json
{
  "researchAgent": {
    "mcp-servers": [
      {
        "url": "https://mcp.amap.com?key=${AMAP_API_KEY}",
        "sse-endpoint": "/sse",
        "description": "高德地图服务",
        "enabled": false
      }
    ]
  }
}
```

- 顶层 Key 是 **agentName**，与代码中 `mcpFactory.createProvider(state, "researchAgent")` 对应。
- `enabled: false` 表示该 Server 被禁用，客户端不会连接。
- `sse-endpoint` 可选，默认 `/sse`。
- `${AMAP_API_KEY}` 是环境变量占位符，需要在启动时注入。

---

### 3.2 `McpAssignNodeProperties`

**路径**：`config/McpAssignNodeProperties.java`

绑定 `application.yml` 中 `spring.ai.alibaba.deepresearch.mcp.*` 前缀的配置。

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 是否启用整个 MCP 模块 |
| `configLocation` | `classpath:mcp-config.json` | 配置文件路径，可替换为外部文件 |

内嵌两个 Record：
- `McpServerConfig`：对应 JSON 中单个 agent 的 `{ "mcp-servers": [...] }` 结构。
- `McpServerInfo`：单个 Server 的连接参数（url、sseEndpoint、description、enabled）。

在 `application.yml` 中控制 MCP 功能：
```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        mcp:
          enabled: false              # 关闭整个 MCP 模块
          config-location: classpath:mcp-config.json
```

---

### 3.3 `McpAssignNodeConfiguration`

**路径**：`agents/McpAssignNodeConfiguration.java`

`@ConditionalOnProperty(mcp.enabled=true)` 才会生效。向容器注册两个 Bean：

#### Bean 1: `agent2mcpConfig`（静态配置）

```java
@Bean(name = "agent2mcpConfig")
public Map<String, McpServerConfig> agent2mcpConfig()
```

启动时读取 `mcp-config.json`，返回 `agentName -> McpServerConfig` 的 Map，全局共享。

#### Bean 2: `agent2mcpConfigWithRuntime`（动态配置 Function）

```java
@Bean(name = "agent2mcpConfigWithRuntime")
public Function<OverAllState, Map<String, McpServerConfig>> agent2mcpConfigWithRuntime(...)
```

每次节点执行时调用，将静态配置与 `OverAllState` 中的 `mcp_settings`（来自前端请求）合并，实现**每次请求可动态注入不同 MCP Server**。

---

### 3.4 `McpConfigMergeUtil`

**路径**：`util/mcp/McpConfigMergeUtil.java`

纯静态工具类，提供三个方法：

#### `mergeAgent2McpConfigs`

合并静态 Map 和运行时 Map。运行时配置的 Server 以 URL 为 key 覆盖静态配置，新 URL 则追加。

```
staticConfig:  { researchAgent: [serverA] }
runtimeConfig: { researchAgent: [serverB] }  // serverB.url != serverA.url
结果:           { researchAgent: [serverA, serverB] }
```

#### `mergeAgent2McpServers`

合并两个 Server 列表，使用 `LinkedHashMap` 以 URL 为 key 去重，保持顺序稳定。

#### `createAgent2McpTransports`

将 `McpServerConfig` 中启用的 Server 转换为 `WebFluxSseClientTransport`（反应式 SSE 客户端）。Transport 名称格式：`{agentName}-{url.hashCode()}`。

---

### 3.5 `McpClientUtil`

**路径**：`util/mcp/McpClientUtil.java`

核心初始化逻辑，被 `McpProviderFactory` 调用：

```
1. mcpConfigProvider.apply(state)      → 获取合并后的配置
2. config.mcpServers() 过滤 enabled    → 只处理启用的 Server
3. createAgent2McpTransports(...)      → 建立 SSE Transport
4. McpClient.async(transport).build() → 构建异步客户端
5. client.initialize().block(2min)    → MCP 握手（同步等待，超时 2 分钟）
6. new AsyncMcpToolCallbackProvider(clients) → 封装所有客户端
```

**注意**：`initialize().block()` 是阻塞调用，会占用线程池线程，如果 MCP Server 响应慢会导致节点延迟。

---

### 3.6 `McpProviderFactory`

**路径**：`service/McpProviderFactory.java`

依赖注入门面，隔离节点对底层 MCP 基础设施的感知。节点只需持有此工厂：

```java
// CoderNode / ResearcherNode 中的用法
AsyncMcpToolCallbackProvider mcpProvider = mcpFactory != null
    ? mcpFactory.createProvider(state, "coderAgent") : null;
if (mcpProvider != null) {
    requestSpec = requestSpec.toolCallbacks(mcpProvider.getToolCallbacks());
}
```

`mcpFactory` 为 null 表示 MCP 功能未启用（`@ConditionalOnProperty` 条件不满足），节点会跳过 MCP 工具注入，退化为纯 LLM 调用。

---

### 3.7 `McpController`

**路径**：`controller/McpController.java`

对外暴露 REST 接口，用于查询系统中配置了哪些 MCP Server。

```
GET /api/mcp/services
```

响应示例：
```json
{
  "success": true,
  "message": "成功获取默认 MCP 服务信息",
  "data": [...],
  "summary": {
    "totalServices": 1,
    "enabledServices": 0,
    "disabledServices": 1,
    "availableServices": []
  }
}
```

---

### 3.8 `McpService`

**路径**：`service/McpService.java`

配合 `McpController`，从 `mcp-config.json` 读取服务信息并转换为 DTO（`McpServerInfo`）。与运行时客户端创建无关，仅用于管理页面展示。

---

### 3.9 `McpServerInfo`（DTO）

**路径**：`model/dto/McpServerInfo.java`

用于 API 响应的数据对象，包含：

| 字段 | 说明 |
|------|------|
| `agentName` | agent 内部名称（如 `researchAgent`） |
| `agentDisplayName` | 中文展示名（通过静态方法映射） |
| `url` | Server 地址 |
| `description` | 描述 |
| `enabled` | 是否启用 |
| `serviceName` | 从 URL 推断的服务名（如"高德地图服务"） |

---

## 4. 数据流：运行时动态配置

前端可以在请求体中通过 `mcp_settings` 字段追加或覆盖 MCP Server：

```json
POST /chat/stream
{
  "query": "...",
  "mcp_settings": {
    "researchAgent": {
      "mcp-servers": [
        {
          "url": "http://my-custom-mcp-server",
          "sse-endpoint": "/sse",
          "description": "自定义服务",
          "enabled": true
        }
      ]
    }
  }
}
```

流转路径：
```
ChatRequest.mcpSettings
  → OverAllState["mcp_settings"]
  → agent2mcpConfigWithRuntime.apply(state)
  → McpConfigMergeUtil.mergeAgent2McpConfigs(staticConfig, runtimeSettings)
  → McpClientUtil.createMcpProvider(...)
  → MCP 客户端连接并握手
```

---

## 5. 使用 MCP 的节点

目前有两个节点使用了 MCP：

| 节点 | agentName | 用途 |
|------|-----------|------|
| `ResearcherNode` | `researchAgent` | 研究类任务，可调用地图、搜索等外部工具 |
| `CoderNode` | `coderAgent` | 编程类任务，可调用代码执行、文件操作等工具 |

两个节点的使用模式相同：创建 Provider → 若非 null 则注入 toolCallbacks → 发起流式 LLM 调用。

---

## 6. 如何接入新的 MCP Server

1. **在 `mcp-config.json` 中添加配置**：

```json
{
  "researchAgent": {
    "mcp-servers": [
      {
        "url": "http://your-mcp-server:8080",
        "sse-endpoint": "/sse",
        "description": "你的服务描述",
        "enabled": true
      }
    ]
  }
}
```

2. **确保 application.yml 开启 MCP**：

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        mcp:
          enabled: true
    mcp:
      client:
        enabled: true
        type: ASYNC
```

3. **（可选）在 `McpServerInfo.extractServiceName` 中添加服务识别逻辑**，使 `/api/mcp/services` 接口能显示正确的服务名称。

4. 若要给新的 Agent 节点加 MCP 支持，注入 `McpProviderFactory` 并调用 `createProvider(state, "yourAgentName")`，agentName 需与 `mcp-config.json` 中的 key 一致。

---

## 7. 关键依赖

| 依赖 | 说明 |
|------|------|
| `spring-ai-mcp-client-spring-boot-autoconfigure` | Spring AI MCP 客户端自动配置 |
| `io.modelcontextprotocol.client.McpAsyncClient` | MCP 协议异步客户端实现 |
| `WebFluxSseClientTransport` | 基于 WebFlux 的 SSE 传输层 |
| `AsyncMcpToolCallbackProvider` | 将 MCP Tools 适配为 Spring AI ToolCallback |

---

## 8. HTTP+SSE 与 stdio 通信方式对比

本项目使用的是 **HTTP+SSE** 方式（`McpConfigMergeUtil.java:130` 中的 `WebFluxSseClientTransport`）。MCP 协议官方支持两种传输方式，区别如下：

### 8.1 通信模型

**HTTP+SSE**

```
客户端（本项目）                    MCP Server（远程进程）
     │                                     │
     │── GET /sse ─────────────────────────▶ 建立 SSE 长连接（Server 推送通道）
     │◀─ event: endpoint ──────────────────│ Server 返回 POST 回调地址
     │                                     │
     │── POST /message (JSON-RPC) ─────────▶ 客户端发送请求（initialize / tools/call）
     │◀─ SSE event (JSON-RPC response) ───│ Server 通过 SSE 推送响应
     │                                     │
     │  （长连接保持，双向通信）
```

**stdio**

```
父进程（本项目）                    子进程（MCP Server 可执行文件）
     │                                     │
     │── spawn("npx mcp-server-xxx") ─────▶ 启动子进程
     │                                     │
     │── stdin (JSON-RPC request) ─────────▶
     │◀─ stdout (JSON-RPC response) ───────│
     │                                     │
     │  （进程生命周期绑定，通过标准流通信）
```

---

### 8.2 核心差异对比

| 维度 | HTTP+SSE | stdio |
|------|----------|-------|
| **部署位置** | MCP Server 是独立进程/服务，可远程部署 | MCP Server 是本地可执行文件，由客户端 spawn |
| **网络要求** | 需要 HTTP 可达（可跨机器、跨网络） | 无网络要求，进程间通信 |
| **启动方式** | 客户端主动建立 SSE 长连接 | 客户端 fork 子进程，拿到其 stdin/stdout |
| **生命周期** | Server 独立运行，客户端连接/断开不影响 Server | Server 随父进程退出而终止 |
| **并发能力** | 多个客户端可同时连接同一 Server | Server 实例与客户端一一绑定 |
| **传输协议** | HTTP/1.1（SSE 推送 + POST 请求） | 操作系统管道（pipe） |
| **序列化** | JSON over HTTP | JSON over 标准流 |
| **认证** | 可通过 HTTP Header / URL 参数（如 `?key=xxx`） | 依赖操作系统权限，无 HTTP 层认证 |
| **适用场景** | 远程工具服务（高德地图、数据库、SaaS API） | 本地工具（文件系统、代码执行、本地命令） |
| **典型代表** | 本项目的 `WebFluxSseClientTransport` | Claude Desktop 连接本地 MCP Server 的方式 |

---

### 8.3 本项目选择 HTTP+SSE 的原因

1. **面向远程服务**：`mcp-config.json` 中配置的是 `https://mcp.amap.com` 这类公网地址，必须通过 HTTP 访问。
2. **运行时动态注入**：前端可以通过 `mcp_settings` 在请求时传入任意 URL，动态连接不同的远程 MCP Server，stdio 无法支持这种场景。
3. **多租户隔离**：每个图执行线程可以连接不同的 Server 集合，HTTP 连接天然支持并发隔离；stdio 方案需要为每个请求 fork 子进程，开销更大。
4. **Spring AI 生态**：`WebFluxSseClientTransport` 是 Spring AI MCP 客户端库的标准实现，与项目已有的 WebFlux 技术栈一致。

---

## 9. 面试 Q&A

### 基础概念

**Q：什么是 MCP？它解决了什么问题？**

A：MCP（Model Context Protocol）是 Anthropic 提出的开放协议，定义了 LLM 与外部工具/服务之间的标准通信格式。它解决的核心问题是：不同 AI 框架与工具之间的集成碎片化——原来每接一个工具都要写定制适配代码，MCP 统一了"工具描述、调用请求、响应格式"，让任何兼容 MCP 的 Client 都能调用任何兼容 MCP 的 Server，类似工具层的 HTTP 标准。

---

**Q：MCP 的工具调用流程是什么？LLM 是怎么"知道"有哪些工具可用的？**

A：流程分三个阶段：

1. **握手阶段**：客户端连接 MCP Server 后调用 `initialize`，Server 返回自身支持的协议版本和能力。
2. **工具发现阶段**：客户端调用 `tools/list`，Server 返回所有可用工具的 JSON Schema（名称、描述、入参定义）。这份 Schema 被 `AsyncMcpToolCallbackProvider` 封装成 `ToolCallback[]`，通过 `requestSpec.toolCallbacks(...)` 注入给 ChatClient。ChatClient 在构造请求时，会把工具 Schema 塞进发给 LLM 的系统消息里。
3. **调用阶段**：LLM 推理时如果认为需要某个工具，会在响应中输出 `tool_use` 块（包含工具名和参数）。Spring AI 拦截到后，调用对应 MCP Server 的 `tools/call`，拿到结果后再把结果追加到对话上下文，继续让 LLM 推理，直到 LLM 输出最终文本答案。

---

### 架构设计

**Q：为什么要设计 `McpProviderFactory` 这一层？直接在节点里调 `McpClientUtil` 不行吗？**

A：可以跑通，但 `McpProviderFactory` 解决了两个问题：

1. **依赖隔离**：`McpClientUtil` 需要 `McpAsyncClientConfigurer`、`McpClientCommonProperties`、`WebClient.Builder`、`ObjectMapper` 四个底层依赖。如果每个节点都直接持有这些依赖，一旦底层基础设施变化，所有节点都要改。Factory 做了门面，节点只依赖 Factory 一个对象。
2. **条件装配**：Factory 本身带 `@ConditionalOnProperty(mcp.enabled=true)`，MCP 未启用时 Bean 不注册，节点里注入的 `mcpFactory` 自然为 null。节点只需判断 `mcpFactory != null` 就能自动降级，不需要在每个节点里重复写条件判断逻辑。

---

**Q：静态配置和运行时配置是怎么合并的？合并规则是什么？**

A：合并发生在 `McpConfigMergeUtil.mergeAgent2McpConfigs()` 里，规则是**以 URL 为 key，动态配置覆盖静态配置，新 URL 追加**：

```java
// McpConfigMergeUtil.java
Map<String, McpServerInfo> serverMap = new LinkedHashMap<>();
for (McpServerInfo server : staticServers)  serverMap.put(server.url(), server);
for (McpServerInfo server : dynamicServers) serverMap.put(server.url(), server); // 覆盖或追加
```

场景举例：静态配置了高德地图（disabled），前端请求时传入同一 URL 但 `enabled=true`，合并后该 Server 变为启用状态——这让前端可以在不重启服务的情况下按需激活工具。`agent2mcpConfigWithRuntime` 这个 Function Bean 是合并逻辑的入口，每次节点执行时调用，拿到当前请求的实时配置。

---

**Q：`client.initialize().block()` 是同步阻塞调用，在响应式编程栈里这样做有什么风险？**

A：风险是**阻塞 Reactor 调度线程**。项目的图执行是基于 WebFlux 的响应式流，Reactor 的调度线程（如 `parallel-N`）不允许被阻塞，否则会触发 `BlockingOperationError` 或导致线程池耗尽，进而影响其他请求的响应。

代码里的 `block(Duration.ofMinutes(2))` 加了超时保护，但仍建议在 `Schedulers.boundedElastic()` 上执行阻塞操作：

```java
// 更安全的写法
Mono.fromCallable(() -> client.initialize().block(Duration.ofMinutes(2)))
    .subscribeOn(Schedulers.boundedElastic())
    .block();
```

这是当前实现的一个潜在优化点，在 MCP Server 响应慢时会暴露问题。

---

### 可靠性与边界

**Q：如果某个 MCP Server 连接失败，会影响整个请求吗？**

A：不会。`McpClientUtil` 里对每个 Server 的连接是独立 try-catch，单个 Server 失败只记 error 日志，不抛出异常，其他 Server 的客户端照常创建：

```java
// McpClientUtil.java 逻辑等效
for (McpServerInfo serverInfo : config.mcpServers()) {
    try {
        // 建立连接 + initialize
        mcpAsyncClients.add(client);
    } catch (Exception e) {
        logger.error("Failed to create MCP client for {}", serverInfo.url(), e);
        // 继续处理下一个 Server
    }
}
```

如果所有 Server 都连接失败，`mcpAsyncClients` 为空，方法返回 null，节点降级为纯 LLM 调用，不报错。

---

**Q：每次节点执行都会重新握手 MCP Server，这不会有性能问题吗？**

A：是的，这是当前架构的一个取舍点。每次图执行都会为该节点重新创建客户端并完成 initialize 握手，会产生额外的 HTTP 往返延迟，在工具数量多或 Server 延迟高时影响明显。

这样设计的原因是**支持运行时动态配置**：每次请求的 `mcp_settings` 可能不同，必须每次都重新初始化才能拿到当前请求的工具集。如果工具集是固定的，可以在应用启动时一次性初始化并复用客户端，性能会好很多。这是"灵活性 vs 性能"的权衡。

---

### 扩展与实践

**Q：如果要给一个新节点（比如 `AnalystNode`）加 MCP 支持，需要改哪些地方？**

A：只需两步：

1. **`mcp-config.json` 加配置**，key 与 agentName 一致：
   ```json
   {
     "analystAgent": {
       "mcp-servers": [{ "url": "http://...", "enabled": true }]
     }
   }
   ```

2. **节点里注入 Factory 并调用**：
   ```java
   // AnalystNode 构造函数注入 McpProviderFactory mcpFactory
   AsyncMcpToolCallbackProvider mcpProvider = mcpFactory != null
       ? mcpFactory.createProvider(state, "analystAgent") : null;
   if (mcpProvider != null) {
       requestSpec = requestSpec.toolCallbacks(mcpProvider.getToolCallbacks());
   }
   ```

不需要改 Factory、不需要改工具类、不需要改配置解析逻辑，扩展成本极低。

---

**Q：前端怎么知道有哪些 MCP Server 可用？**

A：通过 `GET /api/mcp/services` 接口查询。`McpController` 调用 `McpService` 读取 `mcp-config.json`，将所有 Server 的元信息（agentName、URL、描述、是否启用）转换为 `McpServerInfo` DTO 返回给前端。这个接口只读静态配置，与运行时客户端创建无关，是纯展示用途，用于管理页面渲染可用的工具列表。

---

## 10. 完整数据流示例

**场景**：用户在前端提问"帮我查一下北京天安门附近的停车场"，并通过 `mcp_settings` 动态注入高德地图 MCP Server，触发 ResearcherNode 调用地图工具。

---

### 第 1 步：前端发起请求

```http
POST /chat/stream
Content-Type: application/json

{
  "query": "帮我查一下北京天安门附近的停车场",
  "session_id": "session-abc",
  "mcp_settings": {
    "researchAgent": {
      "mcp-servers": [
        {
          "url": "https://mcp.amap.com?key=YOUR_AMAP_KEY",
          "sse-endpoint": "/sse",
          "description": "高德地图服务",
          "enabled": true
        }
      ]
    }
  }
}
```

此时 `mcp_settings` 携带了一个启用状态的高德地图 Server，目标 agentName 是 `researchAgent`。

---

### 第 2 步：ChatController 接收并规范化请求

**`ChatController.java:111`**
```java
chatRequest = ChatRequestProcess.getDefaultChatRequest(chatRequest, searchBeanUtil);
```

`ChatRequestProcess.getDefaultChatRequest` 对 null 字段填默认值，`mcpSettings` 不为 null 则原样保留。

**`ChatRequestProcess.java:initializeObjectMap`**
```java
objectMap.put("mcp_settings", chatRequest.mcpSettings()); // 写入图输入 Map
objectMap.put("query", chatRequest.query());
objectMap.put("thread_id", chatRequest.threadId());
// ... 其他字段
```

`objectMap` 现在是：
```
{
  "query":        "帮我查一下北京天安门附近的停车场",
  "thread_id":    "thread-xyz",
  "mcp_settings": { "researchAgent": { "mcp-servers": [...] } },
  ...
}
```

---

### 第 3 步：图执行启动，mcp_settings 进入 OverAllState

**`ChatController.java:134`**
```java
Flux<NodeOutput> resultFuture = compiledGraph.fluxStream(objectMap, runnableConfig);
```

`objectMap` 被合并进 `OverAllState`，此后图内任意节点都可通过 `state.value("mcp_settings", Map.class)` 读取到前端传入的配置。

---

### 第 4 步：图流转至 ResearcherNode

图按 `coordinator → background_investigator → planner → [parallel_executor] → researcher_1` 的顺序执行，最终进入 `ResearcherNode.apply(state)`。

---

### 第 5 步：创建 MCP Provider（连接高德地图 Server）

**`ResearcherNode.java:147-148`**
```java
AsyncMcpToolCallbackProvider mcpProvider = mcpFactory != null
        ? mcpFactory.createProvider(state, "researchAgent") : null;
```

`McpProviderFactory.createProvider` 委托给 `McpClientUtil.createMcpProvider`，内部执行：

**① 读取合并配置**（`McpAssignNodeConfiguration.java:103-105`）
```java
// agent2mcpConfigWithRuntime 这个 Function 被调用
Map<String, Object> runtimeMcpSettings = state.value("mcp_settings", Map.class)
    .orElse(Collections.emptyMap());
// runtimeMcpSettings = { "researchAgent": { "mcp-servers": [高德地图] } }

return McpConfigMergeUtil.mergeAgent2McpConfigs(staticConfig, runtimeMcpSettings, objectMapper);
// staticConfig 来自 mcp-config.json（高德地图 enabled=false）
// runtimeMcpSettings 中高德地图 enabled=true，URL 相同 → 动态配置覆盖静态配置
// 合并结果：researchAgent 的高德地图 Server enabled=true
```

**② 建立 SSE Transport**（`McpConfigMergeUtil.java:130-133`）
```java
WebClient.Builder webClientBuilder = webClientBuilderTemplate.clone()
    .baseUrl("https://mcp.amap.com?key=YOUR_AMAP_KEY");

WebFluxSseClientTransport transport = WebFluxSseClientTransport.builder(webClientBuilder)
    .sseEndpoint("/sse")       // 建立 GET https://mcp.amap.com/sse 长连接
    .objectMapper(objectMapper)
    .build();
```

**③ 握手**（`McpClientUtil.java:106`）
```java
client.initialize().block(Duration.ofMinutes(2));
// 客户端 → GET  https://mcp.amap.com/sse          （建立 SSE 推送通道）
// 服务端 ← SSE event: endpoint=/message           （返回 POST 地址）
// 客户端 → POST https://mcp.amap.com/message      （发送 initialize 请求）
// 服务端 ← SSE event: { tools: ["maps_around_search", ...] } （握手完成）
```

此时 `AsyncMcpToolCallbackProvider` 持有高德地图暴露的所有工具描述（包含 JSON Schema）。

---

### 第 6 步：将工具注入 ChatClient 请求

**`ResearcherNode.java:149-151`**
```java
if (mcpProvider != null) {
    requestSpec = requestSpec.toolCallbacks(mcpProvider.getToolCallbacks());
}
// getToolCallbacks() 返回 [maps_around_search, maps_geo, maps_direction, ...]
// 这些工具的 JSON Schema 会被追加到发给 LLM 的 system prompt 里
```

---

### 第 7 步：发起流式 LLM 调用，LLM 决定调用工具

**`ResearcherNode.java:169-172`**
```java
Flux<ChatResponse> streamResult = requestSpec.messages(messages).stream().chatResponse();
```

LLM 收到工具列表后，判断该问题需要调用 `maps_around_search`，在响应中输出 `tool_use` 块：

```json
{
  "type": "tool_use",
  "name": "maps_around_search",
  "input": {
    "keywords": "停车场",
    "location": "116.3974,39.9093",
    "radius": 1000
  }
}
```

---

### 第 8 步：Spring AI 拦截 tool_use，调用 MCP Server

Spring AI 框架（非业务代码）检测到 `tool_use` 后，通过 `WebFluxSseClientTransport` 向高德地图 Server 发送 `tools/call` 请求：

```
客户端 → POST https://mcp.amap.com/message
Body:
{
  "method": "tools/call",
  "params": {
    "name": "maps_around_search",
    "arguments": { "keywords": "停车场", "location": "116.3974,39.9093", "radius": 1000 }
  }
}

服务端 ← SSE event:
{
  "result": {
    "content": [{ "type": "text", "text": "天安门停车场A：...\n天安门停车场B：..." }]
  }
}
```

---

### 第 9 步：工具结果追回 LLM，生成最终答案

Spring AI 将工具返回值追加到对话上下文，再次请求 LLM，LLM 基于停车场信息生成自然语言答案，以 SSE token 流的形式返回给前端。

---

### 完整数据流总览

```
前端 POST /chat/stream
  │  mcp_settings: { researchAgent: { mcp-servers: [高德 enabled=true] } }
  │
  ▼
ChatController
  │  ChatRequestProcess.initializeObjectMap()
  │  objectMap["mcp_settings"] = { researchAgent: {...} }
  │
  ▼
compiledGraph.fluxStream(objectMap)
  │  → OverAllState["mcp_settings"] = { researchAgent: {...} }
  │
  ▼
coordinator → planner → parallel_executor → ResearcherNode.apply(state)
  │
  ▼
mcpFactory.createProvider(state, "researchAgent")
  │
  ├─ agent2mcpConfigWithRuntime.apply(state)
  │    ├─ 读 state["mcp_settings"]（动态：高德 enabled=true）
  │    ├─ 读 mcp-config.json（静态：高德 enabled=false）
  │    └─ 合并 → 高德 enabled=true（动态覆盖静态）
  │
  ├─ WebFluxSseClientTransport.builder().baseUrl("高德URL").sseEndpoint("/sse").build()
  │    └─ GET https://mcp.amap.com/sse  （建立 SSE 长连接）
  │
  ├─ McpClient.async(transport).build()
  │
  └─ client.initialize().block()
       └─ POST /message: initialize  →  握手，拿到工具列表
  │
  ▼
AsyncMcpToolCallbackProvider（持有 maps_around_search 等工具）
  │
  ▼
requestSpec.toolCallbacks(provider.getToolCallbacks())
  │  工具 Schema 注入 LLM system prompt
  │
  ▼
LLM 推理 → 输出 tool_use: maps_around_search(...)
  │
  ▼
Spring AI 拦截 → POST /message: tools/call
  │  ← SSE event: 停车场列表数据
  │
  ▼
LLM 二次推理 → 生成最终自然语言答案
  │
  ▼
前端 ← SSE token 流（streaming 输出）
```
