/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.deepresearch.controller;

import com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchProperties;
import com.alibaba.cloud.ai.example.deepresearch.controller.graph.GraphProcess;
import com.alibaba.cloud.ai.example.deepresearch.controller.request.ChatRequestProcess;
import com.alibaba.cloud.ai.example.deepresearch.model.ApiResponse;
import com.alibaba.cloud.ai.example.deepresearch.model.req.ChatRequest;
import com.alibaba.cloud.ai.example.deepresearch.model.req.FeedbackRequest;
import com.alibaba.cloud.ai.example.deepresearch.model.req.GraphId;
import com.alibaba.cloud.ai.example.deepresearch.util.SearchBeanUtil;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.constant.SaverEnum;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.observation.GraphObservationLifecycleListener;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.Map;

/**
 * 聊天流式接口控制器，提供基于 SSE 的深度研究对话入口。
 *
 * <p>
 * 项目职责：controller 层的核心入口，负责编译 StateGraph、管理 MemorySaver 检查点、 处理首次提问和人工反馈两种请求场景，并通过 SSE
 * 将图执行的流式输出推送到客户端。 支持通过 {@code /chat/stop} 中止运行中的图任务，通过 {@code /chat/resume}
 * 在人工确认计划后恢复执行。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理，作为 {@code @RestController} 注册到 {@code /chat} 路径；内部实例化
 * {@link GraphProcess} 和调用 {@link ChatRequestProcess} 完成辅助逻辑。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/chat")
public class ChatController {

	private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

	private final CompiledGraph compiledGraph;

	private final GraphProcess graphProcess;

	private final SearchBeanUtil searchBeanUtil;

	/**
	 * 构造方法——编译 AI 研究图，配置检查点和中断策略。
	 * @param stateGraph 名为 "deepResearch" 的图定义 Bean，由 DeepResearchConfiguration 创建
	 * @param searchBeanUtil 搜索引擎可用性校验工具
	 * @param observationRegistry 可观测性注册表（用于链路追踪/指标），可选
	 * @param deepResearchProperties 项目配置（如最大迭代次数）
	 */
	@Autowired
	public ChatController(@Qualifier("deepResearch") StateGraph stateGraph, SearchBeanUtil searchBeanUtil,
			ObjectProvider<ObservationRegistry> observationRegistry, DeepResearchProperties deepResearchProperties)
			throws GraphStateException {
		// ── 检查点配置（MemorySaver）──────────────────────────────────────────
		// MemorySaver 将每个节点执行后的完整 OverAllState 快照保存在内存中，以 threadId 为 key。
		// 目的：支持图在 human_feedback 节点前"暂停"后，下次请求能通过同一个 threadId 找回状态并恢复执行。
		// 注意：这是单次图执行的短期状态，与跨请求的对话历史（sessionId）是两个不同维度的存储。
		SaverConfig saverConfig = SaverConfig.builder()
			.register(SaverEnum.MEMORY.getValue(), new MemorySaver())
			.build();

		// ── 编译图 ─────────────────────────────────────────────────────────────
		// compile() 将节点/边的定义"锁定"成可执行的 CompiledGraph。
		// interruptBefore("human_feedback")：图执行到 human_feedback 节点之前自动暂停，
		// 此时前端会收到计划内容，用户可确认或修改，然后调用 /resume 恢复。
		this.compiledGraph = stateGraph.compile(CompileConfig.builder()
			.saverConfig(saverConfig)
			.interruptBefore("human_feedback")
			.withLifecycleListener(new GraphObservationLifecycleListener(
					// 若没有配置 ObservationRegistry Bean，则使用空实现（NOOP），避免 NPE
					observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP)))
			.build());

		// 设置图的最大迭代次数，防止无限循环
		this.compiledGraph.setMaxIterations(deepResearchProperties.getMaxIterations());
		this.searchBeanUtil = searchBeanUtil;
		// GraphProcess 封装了流式推送、停止、人工反馈等通用图操作逻辑
		this.graphProcess = new GraphProcess(this.compiledGraph);
		logger.info("ChatController initialized with graph maxIterations: {}",
				deepResearchProperties.getMaxIterations());
	}

	/**
	 * 流式对话接口（SSE），POST /chat/stream。
	 *
	 * <p>
	 * 处理两种场景：
	 * <ol>
	 * <li><b>首次提问</b>：将用户问题写入初始状态，启动图执行，逐节点推送 SSE 事件。</li>
	 * <li><b>带反馈的提问</b>：{@code autoAcceptPlan=false} 且携带 {@code interruptFeedback} 时，
	 * 表示用户在计划确认页面提交了修改意见，此时走人工反馈分支而非重新启动图。</li>
	 * </ol>
	 *
	 * <p>
	 * 返回值是 {@code Flux<ServerSentEvent<String>>}，Spring WebFlux 会将其持续推送给客户端，
	 * 直到图执行完毕或客户端断开连接。
	 * @param chatRequest 请求体，包含用户问题、搜索引擎选择、是否自动接受计划等参数
	 */
	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> chatStream(@RequestBody(required = false) ChatRequest chatRequest)
			throws GraphRunnerException, IllegalArgumentException {
		// 填充默认值（如未指定搜索引擎则使用配置中的默认值）
		chatRequest = ChatRequestProcess.getDefaultChatRequest(chatRequest, searchBeanUtil);

		// 校验所选搜索引擎是否已在 Spring 容器中注册（即对应的 Bean 是否存在）
		if (searchBeanUtil.getSearchService(chatRequest.searchEngine()).isEmpty()) {
			throw new IllegalArgumentException("Search Engine not available.");
		}
		logger.info("Received chat request: {}", chatRequest);

		// 创建本次执行的图 ID（含 sessionId 和全局唯一的 threadId）
		// threadId 是本次图执行的唯一标识，MemorySaver 用它来存取快照
		GraphId graphId = graphProcess.createNewGraphId(chatRequest.sessionId());
		chatRequest = ChatRequestProcess.updateThreadId(chatRequest, graphId.threadId());

		// RunnableConfig 携带 threadId，告诉 CompiledGraph 去 MemorySaver 中找哪个快照
		RunnableConfig runnableConfig = RunnableConfig.builder().threadId(chatRequest.threadId()).build();

		Map<String, Object> objectMap = new HashMap<>();

		// Sinks.Many 是 Reactor 中的"手动发射器"，可以在异步回调里往里塞数据
		// unicast() 表示只允许一个订阅者（即当前 HTTP 连接）；onBackpressureBuffer() 应对背压
		Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();

		// ── 分支判断 ──────────────────────────────────────────────────────────
		if (!chatRequest.autoAcceptPlan() && StringUtils.hasText(chatRequest.interruptFeedback())) {
			// 场景二：用户不自动接受计划，且在本次请求中携带了对计划的修改意见
			// → 将反馈写入已暂停的图状态并恢复执行
			graphProcess.handleHumanFeedback(graphId, chatRequest, objectMap, runnableConfig, sink);
		}
		else {
			// 场景一：首次提问，将用户输入写入 objectMap 作为图的初始状态
			ChatRequestProcess.initializeObjectMap(chatRequest, objectMap);
			logger.info("init inputs: {}", objectMap);
			// fluxStream() 启动图执行，返回每个节点输出的响应式流
			Flux<NodeOutput> resultFuture = compiledGraph.fluxStream(objectMap, runnableConfig);
			// processStream() 订阅 resultFuture，将节点输出转换为 SSE 事件并写入 sink
			graphProcess.processStream(graphId, resultFuture, sink);
		}

		// 将 sink 转为 Flux 返回给 Spring，框架负责将数据推送到客户端
		return sink.asFlux()
			.doOnCancel(() -> logger.info("Client disconnected from stream"))
			.onErrorResume(throwable -> {
				// 图执行出现异常时，向客户端推送一条 error 事件后正常结束流（而非抛异常断连）
				logger.error("Error occurred during streaming", throwable);
				return Mono.just(ServerSentEvent.<String>builder()
					.event("error")
					.data("Error occurred during streaming: " + throwable.getMessage())
					.build());
			});
	}

	/**
	 * 停止正在执行的图任务，POST /chat/stop。
	 *
	 * <p>
	 * 通过 threadId 找到对应的执行上下文并发出终止信号。 返回操作是否成功。
	 * @param graphId 包含 sessionId 和 threadId 的标识对象
	 */
	@PostMapping("/stop")
	public ApiResponse<String> stopGraph(@RequestBody GraphId graphId) {
		return graphProcess.stopGraph(graphId) ? ApiResponse.success(graphId.threadId())
				: ApiResponse.error("Failure", graphId.threadId());
	}

	/**
	 * 恢复被中断的图任务，POST /chat/resume。
	 *
	 * <p>
	 * 调用时机：图在 human_feedback 节点前暂停后，用户在前端确认或修改了研究计划， 点击"继续"按钮时前端调用此接口。
	 *
	 * <p>
	 * 执行步骤：
	 * <ol>
	 * <li>从 MemorySaver 中取出 threadId 对应的状态快照（即暂停时保存的 OverAllState）</li>
	 * <li>将用户的反馈（approve/reject + 具体意见）写入状态</li>
	 * <li>标记状态为"恢复中"，从初始节点重新进入图（跳过已执行的节点）</li>
	 * <li>以 SSE 推送后续节点的输出</li>
	 * </ol>
	 * @param humanFeedback 包含 threadId、feedback（approve/reject）和 feedbackContent（具体意见）
	 */
	@PostMapping(value = "/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<String>> resume(@RequestBody(required = false) FeedbackRequest humanFeedback)
			throws GraphRunnerException {
		// 用 threadId 构造查询 key，MemorySaver 凭此找到暂停时保存的状态快照
		RunnableConfig runnableConfig = RunnableConfig.builder().threadId(humanFeedback.threadId()).build();

		// 将用户反馈内容打包：feedback=approve/reject，feedbackContent=具体修改建议
		Map<String, Object> objectMap = new HashMap<>();
		objectMap.put("feedback", humanFeedback.feedback());
		objectMap.put("feedback_content", humanFeedback.feedbackContent());

		Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
		// 每次 resume 创建独立的 GraphProcess 实例，避免与其他并发请求的 sink 混用
		GraphProcess graphProcess = new GraphProcess(this.compiledGraph);

		// 从 MemorySaver 取回暂停前保存的完整状态快照
		StateSnapshot stateSnapshot = compiledGraph.getState(runnableConfig);
		OverAllState state = stateSnapshot.state();

		// 标记当前状态为"恢复执行"模式，图内部会根据此标志跳过已完成的初始化逻辑
		state.withResume();

		// 将用户反馈写入状态，目标节点 "research_team" 是恢复后第一个处理反馈的节点
		state.withHumanFeedback(new OverAllState.HumanFeedback(objectMap, "research_team"));

		// 从初始节点重新进入图，但因为状态已标记为 resume，图会直接跳到 research_team 继续执行
		Flux<NodeOutput> resultFuture = compiledGraph.fluxStreamFromInitialNode(state, runnableConfig);
		graphProcess.processStream(new GraphId(humanFeedback.sessionId(), humanFeedback.threadId()), resultFuture,
				sink);

		return sink.asFlux()
			.doOnCancel(() -> logger.info("Client disconnected from stream"))
			.doOnError(e -> logger.error("Error occurred during streaming", e));
	}

}
