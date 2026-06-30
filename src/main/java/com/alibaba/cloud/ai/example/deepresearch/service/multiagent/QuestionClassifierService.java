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

package com.alibaba.cloud.ai.example.deepresearch.service.multiagent;

import com.alibaba.cloud.ai.example.deepresearch.config.SmartAgentProperties;
import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.AgentType;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.SmartAgentUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.AgentPromptTemplateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 问题分类服务，通过 AI 模型将用户问题归类为对应的 {@code AgentType}。
 *
 * <p>
 * 项目职责：使用 DashScope 聊天模型和 {@code AgentPromptTemplateUtil} 生成的分类提示，
 * 对用户问题做意图分类（学术研究、生活旅游、百科、数据分析或通用研究）， 为后续的搜索平台选择和 Agent 路由提供决策依据。 仅在
 * {@code spring.ai.alibaba.deepresearch.smart-agents.enabled=true} 时注册为 Bean。
 *
 * <p>
 * 被使用情况：被 {@code SmartAgentDispatcherService}、{@code SmartAgentSelectionHelperService} 和
 * {@code BackgroundInvestigationNode} 注入，在问题路由和背景调查阶段执行分类； 也通过
 * {@code AgentIntegrationUtil} 间接使用。
 *
 * @author Makoto
 * @since 2025/07/17
 */
@Service
@ConditionalOnProperty(prefix = SmartAgentProperties.PREFIX, name = "enabled", havingValue = "true",
		matchIfMissing = false)
public class QuestionClassifierService {

	private static final Logger logger = LoggerFactory.getLogger(QuestionClassifierService.class);

	private final ChatClient classifierClient;

	public QuestionClassifierService(DashScopeChatModel chatModel) {
		this.classifierClient = ChatClient.builder(chatModel)
			.defaultSystem(AgentPromptTemplateUtil.getClassificationPrompt())
			.build();
	}

	/**
	 * 分类用户问题，返回对应的Agent类型
	 */
	public AgentType classifyQuestion(String question) {
		if (question == null || question.trim().isEmpty()) {
			return AgentType.GENERAL_RESEARCH;
		}

		// 直接使用AI模型进行问题分类决策
		String aiClassification = classifierClient.prompt()
			.user("请分析以下问题并返回最适合的Agent类型代码：\n\n" + question)
			.call()
			.content();

		AgentType result = SmartAgentUtil.parseAiClassification(aiClassification);
		logger.info("AI classification result for question '{}': {}", question, result);
		return result;
	}

}
