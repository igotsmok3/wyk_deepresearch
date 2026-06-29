# Spring AI Alibaba API 使用总览

本文档梳理 DeepResearch 项目中所有 Spring AI (`org.springframework.ai`) 和 Spring AI Alibaba (`com.alibaba.cloud.ai`) 相关 API 的原始作用与项目中的具体用途。

---

## 一、DashScope（阿里云大模型接入层）

### `DashScopeConnectionProperties`
- **包路径**：`com.alibaba.cloud.ai.autoconfigure.dashscope`
- **原本作用**：持有 DashScope 服务的连接配置（API Key、baseUrl 等），由 Spring Boot 自动装配从 `application.yml` 读取。
- **使用位置**：`AgentModelsConfiguration`
- **项目中的作用**：在 `AgentModelsConfiguration` 构造函数中注入，取出 `apiKey` 用于动态构建多个 `DashScopeApi` 实例，每个 Agent 对应一个独立的 API 连接。

---

### `DashScopeApi`
- **包路径**：`com.alibaba.cloud.ai.dashscope.api`
- **原本作用**：DashScope HTTP 客户端，封装与阿里云模型服务的底层通信（请求签名、重试、流式处理）。
- **使用位置**：`AgentModelsConfiguration`
- **项目中的作用**：通过 `DashScopeApi.builder().apiKey(...).build()` 创建实例，传入 `DashScopeChatModel.builder()` 作为底层通信层，为每个 Agent 模型提供独立的 API 连接。

---

### `DashScopeChatModel`
- **包路径**：`com.alibaba.cloud.ai.dashscope.chat`
- **原本作用**：实现了 Spring AI 的 `ChatModel` 接口，将 DashScope API 的调用适配为 Spring AI 标准接口，支持同步调用和流式调用。
- **使用位置**：`AgentModelsConfiguration`
- **项目中的作用**：通过 Builder 模式创建，绑定 `DashScopeApi`、`ToolCallingManager`、`DashScopeChatOptions`，最终封装为 `ChatClient`，注册到 Spring 容器。每个 Agent（coordinator、planner、researcher 等）都有一个对应的 `DashScopeChatModel` 实例，可以独立配置不同模型名称和参数。

---

### `DashScopeChatOptions`
- **包路径**：`com.alibaba.cloud.ai.dashscope.chat`
- **原本作用**：DashScope 特有的聊天参数配置（模型名 `modelName`、温度 `temperature`、最大 Token 数等），是对 Spring AI 标准 `ChatOptions` 的扩展。
- **使用位置**：`AgentModelsConfiguration`
- **项目中的作用**：在构建 `DashScopeChatModel` 时设置默认参数，例如 `.withModel(model.modelName())` 指定从 `model-config.json` 读取的模型名称，`.withTemperature(DEFAULT_TEMPERATURE)` 设置生成温度。

---

## 二、Chat Client（LLM 调用核心）

### `ChatClient`
- **包路径**：`org.springframework.ai.chat.client`
- **原本作用**：Spring AI 的核心 LLM 调用接口，提供 Fluent API（`.prompt().messages().call()`），封装了消息构建、工具绑定、流式输出等全部调用细节，是对底层 `ChatModel` 的高层封装。
- **使用位置**：`AgentsConfiguration`（创建），`CoordinatorNode`、`PlannerNode`、`ReporterNode`、`ResearcherNode`、`CoderNode`、`BackgroundInvestigationNode`、`ShortUserRoleMemoryNode`、`HyDeTransformer`、`ReflectionProcessor` 等（使用）
- **项目中的作用**：项目中每个 Agent 都有一个专属的 `ChatClient` Bean（`coordinatorAgent`、`plannerAgent`、`researchAgent` 等），在各自的节点类中通过 `.prompt().messages(messages).call().chatResponse()` 或 `.stream()` 调用大模型，完成协调决策、研究计划生成、报告撰写等任务。

---

### `ChatClient.Builder`
- **包路径**：`org.springframework.ai.chat.client`
- **原本作用**：`ChatClient` 的建造者，用于设置默认系统提示词（`defaultSystem`）、默认工具（`defaultTools`）、默认选项（`defaultOptions`）等，使每个 Agent 在创建时就携带自己的个性化配置。
- **使用位置**：`AgentsConfiguration`、`DeepResearchConfiguration`、`HyDeTransformer`、`RewriteAndMultiQueryNode`
- **项目中的作用**：
  - `AgentsConfiguration` 中：用 Builder 为每个 Agent 绑定系统提示词（`.defaultSystem()`）和工具（`.defaultTools()`），最终 `.build()` 得到 ChatClient。
  - `RewriteAndMultiQueryNode` 中：持有 Builder 而非 ChatClient，以便动态创建 `RewriteQueryTransformer` 和 `MultiQueryExpander`（它们的 Builder 接受 `ChatClient.Builder` 参数）。
  - `HyDeTransformer` 中：通过 Builder 创建内部 ChatClient，避免依赖外部注入。

---

## 三、消息类型（Message）

### `Message`（接口）
- **包路径**：`org.springframework.ai.chat.messages`
- **原本作用**：所有消息类型的基接口，定义 `getText()`、`getMetadata()`、`getMessageType()` 等通用方法。
- **使用位置**：几乎所有节点类、`ShortTermMemoryRepository`、`MessageWindowChatMemory` 操作处
- **项目中的作用**：作为方法参数和返回类型，统一处理不同类型消息（UserMessage、SystemMessage、AssistantMessage）的集合，用于构建发送给 LLM 的消息列表。

---

### `UserMessage`
- **包路径**：`org.springframework.ai.chat.messages`
- **原本作用**：代表用户发送的消息，可以携带文本内容和元数据（`metadata`）。
- **使用位置**：`CoordinatorNode`、`ShortUserRoleMemoryNode`、`ShortUserRoleExtractInMemory`、`RewriteAndMultiQueryNode`
- **项目中的作用**：
  - `CoordinatorNode`：将当前用户提问包装为 `UserMessage`，加入消息列表，同时存入 `MessageWindowChatMemory` 维护对话历史。
  - `ShortUserRoleMemoryNode`：将用户提问包装，附加 `create_time` 元数据，存入 `userQueryMemory` 以便按时间排序查取历史提问。

---

