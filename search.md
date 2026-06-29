# Search 实现详解

## 一、整体架构

Search 实现分为四层，从用户问题到最终检索结果经过以下处理链：

```
用户查询
  │
  ▼
[RewriteAndMultiQueryNode] ── 查询优化（压缩 → 重写 → 扩展）
  │
  ▼
[BackgroundInvestigationNode / ResearcherNode] ── 搜索执行
  │  ├─ SmartAgentSelectionHelperService ── 问题分类 & 平台选择
  │  └─ SearchInfoService ── 调度搜索并组装结果
  │       ├─ SearchFilterService ── 传统搜索引擎 + 信任过滤
  │       └─ ToolCallingSearchService ── 专用领域工具调用
  ▼
site_information（Map 列表，含 title/content/url/weight/icon）
```

---

## 二、查询优化层

**文件**: `node/RewriteAndMultiQueryNode.java`

用户原始问题在进入搜索前经过三步 pipeline 处理：

| 步骤 | 组件 | 作用 |
|------|------|------|
| 1 | `CompressionQueryTransformer` | 消解多轮对话中的代词引用（"上面那个框架" → "Spring AI"）|
| 2 | `RewriteQueryTransformer` | 语义重写，去除口语化表达，提升精准度 |
| 3 | `MultiQueryExpander` | 扩展为 N 条语义变体（含原始问题），并行检索提升召回率 |

步骤 1 仅在 `short-term-memory.enabled=true` 且存在历史消息时执行。
扩展数量由请求参数 `optimizeQueryNum` 控制（范围 [0, 5]）。

优化后的查询列表写入 `OverAllState["optimize_queries"]`，供后续节点并行使用。

路由逻辑：用户上传文件时走 `user_file_rag`，否则走 `background_investigator`。

---

## 三、搜索引擎管理

### 3.1 可用引擎配置

**文件**: `config/DeepResearchProperties.java`, `resources/application.yml`

```yaml
spring.ai.alibaba.deepresearch:
  search-list:
    - tavily
    - aliyun
    - baidu
    - serpapi
```

`search-list` 定义项目级别可用引擎白名单。`SearchBeanUtil` 根据这个列表和 Spring 容器中实际注册的 Bean 查找可用的 `SearchService`。

**文件**: `util/SearchBeanUtil.java`

```java
// 先检查是否在白名单中，再从 Spring 容器获取 Bean
Optional<SearchService> getSearchService(SearchEnum searchEnum)
Optional<SearchEnum> getFirstAvailableSearch()  // 取白名单第一个可用引擎
```

### 3.2 平台类型划分

**文件**: `model/multiagent/SearchPlatform.java`

| 类别 | 平台 | 调用方式 |
|------|------|----------|
| 传统搜索引擎 | Tavily, AliyunAISearch, Baidu, SerpAPI | `SearchFilterService` 管线 |
| 专用工具调用 | OpenAlex, OpenTripMap, TripAdvisor, Wikipedia, WorldBank, GoogleScholar | `ToolCallingSearchService` 直接调用 |

判断方法：`SmartAgentUtil.isToolCallingPlatform(platform)` 返回 true 时走工具调用分支。

---

## 四、智能平台选择（SmartAgent 模式）

**开关**: `spring.ai.alibaba.deepresearch.smart-agents.enabled=true`

SmartAgent 模式开启时，按以下决策链自动选择最合适的搜索平台：

```
问题内容
  │
  ▼ QuestionClassifierService（AI 分类）
AgentType（学术/旅游/百科/数据分析/通用）
  │
  ▼ SearchPlatformSelectionService
SearchPlatform（先查静态配置，无配置则 AI 决策）
  │
  ├─ isToolCallingPlatform? → ToolCallingSearchService
  └─ 传统平台? → SearchFilterService
```

### 4.1 问题分类

**文件**: `service/multiagent/QuestionClassifierService.java`

调用 DashScope LLM，使用 `prompts/multiagent/classifier.md` 中的 Prompt 对问题分类：

