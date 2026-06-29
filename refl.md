# Reflection 模式实现总结

## 一、设计目标

Reflection（反思）模式让 ResearcherNode / CoderNode 在完成一次执行后，由一个独立的 **reflectionAgent** 对输出结果进行质量评估。若评估不通过，节点会携带历史反馈重新执行，直到通过或达到最大重试次数。

---

## 二、整体流程

```
ResearcherNode / CoderNode 执行完毕
        │
        ▼
Step.executionStatus = "waiting_reflecting_<nodeName>"
        │
graph 继续循环，同一节点再次被调度
        │
        ▼
handleReflection() 检测到 waiting_reflecting 状态
        │
        ├─► 调用 reflectionAgent 评估结果
        │         ├─► passed=true  → 状态设为 completed，本轮跳过执行
        │         └─► passed=false → 记录 feedback，状态设为 waiting_processing
        │
        ▼（waiting_processing 时）
handleReflection() 清空旧结果，返回 continueProcessing
        │
        ▼
节点重新执行，prompt 中注入历史 feedback → 改进后的结果
        │
        └─► 重复上述评估流程，直到 passed 或达到 maxAttempts（强制通过）
```

---

## 三、执行状态机

Step.executionStatus 的值格式均为 `<前缀><nodeName>`：

| 状态前缀 | 常量 | 含义 |
|---|---|---|
| `assigned_` | `EXECUTION_STATUS_ASSIGNED_PREFIX` | Planner 分配，等待首次执行 |
| `processing_` | `EXECUTION_STATUS_PROCESSING_PREFIX` | 节点执行中 |
| `waiting_reflecting_` | `EXECUTION_STATUS_WAITING_REFLECTING` | 执行完成，等待 Reflection 评估 |
| `waiting_processing_` | `EXECUTION_STATUS_WAITING_PROCESSING` | 评估不通过，等待重新执行 |
| `completed_` | `EXECUTION_STATUS_COMPLETED_PREFIX` | 最终完成 |
| `error_` | `EXECUTION_STATUS_ERROR_PREFIX` | 执行异常 |

---

## 四、关键组件

### 4.1 配置类

**`ReflectionProperties`**（`config/ReflectionProperties.java`）

```yaml
spring.ai.alibaba.deepresearch:
  reflection:
    enabled: true      # 是否启用反思
    max-attempts: 1    # 单个 Step 最大重试次数（超出后强制通过）
```

### 4.2 数据模型

**`ReflectionResult`**（`model/dto/ReflectionResult.java`）

reflectionAgent 的 LLM 输出被反序列化为此类，同时追加到 Step 历史：

```java
class ReflectionResult {
    boolean passed;           // 是否通过
    String feedback;          // 评估意见，下次执行时注入 prompt
    String executionResult;   // 本次执行结果快照（由处理器写入，非 LLM 字段）
}
```

**`Plan.Step.reflectionHistory`**（`model/dto/Plan.java`）

```java
List<ReflectionResult> reflectionHistory;
// 列表长度 = 已经历的反思次数，同时作为重试计数器
```

### 4.3 核心处理器

**`ReflectionProcessor`**（`util/ReflectionProcessor.java`）

节点 `apply()` 开头调用 `handleReflection()`，内部状态转换逻辑：

```
handleReflection(step, nodeName, nodeType)
  ├── status == waiting_reflecting → performReflection()
  │     ├── 已达 maxAttempts → completed（强制通过）
  │     ├── evaluateStepQuality() == true  → completed
  │     └── evaluateStepQuality() == false → waiting_processing（记录 feedback）
  ├── status == waiting_processing → 清空 executionRes，返回 continueProcessing
  └── 其他（assigned / processing）→ 返回 continueProcessing
```

`evaluateStepQuality()` 调用流程：
1. 构建包含任务信息的评估 prompt
2. 调用 `reflectionAgent`（system prompt = `prompts/reflection.md`）
3. 用 `BeanOutputConverter` 将 JSON 输出反序列化为 `ReflectionResult`
4. 将结果快照写入 `step.reflectionHistory`

### 4.4 静态工具类

**`ReflectionUtil`**（`util/ReflectionUtil.java`）

| 方法 | 作用 |
|---|---|
| `shouldProcessStep(step, nodeName)` | 判断 Step 当前状态是否需要该节点处理（含反思状态） |
| `buildReflectionHistoryContent(step)` | 将历史反思记录序列化为 Markdown，注入 prompt |
| `getCompletionStatus(hasReflectionProcessor, nodeName)` | 执行完成后应设置的状态（启用→waiting_reflecting，否则→completed） |
| `shouldContinueAfterReflection(result)` | 解包 handleReflection 结果 |
| `hasReflectionHistory(step)` | 是否存在历史反思记录 |

### 4.5 Reflection Agent

**system prompt**：`src/main/resources/prompts/reflection.md`