### `SystemMessage`
- **包路径**：`org.springframework.ai.chat.messages`
- **原本作用**：代表系统角色消息，通常用于设置 AI 的行为规则和背景信息，在对话中权重高于用户消息。
- **使用位置**：`TemplateUtil`、`ShortUserRoleMemoryNode`、`ShortUserRoleExtractInMemory`
- **项目中的作用**：
  - `TemplateUtil.getMessage()`：将 Markdown 提示词文件读取后包装为 `SystemMessage`，注入节点的消息列表。
  - `ShortUserRoleMemoryNode`：将用户画像 JSON 序列化后包装为 `SystemMessage`，存入 `shortTermMemory` 和 `shortTermMemoryTrack`（利用 `Message` 接口的通用存储，避免引入新类型）。

---

### `AssistantMessage`
- **包路径**：`org.springframework.ai.chat.messages`
- **原本作用**：代表 AI 助手的回复消息，包含文本内容和可能的工具调用信息（`getToolCalls()`）。
- **使用位置**：`CoordinatorNode`、`GraphProcess`
- **项目中的作用**：
  - `CoordinatorNode`：调用 LLM 后从 `ChatResponse` 中取出 `AssistantMessage`，检查 `getToolCalls()` 是否为空来判断是"直接回答"还是"触发深度研究"。直接回答时将 AssistantMessage 存入 `MessageWindowChatMemory`。
  - `GraphProcess`：从流式输出中提取文本内容发送给前端 SSE。

---

### `ToolResponseMessage`
- **包路径**：`org.springframework.ai.chat.messages`
- **原本作用**：代表工具调用的执行结果，在工具调用链路中返回给 LLM。
- **使用位置**：序列化/反序列化相关类（`DeepResearchStateSerializer`、`DeepResearchDeserializer`）
- **项目中的作用**：在自定义序列化器中处理工具调用结果消息的序列化，确保 Checkpoint 中包含工具响应的状态能被正确存储和恢复。

---

## 四、Chat Response（响应对象）

### `ChatResponse`
- **包路径**：`org.springframework.ai.chat.model`
- **原本作用**：LLM 调用的完整响应容器，包含一个或多个 `Generation`（生成结果），以及元数据（使用量、模型信息等）。
- **使用位置**：`CoordinatorNode`、`ShortUserRoleMemoryNode`、`PlannerNode`、`GraphProcess`、`FluxConverter`
- **项目中的作用**：从 `ChatClient.call().chatResponse()` 获取，提取 `getResult().getOutput()` 拿到 `AssistantMessage` 或文本内容；在流式调用中作为 `Flux<ChatResponse>` 的元素，`FluxConverter` 将其映射为 `GraphResponse<StreamingOutput>`。

---

### `Generation`
- **包路径**：`org.springframework.ai.chat.model`
- **原本作用**：单次生成结果，包含输出消息（`AssistantMessage`）和元数据（`ChatGenerationMetadata`）。
- **使用位置**：`GraphProcess`
- **项目中的作用**：在 `GraphProcess` 处理流式输出时，从 `ChatResponse.getResult()` 取出 `Generation`，再取其元数据判断生成是否结束。

---

### `ChatGenerationMetadata`
- **包路径**：`org.springframework.ai.chat.metadata`
- **原本作用**：单次生成的元数据，包含 `finishReason`（停止原因：`STOP`/`TOOL_CALLS` 等）、token 使用量等信息。
- **使用位置**：`GraphProcess`
- **项目中的作用**：判断流式输出是否由工具调用触发，以决定如何向前端 SSE 推送内容（工具调用结果与普通文本使用不同的事件格式）。

---

## 五、Chat Memory（对话记忆）

### `InMemoryChatMemoryRepository`
- **包路径**：`org.springframework.ai.chat.memory`
- **原本作用**：`ChatMemoryRepository` 接口的内存实现，用 `ConcurrentHashMap` 按 `conversationId` 存储消息列表。
- **使用位置**：`MemoryConfig`
- **项目中的作用**：作为 `MessageWindowChatMemory` 的底层存储，在 `MemoryConfig` 中创建实例后传入 `MessageWindowChatMemory.builder()`。

---

### `MessageWindowChatMemory`
- **包路径**：`org.springframework.ai.chat.memory`
- **原本作用**：滑动窗口对话记忆，封装消息的存取，超出 `maxMessages` 限制时自动丢弃最旧的消息，实现有界的多轮对话历史。
- **使用位置**：`MemoryConfig`（创建），`CoordinatorNode`、`ReporterNode`（使用），`ShortUserRoleMemoryController`（查询）
- **项目中的作用**：
  - `CoordinatorNode`：每次调用时先 `get(sessionId)` 取历史消息加入提示词，调用后将本轮 UserMessage/AssistantMessage `add` 进去，实现多轮对话连贯性。
  - `ReporterNode`：报告生成完成后将最终报告存入，让后续对话能引用之前的研究结论。
  - `ShortUserRoleMemoryController`：通过 `GET /api/user/memory/conversation` 接口对外暴露历史查询能力。

---

## 六、Output Conversion（输出解析）

### `BeanOutputConverter<T>`
- **包路径**：`org.springframework.ai.converter`
- **原本作用**：将 LLM 输出的 JSON 文本自动解析为 Java 对象，内部使用 Jackson ObjectMapper，同时能生成格式指令字符串（`getFormat()`）告诉 LLM 应该输出什么结构。
- **使用位置**：`PlannerNode`、`ShortUserRoleMemoryNode`、`ReflectionProcessor`、`InformationNode`
- **项目中的作用**：
  - `PlannerNode`：`BeanOutputConverter<Plan>` 将 LLM 输出解析为研究计划 `Plan` 对象（含步骤列表）。
  - `ShortUserRoleMemoryNode`：`BeanOutputConverter<ShortUserRoleExtractResult>` 解析用户画像 JSON，同时 `converter.getFormat()` 作为格式指令传给 LLM，确保 LLM 输出符合预期结构。
  - `ReflectionProcessor`：解析反思评估结果 `ReflectionResult`。

---

