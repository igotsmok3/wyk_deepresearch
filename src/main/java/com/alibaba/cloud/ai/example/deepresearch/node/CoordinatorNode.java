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
		// 1. 添加消息
		// 1.1 添加预置提示消息
		TemplateUtil.addShortUserRoleMemory(messages, state);
		messages.add(TemplateUtil.getMessage("coordinator"));

		// 添加历史消息UserMessage和AssistantMessage交替
		String sessionId = StateUtil.getSessionId(state);
		boolean enabledShortTermMemory = shortTermMemoryProperties.isEnabled();
		if (enabledShortTermMemory) {
			List<Message> sessionHistoryMemory = messageWindowChatMemory.get(sessionId);
			if (!CollectionUtils.isEmpty(sessionHistoryMemory)) {
				messages.addAll(sessionHistoryMemory);
			}
		}
		// 1.2 添加用户提问
		UserMessage userMessage = new UserMessage(StateUtil.getQuery(state));
		if (enabledShortTermMemory) {
			messageWindowChatMemory.add(sessionId, userMessage);
		}
		messages.add(userMessage);
		logger.debug("Current Coordinator messages: {}", messages);

		// 发起调用并获取完整响应
		ChatResponse response = coordinatorAgent.prompt().messages(messages).call().chatResponse();

		String nextStep = END;
		boolean deepResearch = false;
		Map<String, Object> updated = new HashMap<>();

		// 获取 assistant 消息内容
		assert response != null;
		AssistantMessage assistantMessage = response.getResult().getOutput();
		// 判断是否触发工具调用
		if (assistantMessage.getToolCalls() != null && !assistantMessage.getToolCalls().isEmpty()) {
			logger.info("✅ 工具已调用: " + assistantMessage.getToolCalls());
			nextStep = "rewrite_multi_query";
			deepResearch = true;
		}
		else {
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