| AgentType | 触发特征 | 对应专用 Agent |
|-----------|----------|----------------|
| `ACADEMIC_RESEARCH` | 学术论文、期刊、研究、会议 | `academicResearchAgent` |
| `LIFESTYLE_TRAVEL` | 旅游、美食、景点、生活 | `lifestyleTravelAgent` |
| `ENCYCLOPEDIA` | 百科、概念、历史、定义 | `encyclopediaAgent` |
| `DATA_ANALYSIS` | 数据、统计、市场、趋势 | `dataAnalysisAgent` |
| `GENERAL_RESEARCH` | 其余 | `researchAgent` |

解析逻辑在 `SmartAgentUtil.parseAiClassification()`，基于关键词匹配 AI 返回文本。

### 4.2 搜索平台选择

**文件**: `service/multiagent/SearchPlatformSelectionService.java`

选择优先级：
1. **静态配置**（`search-platform-mapping`）：直接读 YAML，零延迟
2. **AI 动态决策**（`prompts/multiagent/search-platform-selector.md`）：无配置时调用 LLM

默认平台映射（可被配置覆盖）：

| AgentType | 默认平台 |
|-----------|----------|
| `ACADEMIC_RESEARCH` | GOOGLE_SCHOLAR |
| `LIFESTYLE_TRAVEL` | OPENTRIPMAP |
| `ENCYCLOPEDIA` | WIKIPEDIA |
| `DATA_ANALYSIS` | WORLDBANK_DATA |
| `GENERAL_RESEARCH` | TAVILY |

### 4.3 Agent 调度

**文件**: `service/multiagent/SmartAgentDispatcherService.java`

`dispatchToAgent()` 串联分类和平台选择，返回 `AgentDispatchResult`（含 ChatClient、AgentType、SearchEnum 列表）。ResearcherNode 使用分派结果选择专用 ChatClient 进行研究。

---

## 五、搜索过滤管线

### 5.1 信任权重体系

**文件**: `service/SearchFilterService.java` (abstract), `service/LocalConfigSearchFilterService.java`

**配置文件**: `resources/website-weight-config.json`

```json
[
  {"host": "github.com",    "weight": 1.0},
  {"host": "www.baidu.com", "weight": 0.9}
]
```

权重范围 [-1.0, 1.0]：
- `> 0`：可信来源，保留
- `= 0`：未知来源（默认值），保留
- `< 0`：不可信来源，`filterSearchResult()` 过滤掉

### 5.2 排列算法（"首尾高权重"策略）

`sortSearchResult()` 将结果按权重降序后，用**交叉拆分再反转拼接**的方式重排：

```
降序原始: [A(1.0), B(0.8), C(0.5), D(0.3), E(0.1)]
偶数索引 → 前段（保序）: [A, C, E]
奇数索引 → 后段（反转）: [D, B]
最终拼接:               [A, C, E, D, B]
```

效果：权重最高的来源出现在列表**首位**，第二高的出现在**末位**，利用 LLM 对序列首尾的注意力偏置，让高可信结果影响力更强。

`queryAndFilter()` 在排序基础上再剔除权重 < 0 的条目。

### 5.3 SearchFilterTool

**文件**: `tool/SearchFilterTool.java`

将 `SearchFilterService.queryAndFilter()` 封装为 Spring AI `@Tool`，可直接挂载到 ChatClient 的 tool calling 调用链，让 Agent 自主触发搜索。

---

## 六、搜索执行层

### 6.1 SearchInfoService

**文件**: `service/SearchInfoService.java`

高层协调器，统一对外暴露两个重载：

```java
// 传统搜索
searchInfo(enableSearchFilter, searchEnum, query)

// 含工具调用分支的搜索（BackgroundInvestigationNode 使用）
searchInfo(enableSearchFilter, searchEnum, query, searchPlatform)
```

执行流程：
1. 若 `searchPlatform` 是工具调用平台 → 调 `ToolCallingSearchService`
2. 否则 → 调 `SearchFilterService.queryAndFilter()`（含信任过滤）
3. 若 Jina Crawler 可用且 URL 合法 → 用完整页面正文替换搜索摘要
4. 提取 favicon.ico 作为 icon（从域名根路径推断）
5. 最多重试 3 次，间隔 500ms

### 6.2 ToolCallingSearchService

**文件**: `service/multiagent/ToolCallingSearchService.java`

路由专用领域 SearchService Bean（由 Spring 按 `@Qualifier` 注入）：