### `PromptTemplate`
- **包路径**：`org.springframework.ai.chat.prompt`
- **原本作用**：基于模板字符串和变量的 Prompt 构建工具，支持 `{variable}` 占位符替换，生成最终的 Prompt。
- **使用位置**：`HyDeTransformer`
- **项目中的作用**：在 `HyDeTransformer` 中定义假设性文档生成的提示词模板，通过 `PromptAssert.templateHasRequiredPlaceholders()` 验证模板必须包含 `{query}` 占位符，调用时 `.param("query", query.text())` 动态替换。

---

## 七、Tool Calling（工具调用）

### `@Tool`
- **包路径**：`org.springframework.ai.tool.annotation`
- **原本作用**：注解在方法上，将普通 Java 方法声明为可被 LLM 调用的工具，`name` 和 `description` 会作为工具描述传给 LLM 以决定何时调用该工具。
- **使用位置**：`PlannerTool`、`PythonReplTool`
- **项目中的作用**：
  - `PlannerTool.handoffToPlanner()`：标注为工具，当 coordinator LLM 判断需要深度研究时，会调用此工具以触发"工具调用"事件，`CoordinatorNode` 通过 `getToolCalls().isEmpty()` 检测此事件，路由到 planner 节点。
  - `PythonReplTool`：标注 Python 代码执行工具，供 coder Agent 调用来执行 Python 代码。

---

### `ToolCallback`
- **包路径**：`org.springframework.ai.tool`
- **原本作用**：工具回调接口，将工具的描述和执行逻辑封装为一个对象，可以动态注册到 ChatClient。
- **使用位置**：`AgentsConfiguration`
- **项目中的作用**：`getMcpToolCallbacks()` 从 MCP 提供者获取 `ToolCallback[]` 数组，通过 `builder.defaultToolCallbacks(mcpCallbacks)` 将 MCP 服务器提供的工具动态注册给各 Agent 的 ChatClient。

---

### `ToolCallingManager`
- **包路径**：`org.springframework.ai.model.tool`
- **原本作用**：工具调用生命周期管理器，负责解析工具描述、执行工具调用、将结果返回给 LLM，支持自动和手动两种执行模式。
- **使用位置**：`AgentModelsConfiguration`
- **项目中的作用**：在构建每个 `DashScopeChatModel` 时传入，使模型支持工具调用能力。由 Spring 自动装配提供单例实例。

---

### `ToolCallingChatOptions`
- **包路径**：`org.springframework.ai.model.tool`
- **原本作用**：扩展了标准 ChatOptions，增加了 `internalToolExecutionEnabled` 配置项，控制框架是否自动执行工具调用（`true`=自动执行，`false`=仅生成工具调用参数不执行）。
- **使用位置**：`AgentsConfiguration`（coordinatorAgent 配置处）
- **项目中的作用**：为 `coordinatorAgent` 设置 `internalToolExecutionEnabled(false)`，让 coordinator LLM 只产生工具调用信号（`handoff_to_planner`），不实际执行工具，`CoordinatorNode` 读取 `getToolCalls()` 判断是否触发深度研究流程。

---

### `ToolDefinition`
- **包路径**：`org.springframework.ai.tool.definition`
- **原本作用**：描述工具的元数据（工具名、描述、输入参数 Schema），是 LLM 判断是否调用工具的依据。
- **使用位置**：`ObservationConfiguration`（ObservationHandler 中）
- **项目中的作用**：在自定义的工具调用观测 Handler 中，通过 `context.getToolDefinition()` 取出工具名和描述，记录到日志，用于追踪哪些工具被 LLM 实际调用了。

---

### `ToolCallingObservationContext`
- **包路径**：`org.springframework.ai.tool.observation`
- **原本作用**：工具调用的 Micrometer Observation 上下文，包含工具调用的请求/响应信息，在 `ObservationHandler` 回调中可读取。
- **使用位置**：`ObservationConfiguration`
- **项目中的作用**：注册了一个 `ObservationHandler<ToolCallingObservationContext>`，在工具调用开始（`onStart`）和结束（`onStop`）时通过 `context.getToolDefinition()` 获取工具信息并写入日志，实现工具调用的可观测性。

---

## 八、MCP（Model Context Protocol）

### `AsyncMcpToolCallbackProvider`
- **包路径**：`org.springframework.ai.mcp`
- **原本作用**：异步 MCP 客户端的工具回调提供者，封装了与 MCP 服务器的异步连接，提供 `getToolCallbacks()` 方法返回服务器上所有可用工具。
- **使用位置**：`AgentsConfiguration`、`McpProviderFactory`
- **项目中的作用**：
  - `AgentsConfiguration`：作为可选 Bean（`@Autowired(required = false)`）注入，按 agentName 分配对应的 MCP 工具给各 Agent 的 ChatClient。
  - `McpProviderFactory.createProvider()`：动态创建（而非 Spring 管理的静态 Bean），根据请求中的 `mcp_settings` 为当前图执行实例化特定 MCP 服务器的连接，支持运行时动态配置 MCP 工具。

---

### `SyncMcpToolCallbackProvider`
- **包路径**：`org.springframework.ai.mcp`
- **原本作用**：同步版本的 MCP 工具回调提供者，适用于不需要异步响应的场景。
- **使用位置**：`AgentsConfiguration`
- **项目中的作用**：作为备选方案，当使用同步 MCP 传输时（如标准输入输出的本地进程），`getMcpToolCallbacks()` 优先尝试 Sync，找不到再尝试 Async。

---

### `McpAsyncClientConfigurer`
- **包路径**：`org.springframework.ai.mcp.client.autoconfigure.configurer`
- **原本作用**：Spring Boot 自动配置类，负责根据配置文件创建和配置 MCP 异步客户端。
- **使用位置**：`McpProviderFactory`
- **项目中的作用**：在 `McpProviderFactory` 中注入，传给 `McpClientUtil.createMcpProvider()`，用于在运行时动态创建新的 MCP 异步客户端实例（绑定到请求传入的 MCP 服务器地址和认证信息）。

---

### `NamedClientMcpTransport`
- **包路径**：`org.springframework.ai.mcp.client.autoconfigure`
- **原本作用**：带名称的 MCP 传输层封装，将 MCP 服务器名称与具体的传输实现（SSE/HTTP/stdio）绑定为一个具名对象。
- **使用位置**：`McpConfigMergeUtil`、`McpClientUtil`
- **项目中的作用**：在 `McpConfigMergeUtil.createAgent2McpTransports()` 中，将从请求中解析出的每个 MCP 服务器配置创建为 `NamedClientMcpTransport`，传入 `McpAsyncClientConfigurer` 来创建对应的 MCP 客户端连接。

