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
import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.AgentDispatchResult;
import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.AgentSelectionResult;
import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.AgentType;
import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.SearchPlatform;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.AgentIntegrationUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.SmartAgentUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.toolcalling.searches.SearchEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * 智能 Agent 选择辅助器，为节点提供统一的 Agent 选择和搜索引擎路由入口。
 *
 * <p>项目职责：整合 {@code SmartAgentDispatcherService}、{@code QuestionClassifierService}
 * 和 {@code SearchPlatformSelectionService}，对外暴露两个高层方法：
 * {@code selectSmartAgent} 用于为研究节点选择最适合的 ChatClient；
 * {@code intelligentSearchSelection} 用于为搜索节点决策传统搜索引擎还是工具调用分支。
 * 不是 Spring Bean，由 {@code AgentIntegrationUtil.createSelectionHelper()} 工厂方法创建。
 *
 * <p>被使用情况：被 {@code ResearcherNode} 和 {@code BackgroundInvestigationNode} 通过
 * {@code AgentIntegrationUtil} 实例化并持有，负责节点内部的 Agent 与搜索路由决策。
 *
 * @author Makoto
 * @since 2025/07/17
 */
public class SmartAgentSelectionHelperService {

	private static final Logger logger = LoggerFactory.getLogger(SmartAgentSelectionHelperService.class);

	private final SmartAgentProperties smartAgentProperties;

	private final SmartAgentDispatcherService smartAgentDispatcher;

	private final QuestionClassifierService questionClassifierService;

	private final SearchPlatformSelectionService searchPlatformSelectionService;

	public SmartAgentSelectionHelperService(SmartAgentProperties smartAgentProperties,
			SmartAgentDispatcherService smartAgentDispatcher, QuestionClassifierService questionClassifierService,
			SearchPlatformSelectionService searchPlatformSelectionService) {
		this.smartAgentProperties = smartAgentProperties;
		this.smartAgentDispatcher = smartAgentDispatcher;
		this.questionClassifierService = questionClassifierService;
		this.searchPlatformSelectionService = searchPlatformSelectionService;
	}

	/**
	 * 选择合适的智能Agent
	 * @param questionContent 问题内容
	 * @param state 全局状态
	 * @param fallbackAgent 回退Agent
	 * @return Agent选择结果
	 */
	public AgentSelectionResult selectSmartAgent(String questionContent, OverAllState state, ChatClient fallbackAgent) {
		if (!AgentIntegrationUtil.isSmartAgentAvailable(smartAgentProperties, smartAgentDispatcher)) {
			logger.debug("智能Agent功能未开启或服务不可用，使用默认Agent");
			return new AgentSelectionResult(fallbackAgent, AgentType.GENERAL_RESEARCH, false, "智能Agent功能未开启或服务不可用");
		}

		try {
			AgentDispatchResult dispatchResult = smartAgentDispatcher.dispatchToAgent(questionContent, state);

			if (dispatchResult.isSuccess() && dispatchResult.getAgent() != null) {
				return new AgentSelectionResult(dispatchResult.getAgent(), dispatchResult.getAgentType(), true,
						"智能Agent选择成功", dispatchResult.getStateUpdate());
			}
			else {
				return new AgentSelectionResult(fallbackAgent, AgentType.GENERAL_RESEARCH, false,
						"智能Agent分派失败: " + dispatchResult.getErrorMessage());
			}
		}
		catch (Exception e) {
			return new AgentSelectionResult(fallbackAgent, AgentType.GENERAL_RESEARCH, false,
					"智能Agent选择异常: " + e.getMessage());
		}
	}

	/**
	 * 智能搜索选择的核心逻辑（统一的问题分类和平台选择）
	 */
	private AgentType classifyQueryAndLog(String query) {
		AgentType agentType = questionClassifierService.classifyQuestion(query);
		logger.info("问题分类结果: {} -> {}", query, agentType);
		return agentType;
	}

	/**
	 * 统一的智能搜索选择入口。
	 * <p>
	 * 决策链：SmartAgent 未开启 → 直接用请求指定的搜索引擎；
	 * SmartAgent 开启 → AI 分类问题 → 查配置/AI 选平台 →
	 *   工具调用平台（专用领域源）返回 isToolCalling=true；
	 *   传统平台返回对应 SearchEnum。
	 * 任何异常均回退到请求指定引擎，保证主流程不中断。
	 *
	 * @param state 全局状态（含请求指定的 search_engine）
	 * @param query 搜索查询
	 * @return 包含搜索引擎、平台、Agent类型和是否工具调用的结果封装
	 */
	public SmartAgentUtil.SearchSelectionResult intelligentSearchSelection(OverAllState state, String query) {
		if (!AgentIntegrationUtil.isSmartAgentAvailable(smartAgentProperties, questionClassifierService,
				searchPlatformSelectionService)) {
			SearchEnum fallbackEnum = state.value("search_engine", SearchEnum.class).orElse(SearchEnum.TAVILY);
			return new SmartAgentUtil.SearchSelectionResult(fallbackEnum, null, AgentType.GENERAL_RESEARCH, false);
		}

		try {
			AgentType agentType = classifyQueryAndLog(query);
			SearchPlatform selectedPlatform = searchPlatformSelectionService.getSelectedSearchPlatform(agentType,
					query);

			if (SmartAgentUtil.isToolCallingPlatform(selectedPlatform)) {
				// 专用领域平台（OpenAlex/Wikipedia 等），由 ToolCallingSearchService 实际执行
				logger.info("选择工具调用搜索: {} (Agent类型: {})", selectedPlatform.getName(), agentType);
				return new SmartAgentUtil.SearchSelectionResult(SearchEnum.TAVILY, selectedPlatform, agentType, true);
			}
			else {
				List<SearchEnum> platforms = searchPlatformSelectionService.selectSearchPlatforms(agentType, query);
				SearchEnum searchEnum = platforms != null && !platforms.isEmpty() ? platforms.get(0)
						: state.value("search_engine", SearchEnum.class).orElse(SearchEnum.TAVILY);
				logger.info("选择传统搜索: {} (Agent类型: {})", searchEnum, agentType);
				return new SmartAgentUtil.SearchSelectionResult(searchEnum, selectedPlatform, agentType, false);
			}
		}
		catch (Exception e) {
			logger.warn("选择失败: {}", e.getMessage());
			SearchEnum fallbackEnum = state.value("search_engine", SearchEnum.class).orElse(SearchEnum.TAVILY);
			return new SmartAgentUtil.SearchSelectionResult(fallbackEnum, null, AgentType.GENERAL_RESEARCH, false);
		}
	}

}
