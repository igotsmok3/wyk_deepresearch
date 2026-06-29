# DeepResearch 项目设计模式分析

## 目录

1. [工厂模式（Factory Pattern）](#1-工厂模式factory-pattern)
2. [策略模式（Strategy Pattern）](#2-策略模式strategy-pattern)
3. [责任链模式（Chain of Responsibility）](#3-责任链模式chain-of-responsibility)
4. [模板方法模式（Template Method Pattern）](#4-模板方法模式template-method-pattern)
5. [建造者模式（Builder Pattern）](#5-建造者模式builder-pattern)
6. [单例模式（Singleton Pattern）](#6-单例模式singleton-pattern)
7. [代理模式（Proxy Pattern）](#7-代理模式proxy-pattern)
8. [适配器模式（Adapter Pattern）](#8-适配器模式adapter-pattern)
9. [门面模式（Facade Pattern）](#9-门面模式facade-pattern)
10. [命令模式（Command Pattern）](#10-命令模式command-pattern)
11. [组合模式（Composite Pattern）](#11-组合模式composite-pattern)
12. [观察者模式（Observer Pattern）](#12-观察者模式observer-pattern)
13. [总结](#13-总结)

---

## 1. 工厂模式（Factory Pattern）

工厂模式将对象的**创建逻辑**与使用逻辑分离，客户端只需调用工厂方法，无需了解具体实现细节。项目中有三处典型应用。

### 1.1 McpProviderFactory — MCP 工具提供者工厂

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/service/McpProviderFactory.java`

封装了 MCP（Model Context Protocol）异步客户端初始化的复杂逻辑，为不同的 Agent 节点按需创建工具提供者实例。

```java
@Service
@ConditionalOnProperty(prefix = McpAssignNodeProperties.MCP_ASSIGN_NODE_PREFIX, name = "enabled", havingValue = "true")
public class McpProviderFactory {

    private final Function<OverAllState, Map<String, McpAssignNodeProperties.McpServerConfig>> mcpConfigProvider;
    private final McpAsyncClientConfigurer mcpAsyncClientConfigurer;
    private final McpClientCommonProperties commonProperties;
    private final WebClient.Builder webClientBuilderTemplate;
    private final ObjectMapper objectMapper;

    public AsyncMcpToolCallbackProvider createProvider(OverAllState state, String agentName) {
        return McpClientUtil.createMcpProvider(state, agentName, mcpConfigProvider,
                mcpAsyncClientConfigurer, commonProperties, webClientBuilderTemplate, objectMapper);
    }
}
```

**使用方**：`ResearcherNode` 和 `CoderNode` 直接调用 `createProvider(state, agentName)`，不需要关心 MCP 客户端的配置细节。

---

### 1.2 ProfessionalKbApiClientFactory — 知识库 API 客户端工厂

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/rag/kb/ProfessionalKbApiClientFactory.java`

根据配置文件中的 `provider` 字段，动态选择并实例化不同的知识库客户端（DashScope 或 Custom）。

```java
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class ProfessionalKbApiClientFactory {

    public ProfessionalKbApiClient createClient(
            RagProperties.ProfessionalKnowledgeBases.KnowledgeBase knowledgeBase) {

        String provider = knowledgeBase.getApi().getProvider();
        if (provider == null) provider = "custom";

        switch (provider.toLowerCase()) {
            case "dashscope":
                return new DashScopeKbApiClient(restClient, knowledgeBase.getApi());
            case "custom":
            default:
                return new CustomKbApiClient(restClient, knowledgeBase.getApi());
        }
    }

    // 批量创建并过滤可用客户端
    public Map<String, ProfessionalKbApiClient> createClients(
            List<RagProperties.ProfessionalKnowledgeBases.KnowledgeBase> knowledgeBases) {
        Map<String, ProfessionalKbApiClient> clients = new HashMap<>();
        for (var kb : knowledgeBases) {
            if (kb.isEnabled() && "api".equalsIgnoreCase(kb.getType())) {
                ProfessionalKbApiClient client = createClient(kb);
                if (client != null && client.isAvailable()) {
                    clients.put(kb.getId(), client);
                }
            }
        }
        return clients;
    }
}
```

**使用方**：`ProfessionalKbApiStrategy` 在初始化时调用 `createClients()`，得到一个可用客户端 Map，之后按知识库 ID 路由查询。

---

### 1.3 MessageFactory — 消息对象工厂（函数式接口）

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/serializer/MessageFactory.java`

使用 `@FunctionalInterface` 定义消息创建契约，支持 Lambda 传入，简洁地适配不同类型的 Spring AI `Message`。

```java
@FunctionalInterface
public interface MessageFactory {
    Message create(String textContent,
                   Map<String, Object> metadata,
                   List<AssistantMessage.ToolCall> toolCalls,
                   List<ToolResponseMessage.ToolResponse> toolResponses);
}
```

**使用场景**：反序列化阶段根据消息类型（USER / ASSISTANT / TOOL）选择不同的 `MessageFactory` Lambda，统一创建 `Message` 实例。

---

## 2. 策略模式（Strategy Pattern）

策略模式定义一族可互换的算法，封装在独立类中，客户端在运行时按需选择。项目中 RAG 检索与融合均采用此模式，便于灵活扩展数据源和算法。

### 2.1 RetrievalStrategy — 检索策略接口

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/rag/strategy/RetrievalStrategy.java`

```java
public interface RetrievalStrategy {
    List<Document> retrieve(String query, Map<String, Object> options);
    String getStrategyName();
}
```

**三个具体策略**：

| 策略类 | getStrategyName() | 数据来源 |
|--------|-------------------|----------|
| `UserFileRetrievalStrategy` | `"userFile"` | 用户上传文件（按 session_id 过滤） |
| `ProfessionalKbApiStrategy` | `"professionalKbApi"` | 外部知识库 REST API |
| `ProfessionalKbEsStrategy` | `"professionalKbEs"` | Elasticsearch 专业知识库 |

**UserFileRetrievalStrategy 示例**（`src/.../rag/strategy/UserFileRetrievalStrategy.java`）：

```java
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class UserFileRetrievalStrategy implements RetrievalStrategy {

    @Override
    public String getStrategyName() { return "userFile"; }

    @Override
    public List<Document> retrieve(String query, Map<String, Object> options) {
        String sessionId = (String) options.get("session_id");
        if (sessionId == null || sessionId.isBlank()) return List.of();

        Map<String, Object> ragOptions = new HashMap<>(options);
        ragOptions.put("source_type", SourceTypeEnum.USER_UPLOAD.getValue());
        ragOptions.put("session_id", sessionId);

        return hybridRagProcessor.process(new Query(query), ragOptions);
    }
}
```

**ProfessionalKbApiStrategy 核心片段**（`src/.../rag/strategy/ProfessionalKbApiStrategy.java`）：

```java
@Override
public List<Document> retrieve(String query, Map<String, Object> options) {
    // 1. 查询扩展/翻译预处理
    List<Query> processedQueries = hybridRagProcessor.preProcess(new Query(query), options);

    List<Document> allDocuments = new ArrayList<>();
    List<String> selectedKbIds = getSelectedKnowledgeBaseIds(options);

    // 2. 对每个扩展查询 × 每个知识库并行搜索
    for (Query q : processedQueries) {
        for (String kbId : selectedKbIds) {
            allDocuments.addAll(searchKnowledgeBase(kbId, q.text(), options));
        }
    }

    // 3. 后处理（去重、重排）
    return hybridRagProcessor.postProcess(allDocuments, options);
}
```

**在 RagNode 中的使用**（`src/.../node/RagNode.java`）：

```java
// 遍历所有注入的策略，收集各自的检索结果
List<List<Document>> allResults = new ArrayList<>();
for (RetrievalStrategy strategy : retrievalStrategies) {
    allResults.add(strategy.retrieve(queryText, options));
}
// 交给 FusionStrategy 融合
documents = fusionStrategy.fuse(allResults);
```

---

### 2.2 FusionStrategy — 结果融合策略接口

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/rag/strategy/FusionStrategy.java`

```java
public interface FusionStrategy {
    List<Document> fuse(List<List<Document>> results);
    String getStrategyName();
}
```

**具体实现 — RrfFusionStrategy**（`src/.../rag/strategy/RrfFusionStrategy.java`）

实现了 **RRF（Reciprocal Rank Fusion）** 算法：将多个排名列表中各文档的倒数排名分数累加，重新排序后返回 Top-K。

```java
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class RrfFusionStrategy implements FusionStrategy, DocumentPostProcessor {

    private final int k;           // RRF 常数，默认 60
    private final int defaultTopK;
    private final double defaultThreshold;

    @Override
    public String getStrategyName() { return "rrf"; }

    @Override
    public List<Document> fuse(List<List<Document>> results) {
        if (results == null || results.isEmpty()) return List.of();
        if (results.size() == 1) return results.get(0);
        return fuseInternal(results, defaultTopK, defaultThreshold);
    }

    // RRF 核心：score(doc) = Σ 1/(k + rank_i)
    private List<Document> fuseInternal(List<List<Document>> results, int topK, double threshold) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> documentMap = new HashMap<>();

        for (List<Document> resultList : results) {
            for (int i = 0; i < resultList.size(); i++) {
                Document doc = resultList.get(i);
                String docId = getDocumentId(doc);
                rrfScores.merge(docId, 1.0 / (k + i + 1), Double::sum);
                documentMap.putIfAbsent(docId, doc);
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .filter(e -> e.getValue() >= threshold)
                .limit(topK)
                .map(e -> documentMap.get(e.getKey()))
                .collect(Collectors.toList());
    }
}
```

`RrfFusionStrategy` 同时实现了 `DocumentPostProcessor`，使其既可作为独立融合策略，也可作为 RAG 后处理管道中的一个处理步骤，体现了接口的灵活组合。

---

## 3. 责任链模式（Chain of Responsibility）

图中每条有条件的边都对应一个 `Dispatcher`（EdgeAction），它读取当前状态并返回下一个节点的 ID，形成动态路由链。每个 Dispatcher 只负责自己的那一段决策，共同构成完整的处理链路。

**公共接口**：`com.alibaba.cloud.ai.graph.action.EdgeAction`（由 spring-ai-alibaba-graph 提供）

项目中共有 **8 个 Dispatcher**，文件均位于 `src/main/java/com/alibaba/cloud/ai/example/deepresearch/dispatcher/`。

### 典型实现

**CoordinatorDispatcher** — 根据协调器结论选择下一节点：

```java
public class CoordinatorDispatcher implements EdgeAction {
    @Override
    public String apply(OverAllState state) {
        // 协调器节点写入 coordinator_next_node，此处读取并路由
        return (String) state.value("coordinator_next_node", END);
    }
}
```

**ProfessionalKbDispatcher** — 带业务判断的路由：

```java
public class ProfessionalKbDispatcher implements EdgeAction {
    @Override
    public String apply(OverAllState state) {
        Boolean needKb = state.value("use_professional_kb", false);
        // 需要专业知识库时走 RAG，否则直接报告
        return Boolean.TRUE.equals(needKb) ? "professional_kb_rag" : "reporter";
    }
}
```

**ResearchTeamDispatcher** — 循环或终止的判断：

```java
public class ResearchTeamDispatcher implements EdgeAction {
    @Override
    public String apply(OverAllState state) throws Exception {
        // 默认返回 planner 继续规划，条件满足后才进入 human_feedback
        return (String) state.value("research_team_next_node", "planner");
    }
}
```

### 链式组装（DeepResearchConfiguration）

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/config/DeepResearchConfiguration.java`

```java
stateGraph
    .addEdge(START, "short_user_role_memory")
    .addConditionalEdges("short_user_role_memory",
            edge_async(new ShortUserRoleMemoryDispatcher()),
            Map.of("coordinator", "coordinator", END, END))
    .addConditionalEdges("coordinator",
            edge_async(new CoordinatorDispatcher()),
            Map.of("rewrite_multi_query", "rewrite_multi_query", END, END))
    .addConditionalEdges("rewrite_multi_query",
            edge_async(new RewriteAndMultiQueryDispatcher()),
            Map.of("background_investigator", "background_investigator",
                   "user_file_rag", "user_file_rag", END, END))
    .addConditionalEdges("background_investigator",
            edge_async(new BackgroundInvestigationDispatcher()),
            Map.of("reporter", "reporter", "planner", "planner", END, END))
    // ... 更多条件边
```

**各 Dispatcher 路由职责一览**：

| Dispatcher | 读取的状态键 | 可选目标节点 |
|------------|-------------|------------|
| `ShortUserRoleMemoryDispatcher` | `short_user_role_next_node` | `coordinator` / END |
| `CoordinatorDispatcher` | `coordinator_next_node` | `rewrite_multi_query` / END |
| `RewriteAndMultiQueryDispatcher` | `rewrite_multi_query_next_node` | `background_investigator` / `user_file_rag` / END |
| `BackgroundInvestigationDispatcher` | `background_investigation_next_node` | `reporter` / `planner` / END |
| `InformationDispatcher` | `information_next_node` | `planner` / END |
| `HumanFeedbackDispatcher` | `human_feedback_next_node` | `planner` / END |
| `ResearchTeamDispatcher` | `research_team_next_node` | `planner`（默认循环）|
| `ProfessionalKbDispatcher` | `use_professional_kb` | `professional_kb_rag` / `reporter` |

---

## 4. 模板方法模式（Template Method Pattern）

模板方法模式在父类（或接口）中定义算法骨架，将可变步骤交给子类或具体实现覆盖。

### 4.1 DefaultHybridRagProcessor — RAG 处理管道

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/rag/core/DefaultHybridRagProcessor.java`

`process()` 是模板方法，固定了 RAG 的四阶段流程；各阶段（`preProcess`、`hybridRetrieve`、`postProcess`）是可按需配置的"钩子步骤"。

```java
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class DefaultHybridRagProcessor implements HybridRagProcessor {

    // 模板方法：固定四阶段流程
    @Override
    public List<Document> process(Query query, Map<String, Object> options) {

        // 步骤 1：查询预处理（翻译 → 扩展 → HyDE）
        List<Query> processedQueries = preProcess(query, options);

        // 步骤 2：构建 ES 过滤条件
        Query filterExpression = buildFilterExpression(options);

        // 步骤 3：混合检索（BM25 + KNN）
        List<Document> documents = hybridRetrieve(processedQueries, filterExpression, options);

        // 步骤 4：后处理（RRF 重排或截断）
        return postProcess(documents, options);
    }

    @Override
    public List<Query> preProcess(Query query, Map<String, Object> options) {
        List<Query> queries = new ArrayList<>();
        queries.add(query);

        // 子步骤 1：查询翻译（可选）
        if (queryTransformer != null) {
            queries = queries.stream()
                    .map(q -> queryTransformer.transform(q))
                    .collect(Collectors.toList());
        }
        // 子步骤 2：多查询扩展（可选）
        if (queryExpander != null) {
            queries = queries.stream()
                    .flatMap(q -> queryExpander.expand(q).stream())
                    .collect(Collectors.toList());
        }
        // 子步骤 3：HyDE 假设文档嵌入（可选）
        if (hyDeTransformer != null) {
            queries = queries.stream()
                    .map(q -> hyDeTransformer.transform(q))
                    .collect(Collectors.toList());
        }
        return queries;
    }

    @Override
    public List<Document> hybridRetrieve(List<Query> queries,
            co.elastic.clients.elasticsearch._types.query_dsl.Query filterExpression,
            Map<String, Object> options) {
        List<Document> allDocuments = new ArrayList<>();
        for (Query query : queries) {
            if (hybridRetriever != null) {
                // ES 混合检索：BM25 关键词 + KNN 向量
                allDocuments.addAll(hybridRetriever.retrieve(query, filterExpression));
            } else {
                // 降级：纯向量相似度搜索
                allDocuments.addAll(performVectorSearch(query, options));
            }
        }
        return deduplicateDocuments(allDocuments);
    }

    @Override
    public List<Document> postProcess(List<Document> documents, Map<String, Object> options) {
        if (ragProperties.getPipeline().isRerankEnabled()) {
            // RRF 重排
            return rrfFusionStrategy.process(new Query(""), documents);
        }
        if (documentPostProcessor != null) {
            // SelectFirst 截断
            return documentPostProcessor.process(null, documents);
        }
        return documents;
    }
}
```

**模板接口**（`HybridRagProcessor`）定义各步骤的契约，`DefaultHybridRagProcessor` 提供默认实现，后续可通过实现同一接口来替换整条管道。

---

### 4.2 DeepResearchStateSerializer — 序列化模板

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/serializer/DeepResearchStateSerializer.java`

继承父类 `PlainTextStateSerializer`，覆盖读写方法，解决原实现在大数据量下的 UTF 长度限制问题。

```java
public class DeepResearchStateSerializer extends PlainTextStateSerializer {

    @Override
    public void writeData(Map<String, Object> data, ObjectOutput out) throws IOException {
        // 将状态序列化为 JSON 字节数组写出，避免 UTF 字符串长度限制
        String json = objectMapper.writeValueAsString(data);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        out.writeInt(jsonBytes.length);   // 先写长度
        out.write(jsonBytes);             // 再写内容
    }

    @Override
    public Map<String, Object> readData(ObjectInput in) throws IOException {
        int length = in.readInt();
        byte[] jsonBytes = new byte[length];
        in.readFully(jsonBytes);
        return objectMapper.readValue(new String(jsonBytes, StandardCharsets.UTF_8),
                new TypeReference<Map<String, Object>>() {});
    }
}
```

父类定义了序列化的整体框架，子类只需实现 `writeData` / `readData` 两个具体步骤，符合模板方法的"开放-封闭"原则。

---

## 5. 建造者模式（Builder Pattern）

建造者模式通过链式 API 逐步构建复杂对象，使构造过程更清晰，并将构建逻辑与表示分离。

### 5.1 FluxConverter.builder() — 流式响应构建器

在 `ResearcherNode`、`CoderNode`、`PlannerNode`、`RagNode` 等多个节点中均使用此 Builder 构建异步流。

**ResearcherNode 示例**（`src/.../node/ResearcherNode.java`）：

```java
Flux<GraphResponse<StreamingOutput>> generator = FluxConverter.builder()
        .startingNode(nodeNum)
        .startingState(state)
        .mapResult(response -> {
            String content = response.getResult().getOutput().getText();
            assignedStep.setExecutionStatus(
                    ReflectionUtil.getCompletionStatus(reflectionProcessor != null, nodeName));
            assignedStep.setExecutionRes(Objects.requireNonNull(content));
            updated.put("researcher_content_" + executorNodeId, content);
            return updated;
        })
        .buildWithChatResponse(streamResult);  // 携带完整 ChatResponse 的构建方式
```

**PlannerNode 示例**（`src/.../node/PlannerNode.java`）：

```java
Flux<GraphResponse<StreamingOutput>> generator = FluxConverter.builder()
        .startingNode(prefix)
        .startingState(state)
        .mapResult(response -> Map.of("planner_content",
                Objects.requireNonNull(response.getResult().getOutput().getText())))
        .buildWithChatResponse(streamResult);
```

Builder 提供了 `build()` 和 `buildWithChatResponse()` 两种终止方法，适配不同场景的流式响应类型。

---

### 5.2 BeanOutputConverter — 类型化输出构建器

在 `PlannerNode` 和 `ReflectionProcessor` 中使用，将 LLM 的文本输出转换为具体 Java 对象。

```java
// PlannerNode — 将 LLM 响应转换为 Plan 对象
this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<Plan>() {});

// ReflectionProcessor — 将 LLM 响应转换为 ReflectionResult 对象
this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<ReflectionResult>() {});
```

---

### 5.3 JsonMapper.builder() — ObjectMapper 构建器

**DeepResearchStateSerializer** 中使用 Jackson 的 Builder API 精细配置序列化行为：

```java
this.objectMapper = JsonMapper.builder()
        .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
        .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false)
        .visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
        .build();
```

---

## 6. 单例模式（Singleton Pattern）

Spring 容器默认以单例作用域（`singleton`）管理所有 `@Component`、`@Service`、`@Bean` 等标注的 Bean，整个应用生命周期内只创建一个实例，全局共享。

### 关键单例 Bean

```java
// 工厂 Bean — 全局唯一，按需创建 MCP 提供者
@Service
public class McpProviderFactory { ... }

// 策略 Bean — 无状态，单例安全
@Component
public class UserFileRetrievalStrategy implements RetrievalStrategy { ... }

@Component
public class RrfFusionStrategy implements FusionStrategy, DocumentPostProcessor { ... }

// 处理器 Bean — 持有配置，全局共享
@Component
public class DefaultHybridRagProcessor implements HybridRagProcessor { ... }

// 客户端工厂 Bean
@Component
public class ProfessionalKbApiClientFactory { ... }
```

Spring 的 DI 容器本身即是单例注册表，避免了手动实现 `getInstance()` 的繁琐，并天然支持线程安全（前提是 Bean 本身无可变状态）。

---

## 7. 代理模式（Proxy Pattern）

代理模式为真实对象提供一个替代品或占位符，控制对其的访问。

### 7.1 @ConditionalOnProperty — 条件化 Bean 代理

通过 `@ConditionalOnProperty` 注解，Spring 在不满足条件时不创建 Bean，注入点得到 `null` 或默认值，相当于一个"空代理"控制访问入口。

```java
@Service
@ConditionalOnProperty(
    prefix = McpAssignNodeProperties.MCP_ASSIGN_NODE_PREFIX,
    name = "enabled",
    havingValue = "true"   // 仅当 mcp.enabled=true 时才创建此 Bean
)
public class McpProviderFactory { ... }
```

所有 RAG 相关 Bean 同理：

```java
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class DefaultHybridRagProcessor implements HybridRagProcessor { ... }
```

---

### 7.2 RagNodeService — 工厂方法代理

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/service/RagNodeService.java`

根据运行时可用组件选择实现，向调用方隐藏了实现切换逻辑：

```java
@Service
public class RagNodeService {

    @Autowired(required = false)
    private HybridRagProcessor hybridRagProcessor;      // 新实现（优先）

    @Autowired(required = false)
    private UserFileRetrievalStrategy userFileRetrievalStrategy;  // 旧实现（兼容）

    // 代理创建逻辑：调用方只知道"我要一个 RAG 节点"
    public AsyncNodeAction createUserFileRagNode() {
        if (hybridRagProcessor != null) {
            return node_async(new RagNode(hybridRagProcessor, ragAgent));
        }
        return node_async(
            new RagNode(userFileRetrievalStrategy != null
                    ? List.of(userFileRetrievalStrategy) : List.of(),
                    fusionStrategy, ragAgent));
    }
}
```

---

## 8. 适配器模式（Adapter Pattern）

适配器模式将一个类的接口转换为另一个接口，使原本不兼容的类可以协同工作。

### 8.1 RagNode — 新旧接口适配器

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/node/RagNode.java`

`RagNode` 通过两套构造函数，将不同的检索接口适配到统一的 `NodeAction` 调用界面：

```java
public class RagNode implements NodeAction {

    private final HybridRagProcessor hybridRagProcessor;       // 新接口
    private final List<RetrievalStrategy> retrievalStrategies; // 旧接口
    private final FusionStrategy fusionStrategy;

    // 旧版构造（保持向后兼容）
    public RagNode(List<RetrievalStrategy> retrievalStrategies,
                   FusionStrategy fusionStrategy,
                   ChatClient ragAgent) {
        this.retrievalStrategies = retrievalStrategies;
        this.fusionStrategy = fusionStrategy;
        this.hybridRagProcessor = null;
        this.ragAgent = ragAgent;
    }

    // 新版构造（统一处理器）
    public RagNode(HybridRagProcessor hybridRagProcessor, ChatClient ragAgent) {
        this.hybridRagProcessor = hybridRagProcessor;
        this.retrievalStrategies = null;
        this.fusionStrategy = null;
        this.ragAgent = ragAgent;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<Document> documents;

        if (hybridRagProcessor != null) {
            // 使用新接口
            documents = hybridRagProcessor.process(new Query(queryText), options);
        } else if (retrievalStrategies != null && fusionStrategy != null) {
            // 使用旧接口（适配）
            List<List<Document>> allResults = new ArrayList<>();
            for (RetrievalStrategy strategy : retrievalStrategies) {
                allResults.add(strategy.retrieve(queryText, options));
            }
            documents = fusionStrategy.fuse(allResults);
        }
        // ...
    }
}
```

---

### 8.2 DeepResearchStateSerializer — 序列化格式适配器

将 Spring AI Graph 要求的 `PlainTextStateSerializer` 接口适配为基于 JSON 字节数组的存储格式，解决原实现的 UTF 字符串长度上限问题。自定义的 `MessageDeserializer` 和 `DeepResearchDeserializer` 将 JSON 数据适配为 Spring AI 的 `Message` / `OverAllState` 对象。

---

## 9. 门面模式（Facade Pattern）

门面模式为复杂子系统提供一个简化的统一接口，降低调用方的使用成本。

### 9.1 RagNodeService — RAG 节点门面

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/service/RagNodeService.java`

汇聚了 RAG 所需的所有依赖（`HybridRagProcessor`、多种 `RetrievalStrategy`、`FusionStrategy`），向图配置层暴露两个极简方法：

```java
@Service
public class RagNodeService {

    @Autowired(required = false) private ChatClient ragAgent;
    @Autowired(required = false) private UserFileRetrievalStrategy userFileRetrievalStrategy;
    @Autowired(required = false) private ProfessionalKbEsStrategy professionalKbEsStrategy;
    @Autowired(required = false) private FusionStrategy fusionStrategy;
    @Autowired(required = false) private HybridRagProcessor hybridRagProcessor;

    public AsyncNodeAction createUserFileRagNode() { ... }

    public AsyncNodeAction createProfessionalKbRagNode() { ... }
}
```

`DeepResearchConfiguration` 中只需调用：

```java
.addNode("user_file_rag", ragNodeService.createUserFileRagNode())
.addNode("professional_kb_rag", ragNodeService.createProfessionalKbRagNode())
```

完全不需要了解内部 RAG 组件的选择和组装逻辑。

---

### 9.2 DefaultHybridRagProcessor — RAG 管道门面

对上层调用方（各 `RetrievalStrategy`）屏蔽了查询翻译、多查询扩展、HyDE、混合检索、RRF 重排等所有内部步骤，只暴露一个 `process()` 入口。

---

## 10. 命令模式（Command Pattern）

命令模式将请求（操作）封装为对象，支持参数化、队列化和异步执行。

### 10.1 NodeAction — 节点命令

**接口**：`com.alibaba.cloud.ai.graph.action.NodeAction`

每个图节点是一个命令对象，实现 `apply(OverAllState)` 方法，接收当前状态，执行操作，返回状态更新 Map。项目中共有 **14 个** NodeAction 实现：

| 节点类 | 职责 |
|--------|------|
| `ShortUserRoleMemoryNode` | 短期用户角色记忆读取 |
| `CoordinatorNode` | 意图识别与任务协调 |
| `RewriteAndMultiQueryNode` | 查询改写与多查询扩展 |
| `BackgroundInvestigationNode` | 背景调研（Web 搜索）|
| `RagNode` | 检索增强生成 |
| `PlannerNode` | 研究计划生成 |
| `ProfessionalKbDecisionNode` | 判断是否需要专业知识库 |
| `InformationNode` | 信息汇总 |
| `HumanFeedbackNode` | 人工反馈中断 |
| `ResearchTeamNode` | 研究团队调度 |
| `ParallelExecutorNode` | 并行任务分发 |
| `ReporterNode` | 最终报告生成 |
| `ResearcherNode` | 并行研究 Agent |
| `CoderNode` | 并行编码 Agent |

**ResearcherNode 核心结构**（`src/.../node/ResearcherNode.java`）：

```java
public class ResearcherNode implements NodeAction {

    private final ChatClient researchAgent;
    private final String executorNodeId;

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Plan currentPlan = StateUtil.getPlan(state);
        Map<String, Object> updated = new HashMap<>();

        // 从计划中找到分配给本节点的步骤
        Plan.Step assignedStep = findAssignedStep(currentPlan);
        if (assignedStep == null) {
            logger.info("No remaining steps to be executed by {}", nodeName);
            return updated;
        }

        // 构建消息并调用 LLM
        List<Message> messages = buildMessages(state, assignedStep);
        Flux<ChatResponse> streamResult = researchAgent.prompt()
                .messages(messages)
                .stream()
                .chatResponse();

        // 构建流式响应，将结果写回状态
        Flux<GraphResponse<StreamingOutput>> generator = FluxConverter.builder()
                .startingNode(nodeNum)
                .startingState(state)
                .mapResult(response -> {
                    String content = response.getResult().getOutput().getText();
                    assignedStep.setExecutionRes(content);
                    updated.put("researcher_content_" + executorNodeId, content);
                    return updated;
                })
                .buildWithChatResponse(streamResult);

        return Map.of(NodeConstant.GENERATOR, generator);
    }
}
```

---

### 10.2 EdgeAction — 路由命令

**接口**：`com.alibaba.cloud.ai.graph.action.EdgeAction`

每个 Dispatcher 是一个路由命令，`apply()` 方法返回下一节点名称（即"命令的执行结果"）。见第 3 节的责任链部分。

---

## 11. 组合模式（Composite Pattern）

组合模式将对象组织成树形结构，使客户端对单个对象和组合对象的处理方式一致。

### StateGraph — 节点与边的组合树

**文件**：`src/main/java/com/alibaba/cloud/ai/example/deepresearch/config/DeepResearchConfiguration.java`

`StateGraph` 将若干叶子节点（NodeAction）和条件边（EdgeAction）组合为一个可执行的有向图，外部只需调用 `compile()` 后的 `fluxStream()` 即可驱动整个工作流。

```java
StateGraph stateGraph = new StateGraph("deep research", keyStrategyFactory,
        new DeepResearchStateSerializer(OverAllState::new))
    // 单一叶子节点
    .addNode("coordinator",      node_async(new CoordinatorNode(...)))
    .addNode("planner",          node_async(new PlannerNode(plannerAgent)))
    .addNode("reporter",         node_async(new ReporterNode(reporterAgent)))
    // 动态组合：并行节点块（数量由配置决定）
    .addNode("user_file_rag",       ragNodeService.createUserFileRagNode())
    .addNode("professional_kb_rag", ragNodeService.createProfessionalKbRagNode());

// 动态添加 N 个并行 researcher 节点和 M 个 coder 节点
for (int i = 0; i < parallelResearcherCount; i++) {
    stateGraph
        .addNode("researcher_" + i, node_async(new ResearcherNode(...)))
        .addEdge("parallel_executor", "researcher_" + i)
        .addEdge("researcher_" + i, "research_team");
}

for (int i = 0; i < parallelCoderCount; i++) {
    stateGraph
        .addNode("coder_" + i, node_async(new CoderNode(...)))
        .addEdge("parallel_executor", "coder_" + i)
        .addEdge("coder_" + i, "research_team");
}
```

整个图（Composite）与单个节点（Leaf）都通过统一的 `addNode` / `addEdge` API 操作，调用方无需区分它们的内部结构。

---

## 12. 观察者模式（Observer Pattern）

项目使用 Project Reactor 的 `Flux` 作为响应式流，实现了基于推送的异步观察者机制：发布者（LLM 流）产生事件，订阅者（图运行时）通过回调处理事件。

### Flux 异步流观察

**ResearcherNode 中的 Flux 链**（`src/.../node/ResearcherNode.java`）：

```java
// 发布者：LLM 流式输出
Flux<ChatResponse> streamResult = researchAgent.prompt()
        .messages(messages)
        .stream()
        .chatResponse()
        .doOnError(error -> StateUtil.handleStepError(assignedStep, nodeName, error, logger));

// 观察者链：将 ChatResponse 转换为图状态更新
Flux<GraphResponse<StreamingOutput>> generator = FluxConverter.builder()
        .startingNode(nodeNum)
        .startingState(state)
        .mapResult(response -> {
            // 每收到一个 token/响应块时触发
            String content = response.getResult().getOutput().getText();
            assignedStep.setExecutionRes(content);
            updated.put("researcher_content_" + executorNodeId, content);
            return updated;
        })
        .buildWithChatResponse(streamResult);
```

**RagNode 中带超时重试的 Flux 链**（`src/.../node/RagNode.java`）：

```java
Flux<ChatResponse> streamResult = ragAgent.prompt()
        .messages(new UserMessage(contextBuilder.toString()))
        .user(queryText)
        .stream()
        .chatResponse()
        .timeout(Duration.ofSeconds(180))   // 观察超时事件
        .retry(2);                          // 失败时重新订阅

Flux<GraphResponse<StreamingOutput>> generatedContent = FluxConverter.builder()
        .startingNode("rag_llm_stream")
        .startingState(state)
        .mapResult(response -> Map.of("rag_content",
                Objects.requireNonNull(response.getResult().getOutput().getText())))
        .build(streamResult);
```

`doOnError`、`timeout`、`retry` 等操作符均是对流事件的"观察钩子"，体现了观察者模式在响应式编程中的自然延伸。

---

## 13. 总结

| 设计模式 | 数量 | 核心文件 | 解决的问题 |
|---------|------|---------|-----------|
| 工厂模式 | 3 处 | `McpProviderFactory`、`ProfessionalKbApiClientFactory`、`MessageFactory` | 封装复杂对象的创建逻辑 |
| 策略模式 | 5+ 处 | `RetrievalStrategy` 及 3 个实现、`FusionStrategy` + `RrfFusionStrategy` | 支持多种检索算法和融合算法的运行时切换 |
| 责任链模式 | 8 处 | 所有 `*Dispatcher` 类 | 动态路由图节点，实现可扩展的流程控制 |
| 模板方法模式 | 2 处 | `DefaultHybridRagProcessor`、`DeepResearchStateSerializer` | 固定算法骨架，允许步骤级别的扩展 |
| 建造者模式 | 3 处 | `FluxConverter.builder()`、`BeanOutputConverter`、`JsonMapper.builder()` | 链式构建复杂对象，提升可读性 |
| 单例模式 | 10+ 处 | 所有 Spring `@Component`/`@Service` Bean | 全局共享无状态服务，节省资源 |
| 代理模式 | 2 处 | `@ConditionalOnProperty` Bean、`RagNodeService` | 按条件控制对象的访问和创建 |
| 适配器模式 | 2 处 | `RagNode`（新旧接口）、`DeepResearchStateSerializer`（格式转换）| 新旧接口兼容、格式适配 |
| 门面模式 | 2 处 | `RagNodeService`、`DefaultHybridRagProcessor` | 简化复杂子系统的调用界面 |
| 命令模式 | 22+ 处 | 14 个 `NodeAction` 实现、8 个 `EdgeAction` 实现 | 封装可执行操作，支持异步调度 |
| 组合模式 | 1 处 | `DeepResearchConfiguration`（StateGraph 装配）| 将节点和边组合为统一的有向图结构 |
| 观察者模式 | 多处 | 所有节点中的 `Flux` 流链 | 基于推送的异步事件处理与流式输出 |

### 架构层面的观察

1. **图驱动架构**：整个工作流以 `StateGraph` 为核心，命令模式（NodeAction）+ 责任链模式（EdgeAction）共同驱动执行流程，解耦了节点实现与路由逻辑。

2. **RAG 的分层设计**：策略模式（检索）+ 策略模式（融合）+ 模板方法（管道）+ 门面（统一入口），形成清晰的四层 RAG 架构，每层均可独立扩展。

3. **渐进式兼容**：适配器模式贯穿 `RagNode` 和 `RagNodeService`，使新 `HybridRagProcessor` 接口与旧的多策略接口并存，支持平滑迁移。

4. **响应式全链路**：Reactor `Flux` 贯穿从 LLM 调用到 SSE 推送的完整链路，观察者模式天然融入响应式范式，支持背压、超时、重试。