---

### `McpClientCommonProperties`
- **包路径**：`org.springframework.ai.mcp.client.autoconfigure.properties`
- **原本作用**：MCP 客户端的公共配置属性（连接超时、重试策略等），由 Spring Boot 自动配置提供。
- **使用位置**：`McpProviderFactory`
- **项目中的作用**：作为公共配置参数，在动态创建 MCP 客户端时传给工具方法，确保新创建的客户端与静态配置的客户端使用相同的全局参数。

---

## 九、RAG 查询处理（Pre-Retrieval）

### `Query`
- **包路径**：`org.springframework.ai.rag`
- **原本作用**：Spring AI RAG 管道中的查询对象，封装查询文本、对话历史（`history`）等上下文，是 RAG 各处理组件的统一输入/输出格式。
- **使用位置**：`RewriteAndMultiQueryNode`、`DefaultHybridRagProcessor`、`UserFileRetrievalStrategy`、`HyDeTransformer`
- **项目中的作用**：
  - `RewriteAndMultiQueryNode`：将用户提问和历史消息封装为 `Query`，依次传给 `CompressionQueryTransformer`（压缩历史）→`RewriteQueryTransformer`（重写）→`MultiQueryExpander`（扩展），最终得到多条优化查询。
  - `UserFileRetrievalStrategy`：包装查询后传入 `HybridRagProcessor.process()`。

---

### `QueryTransformer`（接口）
- **包路径**：`org.springframework.ai.rag.preretrieval.query.transformation`
- **原本作用**：查询转换器接口，接受一个 `Query`，输出转换后的 `Query`，是所有具体查询转换实现的统一接口。
- **使用位置**：`RewriteAndMultiQueryNode`（类型声明）、`DefaultHybridRagProcessor`（类型声明）
- **项目中的作用**：作为字段类型，持有具体的转换实现（如 `RewriteQueryTransformer`），使节点代码与具体实现解耦。

---

### `RewriteQueryTransformer`
- **包路径**：`org.springframework.ai.rag.preretrieval.query.transformation`
- **原本作用**：使用 LLM 对原始查询进行重写，提升查询表达质量和检索精度，去除口语化表达和歧义。
- **使用位置**：`RewriteAndMultiQueryNode`
- **项目中的作用**：在 `RewriteAndMultiQueryNode` 构造时初始化，传入 `rewriteAndMultiQueryAgentBuilder`（LLM 客户端），调用 `queryTransformer.transform(query)` 对用户原始提问进行语义重写，为后续多查询扩展提供更精准的基础查询。

---

### `CompressionQueryTransformer`
- **包路径**：`org.springframework.ai.rag.preretrieval.query.transformation`
- **原本作用**：使用 LLM 结合对话历史，将多轮对话中的代词引用、省略表达压缩为一个完整独立的查询，使当轮查询能脱离上下文独立理解。
- **使用位置**：`RewriteAndMultiQueryNode`
- **项目中的作用**：当 `MessageWindowChatMemory` 中存在历史对话时，将历史消息附加到 `Query.history` 字段，调用 `CompressionQueryTransformer.transform()` 消解引用（如"上面提到的那个框架"→具体框架名），再传给 `RewriteQueryTransformer`。

---

### `TranslationQueryTransformer`
- **包路径**：`org.springframework.ai.rag.preretrieval.query.transformation`
- **原本作用**：使用 LLM 将查询翻译为目标语言，解决知识库语言与用户提问语言不一致的问题。
- **使用位置**：`DefaultHybridRagProcessor`
- **项目中的作用**：RAG 管道的可选第一步（由 `ragProperties.pipeline.queryTranslationEnabled` 控制），将用户中文提问翻译为配置的目标语言（如英文），提升英文知识库的检索命中率。

---

### `QueryExpander`（接口）
- **包路径**：`org.springframework.ai.rag.preretrieval.query.expansion`
- **原本作用**：查询扩展接口，将一个查询扩展为多个相关查询，提升召回率。
- **使用位置**：`RewriteAndMultiQueryNode`（类型声明）
- **项目中的作用**：作为字段类型，持有 `MultiQueryExpander` 实例，调用 `.expand(query)` 返回多个查询变体。

---

### `MultiQueryExpander`
- **包路径**：`org.springframework.ai.rag.preretrieval.query.expansion`
- **原本作用**：使用 LLM 将单个查询扩展为多个语义相关但表达不同的查询，并行检索后合并结果，提升向量检索的召回率。
- **使用位置**：`RewriteAndMultiQueryNode`、`DefaultHybridRagProcessor`
- **项目中的作用**：
  - `RewriteAndMultiQueryNode`：通过配置参数 `optimizeQueryNum` 控制扩展数量，扩展后的多个查询列表存入 `OverAllState["optimize_queries"]`，供后续 researcher/coder 节点并行检索使用。
  - `DefaultHybridRagProcessor`：RAG 管道中的可选扩展步骤，开启后每个检索请求并行执行多个变体查询。

---

## 十、RAG 文档处理（Post-Retrieval）

### `DocumentPostProcessor`（接口）
- **包路径**：`org.springframework.ai.rag.postretrieval.document`
- **原本作用**：文档后处理接口，对检索到的文档列表做过滤、排序、截断等操作，是 RAG 管道中检索后阶段的统一接口。
- **使用位置**：`DocumentSelectFirstProcess`、`RrfFusionStrategy`（均实现此接口）
- **项目中的作用**：
  - `DocumentSelectFirstProcess`：实现该接口，仅保留第一条文档（最相关的），减少 LLM 的 context 长度。
  - `RrfFusionStrategy`：同时实现该接口，对多路检索结果执行 RRF（Reciprocal Rank Fusion）重排序后返回。

---

### `DocumentRetriever`（接口）
- **包路径**：`org.springframework.ai.rag.retrieval.search`
- **原本作用**：文档检索接口，接受 `Query` 返回 `List<Document>`，是 RAG 检索层的统一抽象。
- **使用位置**：`RrfHybridElasticsearchRetriever`（实现此接口）
- **项目中的作用**：`RrfHybridElasticsearchRetriever` 实现该接口，封装了向量检索（Embedding 相似度）和全文检索（BM25）的混合查询逻辑，对外以统一的 `retrieve(Query)` 接口暴露。