- 角色：任务质量评估专家
- 研究类评估维度：内容完整性、信息准确性、逻辑清晰性、深度适宜性、来源可靠性
- 编程类评估维度：功能正确性、逻辑清晰性、结构合理性、注释充分性、规范遵循性
- 输出格式：原始 JSON，字段为 `passed` (boolean) + `feedback` (string)

**Bean 定义**（`agents/AgentsConfiguration.java`）：

```java
@Bean
public ChatClient reflectionAgent(ChatClient.Builder reflectionChatClientBuilder) {
    return reflectionChatClientBuilder
        .defaultSystem(ResourceUtil.loadResourceAsString(reflectionPrompt))
        .build();
}
```

### 4.6 装配入口

**`DeepResearchConfiguration`**（`config/DeepResearchConfiguration.java`）：

```java
@Bean
public ReflectionProcessor reflectionProcessor() {
    if (!reflectionProperties.isEnabled()) {
        return null;  // null 表示未启用，节点直接跳过反思
    }
    return new ReflectionProcessor(reflectionAgent, reflectionProperties.getMaxAttempts());
}
```

`ReflectionProcessor` 以同一实例注入所有 `researcher_N` 和 `coder_N` 节点。

---

## 五、节点侧集成（ResearcherNode / CoderNode）

两个节点的反思集成逻辑完全对称：

```java
// 1. apply() 开头：前置反思拦截
if (reflectionProcessor != null) {
    var result = reflectionProcessor.handleReflection(step, nodeName, "researcher"/"coder");
    if (!ReflectionUtil.shouldContinueAfterReflection(result)) {
        return updated;  // 评估完成或已处理，本次不执行业务
    }
}

// 2. 执行完成后：根据是否启用反思决定状态
assignedStep.setExecutionStatus(
    ReflectionUtil.getCompletionStatus(reflectionProcessor != null, nodeName)
);

// 3. 重新执行时：prompt 中注入历史 feedback
if (ReflectionUtil.hasReflectionHistory(step)) {
    content.append(ReflectionUtil.buildReflectionHistoryContent(step));
    content.append("请参考上述反馈改进...");
}
```

前端展示时，反思重试的流节点会使用不同的前缀（`StreamNodePrefixEnum`）：
- 首次执行：`researcher_llm_stream` / `coder_llm_stream`
- 反思重试：`researcher_reflect_llm_stream` / `coder_reflect_llm_stream`

---

## 六、关键设计决策

1. **null 表示禁用**：`reflectionProcessor` 为 null 时节点完全跳过反思，无额外开销。
2. **状态驱动**：反思循环通过 `executionStatus` 字符串驱动，图本身无需特殊边，节点被同一图流程多次调度即可。
3. **失败安全**：LLM 调用或 JSON 解析异常时默认"通过"，避免阻塞整个研究流程。
4. **历史注入改进**：评估不通过时将 feedback 追加到 `reflectionHistory`，下次执行时完整注入 prompt，让节点知道"上次哪里不好"。
5. **最大次数保护**：`maxAttempts` 防止死循环，超出后强制 completed。

---

## 七、相关文件索引

| 文件 | 职责 |
|---|---|
| `config/ReflectionProperties.java` | 配置项（enabled / maxAttempts） |
| `model/dto/ReflectionResult.java` | 评估结果 DTO |
| `model/dto/Plan.java` | Step 的 reflectionHistory 字段 |
| `util/StateUtil.java` | executionStatus 状态常量定义 |
| `util/ReflectionProcessor.java` | 核心处理器：状态判断、评估调用、重试控制 |
| `util/ReflectionUtil.java` | 静态工具方法：Step 过滤、历史注入、状态计算 |
| `util/NodeStepTitleUtil.java` | 为前端注册反思节点的展示标题 |
| `model/enums/StreamNodePrefixEnum.java` | 反思重试节点的流前缀枚举 |
| `node/ResearcherNode.java` | 研究节点，集成反思前置拦截和状态设置 |
| `node/CoderNode.java` | 编码节点，同上 |
| `agents/AgentsConfiguration.java` | reflectionAgent Bean 定义 |
| `config/DeepResearchConfiguration.java` | ReflectionProcessor Bean 创建与注入 |
| `src/main/resources/prompts/reflection.md` | reflectionAgent 的 system prompt |

---

## 八、完整示例对比

### 场景

用户提问：**"分析 2024 年全球 AI 芯片市场份额"**

Planner 生成一个 Step：

```
title: 2024 年全球 AI 芯片市场份额分析
type: RESEARCH
description: 搜集英伟达、AMD、英特尔等主要厂商的市场数据，分析各自占比及趋势
```

该 Step 被分配给 `researcher_0`，初始状态为 `assigned_researcher_0`。

---

### 情况 A：未启用 Reflection（`reflection.enabled: false`）

`reflectionProcessor` 为 null，节点执行流程如下：