| SearchPlatform | Bean Qualifier | 数据领域 |
|----------------|----------------|----------|
| OPENALEX | `openAlex` | 学术论文 |
| OPENTRIPMAP | `openTripMapService` | 旅游景点 |
| TRIPADVISOR | `tripAdvisor` | 酒店/餐厅 |
| WIKIPEDIA | `searchWikipedia` | 百科知识 |
| WORLDBANK_DATA | `worldBankData` | 全球统计数据 |
| GOOGLE_SCHOLAR | `googleScholar` | 学术引用 |

所有专用服务默认 `enabled: false`，需在 `application.yml` 手动开启并提供 API Key。

---

## 七、图节点中的搜索调用

### 7.1 BackgroundInvestigationNode

**文件**: `node/BackgroundInvestigationNode.java`

- 对 `optimize_queries` 中每条查询独立调 `searchInfoService.searchInfo()`
- SmartAgent 开启时按问题分类选平台，否则用请求指定引擎
- 搜索结果写入 `site_information`（列表的列表）
- 对每个查询调用 `backgroundAgent` 生成背景调查小结，写入 `background_investigation_results`
- 路由：`enableDeepResearch=true` → planner，否则 → reporter

### 7.2 ResearcherNode

**文件**: `node/ResearcherNode.java`

- 从计划（Plan）中领取一个 RESEARCH 类型步骤执行
- 使用 `searchInfoService.searchInfo()` 搜索（不带工具调用分支）
- SmartAgent 开启时 `selectSmartAgent()` 选择专用 ChatClient
- 流式输出研究内容，写入 `researcher_content_N`

---

## 八、配置总览

```yaml
spring.ai.alibaba.deepresearch:
  search-list:          # 启用的传统搜索引擎列表
    - tavily
    - aliyun
    - baidu
    - serpapi
  smart-agents:
    enabled: false      # 是否启用智能平台选择
    search-platform-mapping:   # 静态平台配置（覆盖 AI 决策）
      academic_research:
        primary: openalex
      lifestyle_travel:
        primary: opentripmap
      encyclopedia:
        primary: wikipedia
      data_analysis:
        primary: worldbankdata
      general_research:
        primary: tavily

# 各搜索引擎 Bean 开关
spring.ai.alibaba.toolcalling:
  tavilysearch.enabled: true
  aliyunaisearch.enabled: true
  openalex.enabled: false      # 专用工具调用，默认关闭
  wikipedia.enabled: false
  ...
```

网站信任权重配置：`src/main/resources/website-weight-config.json`

---

## 九、关键文件索引

| 文件 | 职责 |
|------|------|
| `node/RewriteAndMultiQueryNode.java` | 查询压缩/重写/扩展 |
| `node/BackgroundInvestigationNode.java` | 背景调查阶段搜索 |
| `node/ResearcherNode.java` | 研究阶段搜索 |
| `service/SearchInfoService.java` | 搜索统一入口 |
| `service/SearchFilterService.java` | 信任权重过滤 & 排列算法 |
| `service/LocalConfigSearchFilterService.java` | 从 JSON 加载网站权重 |
| `tool/SearchFilterTool.java` | Agent tool calling 封装 |
| `util/SearchBeanUtil.java` | 搜索引擎 Bean 查找 |
| `service/multiagent/QuestionClassifierService.java` | AI 问题分类 |
| `service/multiagent/SearchPlatformSelectionService.java` | 搜索平台选择 |
| `service/multiagent/SmartAgentDispatcherService.java` | 问题→Agent 全链路调度 |
| `service/multiagent/SmartAgentSelectionHelperService.java` | 搜索选择辅助（节点使用） |
| `service/multiagent/ToolCallingSearchService.java` | 专用领域工具调用 |
| `util/multiagent/SmartAgentUtil.java` | 平台转换 & 判断工具方法 |
| `model/multiagent/SearchPlatform.java` | 搜索平台枚举定义 |
| `model/multiagent/AgentType.java` | Agent 类型枚举定义 |
| `resources/website-weight-config.json` | 网站信任权重配置 |
| `resources/prompts/multiagent/classifier.md` | 问题分类 Prompt |
| `resources/prompts/multiagent/search-platform-selector.md` | 平台选择 Prompt |