---

### `PromptAssert`
- **包路径**：`org.springframework.ai.rag.util`
- **原本作用**：断言工具类，用于在启动时校验 `PromptTemplate` 模板是否包含必要的占位符，提前发现配置错误。
- **使用位置**：`HyDeTransformer`
- **项目中的作用**：在 `HyDeTransformer` 构造时调用 `PromptAssert.templateHasRequiredPlaceholders(template, "query")`，若模板缺少 `{query}` 占位符则立即抛出异常，防止运行时格式错误。

---

## 十一、Document & Vector Store（文档与向量存储）

### `Document`
- **包路径**：`org.springframework.ai.document`
- **原本作用**：Spring AI 的核心文档对象，包含文本内容（`getText()`）、唯一 ID 和元数据 Map，是 RAG 全链路的数据载体。
- **使用位置**：`VectorStoreDataIngestionService`、`DefaultHybridRagProcessor`、`UserFileRetrievalStrategy`、`RrfHybridElasticsearchRetriever`
- **项目中的作用**：在文档摄入流程中，`TikaDocumentReader` 解析文件得到 `Document` 列表，`TokenTextSplitter` 切分后添加业务元数据（`session_id`、`source_type`、`kb_id` 等），最终存入 VectorStore；检索时以 `List<Document>` 形式返回给 RAG 节点。

---

### `VectorStore`（接口）
- **包路径**：`org.springframework.ai.vectorstore`
- **原本作用**：向量数据库的统一抽象接口，定义 `add(List<Document>)`、`delete()`、`similaritySearch(SearchRequest)` 等核心操作，屏蔽不同向量数据库的实现差异。
- **使用位置**：`VectorStoreDataIngestionService`、`DefaultHybridRagProcessor`、`RagVectorStoreConfiguration`
- **项目中的作用**：注入 `@Qualifier("ragVectorStore")` 限定符的 Bean，根据配置实际类型为 `SimpleVectorStore` 或 `ElasticsearchVectorStore`，对上层代码透明。

---

### `SimpleVectorStore`
- **包路径**：`org.springframework.ai.vectorstore`
- **原本作用**：基于内存的向量存储实现，使用 Java `HashMap` 存储向量，支持保存到文件/从文件加载。适用于开发测试场景。
- **使用位置**：`RagVectorStoreConfiguration`（`vector-store-type=simple` 时创建）
- **项目中的作用**：RAG 功能的默认向量存储，当未配置 Elasticsearch 时使用，通过 `SimpleVectorStore.builder(embeddingModel).build()` 创建，可选配置本地文件持久化路径。

---

### `ElasticsearchVectorStore`
- **包路径**：`org.springframework.ai.vectorstore.elasticsearch`
- **原本作用**：基于 Elasticsearch 的向量存储实现，支持向量相似度检索（KNN）和全文检索，适用于生产环境。
- **使用位置**：`RagVectorStoreConfiguration`（`vector-store-type=elasticsearch` 时创建）
- **项目中的作用**：生产环境的 RAG 向量存储，通过 Builder 配置索引名、相似度函数、向量维度、批处理策略，支持与 `RrfHybridElasticsearchRetriever` 配合实现混合检索。

---

### `ElasticsearchVectorStoreOptions`
- **包路径**：`org.springframework.ai.vectorstore.elasticsearch`
- **原本作用**：`ElasticsearchVectorStore` 的配置选项对象，包含索引名、向量维度、相似度函数等参数。
- **使用位置**：`RagVectorStoreConfiguration`
- **项目中的作用**：从 `RagProperties` 读取配置值，构建 Options 对象后传给 `ElasticsearchVectorStore.builder().options(options)`，实现 Elasticsearch 索引参数的外部化配置。

---

### `SimilarityFunction`
- **包路径**：`org.springframework.ai.vectorstore.elasticsearch`
- **原本作用**：枚举类，定义向量相似度计算函数（`COSINE`、`DOT_PRODUCT`、`L2_NORM`），决定 Elasticsearch 如何计算向量间的相似度。
- **使用位置**：`RagVectorStoreConfiguration`、`RagProperties`
- **项目中的作用**：从配置文件读取相似度函数类型，通过 `options.setSimilarity(esProps.getSimilarityFunction())` 设置到 ES 向量索引上。

---

### `SearchRequest`
- **包路径**：`org.springframework.ai.vectorstore`
- **原本作用**：向量数据库相似度搜索请求对象，包含查询文本、返回数量（`topK`）、相似度阈值、过滤表达式等参数。
- **使用位置**：`DefaultHybridRagProcessor`、`UserFileRetrievalStrategy`（通过 VectorStore 间接使用）
- **项目中的作用**：在 `DefaultHybridRagProcessor` 执行向量检索时构建，通过 `FilterExpressionBuilder` 添加元数据过滤条件（如 `source_type`、`session_id`），实现按来源类型隔离检索结果。

---

### `FilterExpressionBuilder`
- **包路径**：`org.springframework.ai.vectorstore.filter`
- **原本作用**：构建向量数据库的元数据过滤表达式，支持 `eq`、`in`、`and`、`or` 等运算，生成 Spring AI 通用的过滤 AST（不依赖特定数据库语法）。
- **使用位置**：`DefaultHybridRagProcessor`
- **项目中的作用**：在 `buildFilterExpression()` 方法中，根据请求的 `source_type`（用户上传/专业知识库/ES）和 `session_id` 构建过滤条件，确保每次检索只命中属于当前用户会话的文档。

---

## 十二、Embedding（向量化）

### `EmbeddingModel`（接口）
- **包路径**：`org.springframework.ai.embedding`
- **原本作用**：向量化模型的统一抽象接口，将文本转为浮点数向量，是 VectorStore 执行相似度检索的基础。
- **使用位置**：`RagVectorStoreConfiguration`、`DefaultHybridRagProcessor`、`RrfHybridElasticsearchRetriever`
- **项目中的作用**：由 Spring AI Alibaba 自动装配（底层为 DashScope Embedding 模型），注入到 `SimpleVectorStore.builder(embeddingModel)` 和 `ElasticsearchVectorStore.builder(restClient, embeddingModel)` 中，在文档摄入和检索时自动完成向量化。