```
第 1 次调度 researcher_0
│
├── findAssignedStep() → 找到该 Step（状态 assigned_researcher_0）
├── reflectionProcessor == null，跳过反思检查
├── 设置状态 → processing_researcher_0
├── 调用 researchAgent，流式输出结果：
│     "英伟达占据约 80% 份额，AMD 约 10%，其余厂商合计 10%。"
│     （内容较简短，缺乏具体数据来源和趋势分析）
└── 执行完成，设置状态 → completed_researcher_0
      （ReflectionUtil.getCompletionStatus(false, ...) 直接返回 completed）
```

**最终结果**：该简短结论被直接采纳，Reporter 汇总时缺乏深度。

---

### 情况 B：启用 Reflection（`reflection.enabled: true`，`max-attempts: 1`）

**第 1 次调度 researcher_0（首次执行）**

```
├── findAssignedStep() → 找到该 Step（状态 assigned_researcher_0）
├── handleReflection():
│     状态不是 waiting_reflecting / waiting_processing
│     → 返回 continueProcessing，节点继续执行
├── 设置状态 → processing_researcher_0
├── 调用 researchAgent，输出：
│     "英伟达占据约 80% 份额，AMD 约 10%，其余厂商合计 10%。"
└── 执行完成，设置状态 → waiting_reflecting_researcher_0
      （ReflectionUtil.getCompletionStatus(true, ...) 返回 waiting_reflecting）
```

**第 2 次调度 researcher_0（反思评估）**

```
├── findAssignedStep() → 找到该 Step（ReflectionUtil.shouldProcessStep 匹配 waiting_reflecting）
├── handleReflection():
│     状态 == waiting_reflecting_researcher_0
│     → 调用 performReflection()
│         ├── attemptCount = 0（reflectionHistory 为空），未超上限
│         ├── 调用 evaluateStepQuality()：
│         │     prompt: "请评估以下研究任务的完成质量：
│         │              任务标题：2024 年全球 AI 芯片市场份额分析
│         │              完成结果：英伟达占据约 80% 份额..."
│         │     reflectionAgent 输出：
│         │     {
│         │       "passed": false,
│         │       "feedback": "内容过于简短，缺少具体数据来源链接，
│         │                    未分析各厂商市场份额的年度变化趋势，
│         │                    也未涵盖华为昇腾等新兴竞争者。"
│         │     }
│         ├── passed=false → 追加 feedback 到 reflectionHistory（第 1 条）
│         └── 设置状态 → waiting_processing_researcher_0
└── 返回 skipProcessing，本次跳过业务执行
```

**第 3 次调度 researcher_0（携带 feedback 重新执行）**

```
├── findAssignedStep() → 找到该 Step（shouldProcessStep 匹配 waiting_processing）
├── handleReflection():
│     状态 == waiting_processing_researcher_0
│     → 清空 executionRes，设置状态 → processing_researcher_0
│     → 返回 continueProcessing
├── buildTaskMessageWithReflectionHistory() 构建 prompt：
│     # Current Task
│     ## Title: 2024 年全球 AI 芯片市场份额分析
│     ## Description: 搜集英伟达...
│
│     ## Previous Attempts and Feedback
│     ### Attempt 1
│     **Previous Execution Result**: 英伟达占据约 80% 份额...
│     **Reflection Feedback**: 内容过于简短，缺少具体数据来源链接，
│                              未分析各厂商市场份额的年度变化趋势，
│                              也未涵盖华为昇腾等新兴竞争者。
│     **Evaluation Result**: Failed
│
│     Please re-complete this research task based on the above...
├── 调用 researchAgent，输出改进后的结果：
│     "根据 IDC 2024Q3 报告（来源: https://idc.com/...），
│      英伟达 H100/H200 系列占据数据中心 GPU 市场约 82%，
│      较 2023 年提升 5 个百分点；AMD MI300X 份额约 8%；
│      华为昇腾在中国市场受出口管制影响份额约 6%...
│      趋势来看，2025 年随着 Blackwell 架构量产，英伟达优势将进一步扩大..."
└── 执行完成，设置状态 → waiting_reflecting_researcher_0
```

**第 4 次调度 researcher_0（再次评估）**

```
├── handleReflection() → performReflection()
│     ├── attemptCount = 1（reflectionHistory 有 1 条），未超上限（max=1）
│     │   注意：此处 attemptCount(1) >= maxAttempts(1)，触发强制通过
│     └── 强制设置状态 → completed_researcher_0
└── 返回 skipProcessing
```

> 若 `max-attempts: 2`，则第 4 次会再次调用 reflectionAgent 评估：
> 改进后的内容包含来源链接、趋势分析、新兴竞争者，评估返回 `passed: true`，
> 直接设置 `completed_researcher_0`，流程结束。

---

### 对比总结

| | 未启用 Reflection | 启用 Reflection（触发重试） |
|---|---|---|
| 调度次数 | 1 次 | 3~4 次 |
| 输出质量 | 简短结论，无来源 | 包含数据来源、趋势、竞争格局 |
| 额外 LLM 调用 | 0 次 | 1~2 次（reflectionAgent 评估） |
| 适用场景 | 快速响应、对质量要求不高 | 深度研究、报告输出质量优先 |
