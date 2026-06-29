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

package com.alibaba.cloud.ai.example.deepresearch.node;

import com.alibaba.cloud.ai.example.deepresearch.config.ShortTermMemoryProperties;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.StreamNodePrefixEnum;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.ParallelEnum;
import com.alibaba.cloud.ai.example.deepresearch.model.SessionHistory;
import com.alibaba.cloud.ai.example.deepresearch.model.dto.Plan;
import com.alibaba.cloud.ai.example.deepresearch.model.req.GraphId;
import com.alibaba.cloud.ai.example.deepresearch.service.ReportService;
import com.alibaba.cloud.ai.example.deepresearch.service.SessionContextService;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.TemplateUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.convert.FluxConverter;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 报告生成节点：图流程的最后一个工作节点，将所有研究成果综合生成最终报告并持久化。
 *
 * <p>项目职责：按顺序从 OverAllState 读取并拼装消息：用户角色画像（可选）、背景调查摘要
 * （{@code background_investigation_results}）、研究计划标题和思路（深度研究时）、
 * 各并行 researcher/coder 节点的产出（{@code researcher_content_N} / {@code coder_content_N}）、
 * 专业知识库 RAG 结果（{@code use_professional_kb=true} 时）。流式调用 reporterAgent 生成报告，
 * 完成后写入 OverAllState：
 * <ul>
 *   <li>{@code final_report}：最终报告文本（携带流式 Flux）</li>
 *   <li>{@code thread_id}：当前线程 ID</li>
 * </ul>
 * 同时持久化到 {@code SessionContextService} 和 {@code MessageWindowChatMemory}（短期记忆启用时）。
 *
 * <p>被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code reporter} 注册到图中；
 * {@code BackgroundInvestigationNode} 在简单问题时直接路由到本节点；
 * {@code ProfessionalKbDispatcher} 在不需要知识库时路由到本节点；
 * {@code ReportController} 通过 {@code ReportService} 读取持久化的报告。
 *
 * @author yingzi
 * @since 2025/5/18 15:58
 */
public class ReporterNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(ReporterNode.class);

	private final ChatClient reporterAgent;

	private final ReportService reportService;

	private final SessionContextService sessionContextService;

	private final MessageWindowChatMemory messageWindowChatMemory;

	private final ShortTermMemoryProperties shortTermMemoryProperties;

	// 向 LLM 传递研究任务标题和思路的格式模板
	private static final String RESEARCH_FORMAT = "# Research Requirements\n\n## Task\n\n{0}\n\n## Description\n\n{1}";

	public ReporterNode(ChatClient reporterAgent, ReportService reportService,
			SessionContextService sessionContextService, MessageWindowChatMemory messageWindowChatMemory,
			ShortTermMemoryProperties shortTermMemoryProperties) {
		this.reporterAgent = reporterAgent;
		this.reportService = reportService;
		this.sessionContextService = sessionContextService;
		this.messageWindowChatMemory = messageWindowChatMemory;
		this.shortTermMemoryProperties = shortTermMemoryProperties;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("reporter node is running.");

		// 从 OverAllState 中获取线程ID
		String threadId = StateUtil.getThreadId(state);
		String sessionId = StateUtil.getSessionId(state);
		logger.info("Thread ID from state: {}", threadId);
		logger.info("Session ID from state: {}", sessionId);

		// 按顺序组装 LLM 输入消息列表
		List<Message> messages = new ArrayList<>();
		// 注入短期用户角色记忆（如用户偏好、背景信息），让报告风格贴合用户
		TemplateUtil.addShortUserRoleMemory(messages, state);

		// 将背景调查阶段的网络搜索摘要加入上下文
		List<String> backgroundInvestigationResults = state.value("background_investigation_results",
				(List<String>) null);
		assert backgroundInvestigationResults != null && !backgroundInvestigationResults.isEmpty();
		for (String backgroundInvestigationResult : backgroundInvestigationResults) {
			if (StringUtils.hasText(backgroundInvestigationResult)) {
				messages.add(new UserMessage(backgroundInvestigationResult));
			}
		}

		// 深度研究模式下，追加计划信息和各研究节点的产出
		if (state.value("enable_deepresearch", true)) {
			Plan currentPlan = StateUtil.getPlan(state);

			// 研究计划的标题和总体思路，帮助 LLM 明确报告主题和范围
			messages.add(new UserMessage(
					MessageFormat.format(RESEARCH_FORMAT, currentPlan.getTitle(), currentPlan.getThought())));

			// 将并行执行的 Researcher 和 Coder 节点各步骤的输出按顺序合并
			List<String> researcherTeam = List.of(ParallelEnum.RESEARCHER.getValue(), ParallelEnum.CODER.getValue());
			for (String content : StateUtil.getParallelMessages(state, researcherTeam,
					StateUtil.getMaxStepNum(state))) {
				logger.info("researcherTeam_content: {}", content);
				messages.add(new UserMessage(content));
			}

			// 若启用了专业知识库，将 RAG 检索内容作为补充知识注入
			if (state.value("use_professional_kb", false) && StringUtils.hasText(StateUtil.getRagContent(state))) {
				messages.add(new UserMessage(StateUtil.getRagContent(state)));
			}
		}

		logger.debug("reporter node messages: {}", messages);

		// 向前端 SSE 流注册步骤标题，用于前端进度展示
		String prefix = StreamNodePrefixEnum.REPORTER_LLM_STREAM.getPrefix();
		String stepTitleKey = prefix + "_step_title";
		state.registerKeyAndStrategy(stepTitleKey, new ReplaceStrategy());
		Map<String, Object> inputMap = new HashMap<>();
		inputMap.put(stepTitleKey, "[报告生成]");
		state.input(inputMap);

		// 发起流式 LLM 调用，生成最终报告
		Flux<ChatResponse> streamResult = reporterAgent.prompt().messages(messages).stream().chatResponse();

		// 将流式响应包装为图框架的 GraphResponse，并在流结束后持久化报告
		Flux<GraphResponse<StreamingOutput>> generator = FluxConverter.builder()
			.startingNode(prefix)
			.startingState(state)
			.mapResult(response -> {
				String finalReport = Objects.requireNonNull(response.getResult().getOutput().getText());
				try {
					GraphId graphId = new GraphId(sessionId, threadId);
					String userQuery = state.value("query", String.class).orElse("UNKNOWN");
					// 若短期记忆开启，将本次报告作为 AssistantMessage 写入会话记忆，供后续对话引用
					if (shortTermMemoryProperties.isEnabled()) {
						messageWindowChatMemory.add(sessionId, new AssistantMessage(finalReport));
					}
					// 持久化到 SessionContextService（内部再委托给 ReportService 按 threadId 存储）
					sessionContextService.addSessionHistory(graphId,
							SessionHistory.builder().graphId(graphId).userQuery(userQuery).report(finalReport).build());
					logger.info("Report saved successfully, Thread ID: {}", threadId);
				}
				catch (Exception e) {
					logger.error("Failed to save report, Thread ID: {}", threadId, e);
				}
				return Map.of("final_report", finalReport, "thread_id", threadId);
			})
			.buildWithChatResponse(streamResult);

		// final_report 携带 Flux 流，图框架会自动处理流式输出
		Map<String, Object> resultMap = new HashMap<>();
		resultMap.put("final_report", generator);
		resultMap.put("thread_id", threadId);
		return resultMap;
	}

}