---

### `EmbeddingUtils`
- **包路径**：`org.springframework.ai.model`
- **原本作用**：向量化工具类，提供向量数据格式转换方法，如 `toList(float[])` 将 float 数组转为 `List<Float>`。
- **使用位置**：`RrfHybridElasticsearchRetriever`
- **项目中的作用**：在混合检索器中，将 `EmbeddingModel` 输出的 `float[]` 向量通过 `EmbeddingUtils.toList(vector)` 转为 Elasticsearch KNN 查询所需的 `List<Float>` 格式。

---

### `TokenCountBatchingStrategy`
- **包路径**：`org.springframework.ai.embedding`
- **原本作用**：按 Token 数量分批处理文档的策略，在调用 Embedding API 时将大量文档分成不超过 Token 限制的批次，避免单次请求超出模型限制。
- **使用位置**：`RagVectorStoreConfiguration`（ElasticsearchVectorStore 配置处）
- **项目中的作用**：在创建 `ElasticsearchVectorStore` 时设置 `.batchingStrategy(new TokenCountBatchingStrategy())`，确保批量摄入大量文档时不会因单批 Token 超限而失败。

---

## 十三、Document Reading & Splitting（文档读取与切分）

### `TikaDocumentReader`
- **包路径**：`org.springframework.ai.reader.tika`
- **原本作用**：基于 Apache Tika 的文档读取器，支持 PDF、Word（docx）、Markdown、HTML、Excel 等几十种文档格式的自动解析，输出统一的 `Document` 对象列表。
- **使用位置**：`VectorStoreDataIngestionService`
- **项目中的作用**：在文档摄入的第一步，`new TikaDocumentReader(resource).get()` 解析上传文件（无论是 PDF、docx 还是 md），输出纯文本 `Document` 列表，后续统一处理，实现格式无关的知识库构建。

---

### `TokenTextSplitter`
- **包路径**：`org.springframework.ai.transformer.splitter`
- **原本作用**：按 Token 数量切分长文档，确保每个文档块在 Embedding 模型的 context 限制内，支持配置 chunk 大小、重叠量、最小切分大小等参数。
- **使用位置**：`VectorStoreDataIngestionService`
- **项目中的作用**：文档摄入第二步，将 `TikaDocumentReader` 解析出的长文档切分为小块，参数从 `RagProperties.textSplitter` 读取（`defaultChunkSize`、`overlap` 等），切分结果经元数据富化后存入 VectorStore。

---

## 十四、Spring AI Alibaba Graph（图编排框架）

### `NodeAction`（接口）
- **包路径**：`com.alibaba.cloud.ai.graph.action`
- **原本作用**：图节点的同步执行接口，定义 `apply(OverAllState) -> Map<String, Object>` 方法，节点逻辑写在 `apply` 中，返回值会被合并进全局状态。
- **使用位置**：所有节点类（`CoordinatorNode`、`PlannerNode`、`ReporterNode`、`ResearcherNode` 等共 10+ 个节点）
- **项目中的作用**：所有业务节点的统一接口，图框架通过此接口调度节点执行，节点返回的 Map 按 `KeyStrategy` 合并到 `OverAllState` 中，驱动下一个节点的决策。

---

### `EdgeAction`（接口）
- **包路径**：`com.alibaba.cloud.ai.graph.action`
- **原本作用**：图条件边的路由接口，定义 `apply(OverAllState) -> String` 方法，返回下一个要去的节点名称，实现基于状态的动态路由。
- **使用位置**：所有 Dispatcher 类（`CoordinatorDispatcher`、`PlannerDispatcher`、`ShortUserRoleMemoryDispatcher` 等共 8 个）
- **项目中的作用**：每个关键节点后都有一个 Dispatcher 实现 `EdgeAction`，从 `OverAllState` 读取 `*_next_node` 字段（由节点写入），返回对应的目标节点名，实现运行时动态路由。

---

### `node_async()` / `AsyncNodeAction`
- **包路径**：`com.alibaba.cloud.ai.graph.action.AsyncNodeAction`
- **原本作用**：将同步 `NodeAction` 包装为异步执行，使图节点以非阻塞方式调度，避免 LLM 长时间等待阻塞线程。
- **使用位置**：`DeepResearchConfiguration`（所有 `addNode` 调用处）
- **项目中的作用**：`stateGraph.addNode("planner", node_async(new PlannerNode(...)))` — 所有节点都用 `node_async()` 包装，图框架以异步方式调度每个节点，支持流式 SSE 输出。

---

### `edge_async()` / `AsyncEdgeAction`
- **包路径**：`com.alibaba.cloud.ai.graph.action.AsyncEdgeAction`
- **原本作用**：将同步 `EdgeAction` 包装为异步执行的条件边。
- **使用位置**：`DeepResearchConfiguration`（所有 `addConditionalEdges` 调用处）
- **项目中的作用**：`stateGraph.addConditionalEdges("coordinator", edge_async(new CoordinatorDispatcher()), ...)` — 条件边的路由函数也以异步方式执行，不阻塞图调度线程。

---

### `OverAllState`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：图执行的全局共享状态容器，以 `Map<String, Object>` 为底层存储，所有节点读写同一个状态对象，是节点间通信的唯一通道。
- **使用位置**：所有节点类和 Dispatcher 类（`apply(OverAllState state)` 参数）
- **项目中的作用**：存储一次研究请求的完整上下文，包括用户提问（`query`）、会话 ID（`session_id`）、研究计划（`current_plan`）、研究结果（`observations`）、最终报告（`final_report`）、下一节点路由（`*_next_node`）等几十个字段，节点间无需直接依赖，通过 State 松耦合传递数据。

---

### `StateGraph`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：有向图的定义容器，提供 `addNode()`、`addEdge()`、`addConditionalEdges()` API，用于声明图的拓扑结构，之后调用 `compile()` 生成可执行的 `CompiledGraph`。
- **使用位置**：`DeepResearchConfiguration`（`deepResearch` Bean 方法）
- **项目中的作用**：在 `deepResearch()` Bean 中通过链式调用添加所有节点和边，构建完整的研究工作流图结构（START → short_user_role_memory → coordinator → planner → ... → reporter → END）。

