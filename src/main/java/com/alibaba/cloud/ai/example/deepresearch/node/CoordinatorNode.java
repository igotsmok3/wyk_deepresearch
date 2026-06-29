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
import com.alibaba.cloud.ai.example.deepresearch.service.SessionContextService;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.TemplateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 协调节点：判断用户请求属于简单对话还是需要深度研究，并决定图流程的下一跳。
 *
 * <p>项目职责：图流程中 short_user_role_memory 之后的第一个决策节点。从 OverAllState 读取
 * {@code query} 和 {@code session_id}，按顺序组装：用户角色画像、coordinator 系统提示词、
 * 多轮对话历史（MessageWindowChatMemory）和当前用户提问，然后调用 coordinatorAgent。
 * LLM 以工具调用方式表达"需要深度研究"的意图。写入 OverAllState：
 * <ul>
 *   <li>{@code coordinator_next_node}：路由键，触发工具调用时为 rewrite_multi_query，否则为 END</li>
 *   <li>{@code deep_research}：是否进入深度研究流程</li>
 *   <li>{@code output}：直接回答内容（仅简单对话时写入）</li>
 * </ul>
 *
 * <p>被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code coordinator} 注册到图中；
 * {@code CoordinatorDispatcher} 读取 {@code coordinator_next_node} 进行边路由；
 * {@code MemoryConfig} 创建的 {@code MessageWindowChatMemory} Bean 注入本节点。
 *
 * @author yingzi
 * @since 2025/5/18 16:38
 */
public class CoordinatorNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(CoordinatorNode.class);

	private final ChatClient coordinatorAgent;

	private final SessionContextService sessionContextService;

	private final MessageWindowChatMemory messageWindowChatMemory;

	private final ShortTermMemoryProperties shortTermMemoryProperties;

	public CoordinatorNode(ChatClient coordinatorAgent, SessionContextService sessionContextService,
			MessageWindowChatMemory messageWindowChatMemory, ShortTermMemoryProperties shortTermMemoryProperties) {
		this.coordinatorAgent = coordinatorAgent;
		this.sessionContextService = sessionContextService;
		this.messageWindowChatMemory = messageWindowChatMemory;
		this.shortTermMemoryProperties = shortTermMemoryProperties;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("coordinator node is running.");
		List<Message> messages = new ArrayList<>();

		// ① 注入用户角色画像（由 ShortUserRoleMemoryNode 写入 OverAllState）
		//    形如："You are having a conversation with [用户职业概述]"，让 coordinator 了解用户背景
		TemplateUtil.addShortUserRoleMemory(messages, state);

		// ② 注入 coordinator 角色的系统提示词（从 prompts/coordinator.md 加载）
		messages.add(TemplateUtil.getMessage("coordinator"));

		// ③ 注入多轮对话历史（MessageWindowChatMemory，按 sessionId 存储 User/Assistant 轮次）
		//    这是跨请求的对话记忆，让 coordinator 知道本次 session 之前聊了什么
		String sessionId = StateUtil.getSessionId(state);
		boolean enabledShortTermMemory = shortTermMemoryProperties.isEnabled();
		if (enabledShortTermMemory) {
			List<Message> sessionHistoryMemory = messageWindowChatMemory.get(sessionId);
			if (!CollectionUtils.isEmpty(sessionHistoryMemory)) {
				messages.addAll(sessionHistoryMemory);
			}
		}

		// ④ 添加本轮用户提问，并立即存入 MessageWindowChatMemory（无论本轮是否触发深度研究都要存）
		UserMessage userMessage = new UserMessage(StateUtil.getQuery(state));
		if (enabledShortTermMemory) {
			messageWindowChatMemory.add(sessionId, userMessage);
		}
		messages.add(userMessage);
		logger.debug("Current Coordinator messages: {}", messages);

		// 调用 coordinator LLM，判断是简单对话还是需要深度研究
		ChatResponse response = coordinatorAgent.prompt().messages(messages).call().chatResponse();

		String nextStep = END;
		boolean deepResearch = false;
		Map<String, Object> updated = new HashMap<>();

		assert response != null;
		AssistantMessage assistantMessage = response.getResult().getOutput();
		// coordinator 通过工具调用来表达"需要深度研究"的意图
		if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
			logger.info("✅ 工具已调用: " + assistantMessage.getToolCalls());
			nextStep = "rewrite_multi_query";
			deepResearch = true;
			// 触发深度研究时不保存 assistant 回复，因为最终回复会由 reporter 生成
		}
		else {
			// 直接回答（无需深度研究），将 assistant 回复也存入 MessageWindowChatMemory，维护完整对话历史
			logger.warn("❌ 未触发工具调用");
			logger.debug("Coordinator response: {}", response.getResult());
			AssistantMessage output = response.getResult().getOutput();
			if (enabledShortTermMemory) {
				messageWindowChatMemory.add(sessionId, output);
			}
			updated.put("output", assistantMessage.getText());
		}
		updated.put("coordinator_next_node", nextStep);
		updated.put("deep_research", deepResearch);
		return updated;
	}

}