---

### `CompiledGraph`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：编译后的可执行图，将 `StateGraph` 的声明性定义转为可直接调用的执行引擎，提供 `fluxStream(inputs, config)`、`stream()`、`invoke()` 等执行方法。
- **使用位置**：`ChatController`、`GraphProcess`
- **项目中的作用**：`ChatController` 注入编译好的图实例，调用 `compiledGraph.fluxStream(objectMap, runnableConfig)` 启动异步流式执行；`GraphProcess` 持有并管理图的执行过程（停止、恢复）。

---

### `RunnableConfig`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：图单次执行的运行时配置，最核心的是 `threadId`，作为 Checkpoint 的 key，区分不同执行线程。
- **使用位置**：`ChatController`、`GraphProcess`
- **项目中的作用**：每次 `POST /chat/stream` 都创建一个新的 `RunnableConfig.builder().threadId(chatRequest.threadId()).build()`，作为执行图的凭证，后续 `/resume` 使用相同 threadId 的 `RunnableConfig` 从 Checkpoint 恢复执行。

---

### `KeyStrategy`（接口）& `KeyStrategyFactory`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：定义 `OverAllState` 中每个 key 的更新策略（如何合并节点输出到全局状态），`KeyStrategyFactory` 是创建 key→strategy 映射的工厂接口。
- **使用位置**：`DeepResearchConfiguration`
- **项目中的作用**：`deepResearch()` Bean 中定义 `KeyStrategyFactory`，为每个状态字段配置策略，目前全部使用 `ReplaceStrategy`（新值覆盖旧值）。

---

### `ReplaceStrategy`
- **包路径**：`com.alibaba.cloud.ai.graph.state.strategy`
- **原本作用**：`KeyStrategy` 的实现，节点输出的新值直接替换 `OverAllState` 中的旧值，是最常见的状态更新语义。
- **使用位置**：`DeepResearchConfiguration`（`keyStrategyHashMap.put("...", new ReplaceStrategy())`）
- **项目中的作用**：项目所有状态字段均使用 `ReplaceStrategy`，确保每个节点的输出直接覆盖前一轮的值（如 `current_plan` 始终是最新计划，不累积历史版本）。

---

### `NodeOutput`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：图执行流中每步产出的输出对象，包含节点名（`nodeName()`）和该节点执行后的完整状态快照，是 `fluxStream()` 返回的 `Flux` 元素类型。
- **使用位置**：`GraphProcess`
- **项目中的作用**：在 `processStream()` 中逐个消费 `NodeOutput`，根据节点名判断输出类型（是普通状态节点还是 LLM 流式输出节点 `StreamingOutput`），分别封装为不同格式的 SSE 事件推送给前端。

---

### `GraphRepresentation`
- **包路径**：`com.alibaba.cloud.ai.graph`
- **原本作用**：图结构的可视化表示，支持输出 PlantUML、Mermaid 等格式的图结构描述文本，用于调试和文档生成。
- **使用位置**：`DeepResearchConfiguration`
- **项目中的作用**：`stateGraph.getGraph(GraphRepresentation.Type.PLANTUML, "deepResearch")` 在应用启动时生成图的 PlantUML 格式描述，打印到日志，方便开发者直观查看当前图的完整拓扑。

---

### `AsyncGenerator`
- **包路径**：`com.alibaba.cloud.ai.graph.async`
- **原本作用**：异步迭代器接口，通过 `next()` 方法逐步获取异步生成的数据，每次返回 `Data<T>` 包含值和是否结束的标志。
- **使用位置**：`GraphProcess`
- **项目中的作用**：`fluxStream()` 底层返回 `AsyncGenerator<NodeOutput>`，`processStream()` 在线程池中循环调用 `generator.next()` 拉取节点输出，将每个 `NodeOutput` 转为 SSE 事件，实现图执行结果的实时流式推送。

---

### `StreamingOutput`
- **包路径**：`com.alibaba.cloud.ai.graph.streaming`
- **原本作用**：图框架中流式输出节点的特殊输出类型，包装 LLM 的流式响应（`ChatResponse`），使图框架能区分普通状态输出和流式文本输出。
- **使用位置**：`PlannerNode`、`ReporterNode`、`GraphProcess`、`FluxConverter`
- **项目中的作用**：`PlannerNode` 和 `ReporterNode` 使用 `FluxConverter` 将 `Flux<ChatResponse>` 转为 `Flux<GraphResponse<StreamingOutput>>`，图框架将 `StreamingOutput` 逐 token 推送给前端，实现打字机效果。

---

### `PlainTextStateSerializer`
- **包路径**：`com.alibaba.cloud.ai.graph.serializer.plain_text`
- **原本作用**：基于明文（JSON）的图状态序列化器基类，负责将 `OverAllState` 序列化为 JSON 字符串（用于 Checkpoint 存储）和反序列化（用于恢复）。
- **使用位置**：`DeepResearchStateSerializer`（继承此类）
- **项目中的作用**：`DeepResearchStateSerializer extends PlainTextStateSerializer`，在基类 JSON 序列化能力的基础上，覆写处理 Spring AI 消息类型（`UserMessage`/`SystemMessage`/`AssistantMessage`）的序列化逻辑，解决这些对象无法被默认 Jackson 序列化的问题。

---

### `AgentStateFactory`
- **包路径**：`com.alibaba.cloud.ai.graph.state`
- **原本作用**：`OverAllState` 的工厂函数式接口，接受 `Map<String, Object>` 创建状态实例，用于在 Checkpoint 恢复时重建状态对象。
- **使用位置**：`DeepResearchStateSerializer`、`DeepResearchConfiguration`
- **项目中的作用**：`new DeepResearchStateSerializer(OverAllState::new)` — 传入 `OverAllState` 的构造方法引用作为工厂，序列化器在反序列化 Checkpoint 时通过此工厂重建 `OverAllState` 实例。

---

### `StateSnapshot`
- **包路径**：`com.alibaba.cloud.ai.graph.state`
- **原本作用**：图某一时刻的完整状态快照，包含 Checkpoint 数据、下一节点信息和运行时配置，用于查询历史状态或在特定节点恢复执行。
- **使用位置**：`GraphProcess`、`CompiledGraph`（`getStateHistory()`、`stateOf()`）
- **项目中的作用**：`ChatController.resume()` 恢复执行时，框架从 `MemorySaver` 取出最新 Checkpoint 构建 `StateSnapshot`，基于其中的 `nextNodeId` 知道从哪个节点继续执行。

---

## 十五、Spring AI Alibaba Tool Calling（工具调用集成）

### `SearchService`（接口）
- **包路径**：`com.alibaba.cloud.ai.toolcalling.common.interfaces`
- **原本作用**：搜索服务的统一接口，抽象了不同搜索引擎（Tavily、SerpAPI、阿里云 AI 搜索等）的调用方式。
- **使用位置**：`SearchBeanUtil`、`SearchFilterService`
- **项目中的作用**：`SearchBeanUtil` 按名称从 Spring 容器查找 `SearchService` Bean，`ResearcherNode` 和 `BackgroundInvestigationNode` 通过接口调用搜索，无需感知具体是哪个搜索引擎实现。

---

### `CommonToolCallUtils`
- **包路径**：`com.alibaba.cloud.ai.toolcalling.common`
- **原本作用**：工具调用通用工具类，提供构建工具调用请求、处理响应等通用方法。
- **使用位置**：搜索相关节点（`ResearcherNode`、`BackgroundInvestigationNode`）
- **项目中的作用**：辅助调用各搜索 Tool，处理搜索结果的统一格式化。

---

### `JinaCrawlerService` / `JinaCrawlerConstants`
- **包路径**：`com.alibaba.cloud.ai.toolcalling.jinacrawler`
- **原本作用**：Jina Reader 网页抓取服务，通过 Jina API 将网页 URL 转换为 Markdown 格式的干净文本，适合 LLM 处理。
- **使用位置**：`DeepResearchConfiguration`（注入到 `BackgroundInvestigationNode`）、`AgentsConfiguration`（researchAgent 配置）
- **项目中的作用**：
  - `AgentsConfiguration`：`getAvailableTools(JinaCrawlerConstants.TOOL_NAME)` 检查 Jina Tool Bean 是否存在，存在则注册给 `researchAgent`，让 researcher LLM 可以主动抓取网页内容。
  - `BackgroundInvestigationNode`：可注入 `JinaCrawlerService` 直接调用网页抓取（`@Autowired(required=false)`）。

---

### `SearchEnum` / `SearchUtil`
- **包路径**：`com.alibaba.cloud.ai.toolcalling.searches`
- **原本作用**：`SearchEnum` 枚举所有支持的搜索引擎（TAVILY、SERPAPI、ALIYUN_AI_SEARCH 等）；`SearchUtil` 提供搜索相关的工具方法。
- **使用位置**：`SearchBeanUtil`
- **项目中的作用**：`SearchBeanUtil` 使用 `SearchEnum` 枚举值查找对应的 `SearchService` Bean，验证请求中 `searchEngine` 参数的合法性，并在 `ChatRequest` 中默认设置可用的搜索引擎。

---

## 总览索引

| API 类 | 所属模块 | 使用位置（核心） | 核心作用 |
|---|---|---|---|
| `DashScopeChatModel` | DashScope | `AgentModelsConfiguration` | 为每个 Agent 创建独立的 LLM 模型实例 |
| `DashScopeChatOptions` | DashScope | `AgentModelsConfiguration` | 配置模型名称和温度参数 |
| `ChatClient` | Spring AI Chat | 所有节点类 | 调用大模型的核心 Fluent API |
| `BeanOutputConverter` | Spring AI | `PlannerNode`、`ShortUserRoleMemoryNode` | 将 LLM JSON 输出解析为 Java 对象 |
| `MessageWindowChatMemory` | Spring AI Memory | `CoordinatorNode`、`ReporterNode` | 多轮对话历史窗口 |
| `@Tool` | Spring AI Tools | `PlannerTool`、`PythonReplTool` | 声明 LLM 可调用的工具方法 |
| `ToolCallingChatOptions` | Spring AI Tools | `AgentsConfiguration` | 禁用 coordinator 的自动工具执行 |
| `AsyncMcpToolCallbackProvider` | Spring AI MCP | `McpProviderFactory` | 动态创建 MCP 工具连接 |
| `RewriteQueryTransformer` | Spring AI RAG | `RewriteAndMultiQueryNode` | LLM 重写用户查询 |
| `CompressionQueryTransformer` | Spring AI RAG | `RewriteAndMultiQueryNode` | 结合历史对话压缩查询 |
| `MultiQueryExpander` | Spring AI RAG | `RewriteAndMultiQueryNode` | 扩展为多条查询提升召回 |
| `TranslationQueryTransformer` | Spring AI RAG | `DefaultHybridRagProcessor` | 查询语言翻译 |
| `TikaDocumentReader` | Spring AI Docs | `VectorStoreDataIngestionService` | 解析多格式文档为文本 |
| `TokenTextSplitter` | Spring AI Docs | `VectorStoreDataIngestionService` | 按 Token 切分长文档 |
| `ElasticsearchVectorStore` | Spring AI Vector | `RagVectorStoreConfiguration` | 生产环境向量存储 |
| `FilterExpressionBuilder` | Spring AI Vector | `DefaultHybridRagProcessor` | 按元数据过滤检索结果 |
| `NodeAction` | Graph | 所有节点类 | 图节点执行接口 |
| `EdgeAction` | Graph | 所有 Dispatcher 类 | 图条件边路由接口 |
| `OverAllState` | Graph | 所有节点和 Dispatcher | 节点间通信的全局状态容器 |
| `StateGraph` / `CompiledGraph` | Graph | `DeepResearchConfiguration`、`ChatController` | 定义和执行研究工作流图 |
| `MemorySaver` | Graph Checkpoint | `ChatController` | 保存图执行状态快照，支持中断恢复 |
| `StreamingOutput` | Graph Streaming | `PlannerNode`、`ReporterNode` | LLM 流式输出封装，实现打字机效果 |
| `JinaCrawlerService` | Tool Calling | `BackgroundInvestigationNode` | 网页内容抓取 |
| `SearchService` | Tool Calling | `SearchBeanUtil`、各研究节点 | 统一搜索引擎接口 |
