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

import com.alibaba.cloud.ai.example.deepresearch.config.SmartAgentProperties;
import com.alibaba.cloud.ai.example.deepresearch.model.SessionHistory;
import com.alibaba.cloud.ai.example.deepresearch.service.InfoCheckService;
import com.alibaba.cloud.ai.example.deepresearch.service.SearchFilterService;
import com.alibaba.cloud.ai.example.deepresearch.service.SearchInfoService;
import com.alibaba.cloud.ai.example.deepresearch.service.SessionContextService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SearchPlatformSelectionService;
import com.alibaba.cloud.ai.example.deepresearch.util.TemplateUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.AgentIntegrationUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.SmartAgentUtil;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.ToolCallingSearchService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SmartAgentSelectionHelperService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.QuestionClassifierService;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.toolcalling.jinacrawler.JinaCrawlerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 背景调查节点：对用户查询的每条优化变体执行网络搜索，并调用 backgroundAgent 生成结构化背景摘要。
 *
 * <p>
 * 项目职责：位于 rewrite_multi_query 之后，planner / reporter 之前。从 OverAllState 读取
 * {@code optimize_queries}（由 RewriteAndMultiQueryNode 写入的多条扩展查询），对每条查询独立 搜索并汇总，最终写入：
 * <ul>
 * <li>{@code site_information}：所有查询的原始搜索结果列表，供 reporter 引用来源</li>
 * <li>{@code background_investigation_results}：backgroundAgent 为每条查询生成的摘要</li>
 * <li>{@code background_investigation_next_node}：路由键，深度研究时为 planner，简单问题时为 reporter</li>
 * </ul>
 *
 * <p>
 * 被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code background_investigator} 注册到图中；
 * {@code BackgroundInvestigationDispatcher} 读取 {@code background_investigation_next_node}
 * 进行边路由。
 *
 * @author yingzi
 * @since 2025/5/17 18:37
 */
public class BackgroundInvestigationNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(BackgroundInvestigationNode.class);

	private final InfoCheckService infoCheckService;

	private final SearchInfoService searchInfoService;

	private final SmartAgentSelectionHelperService smartAgentSelectionHelper;

	private final SessionContextService sessionContextService;

	private final ChatClient backgroundAgent;

	public BackgroundInvestigationNode(JinaCrawlerService jinaCrawlerService, InfoCheckService infoCheckService,
			SearchFilterService searchFilterService, QuestionClassifierService questionClassifierService,
			SearchPlatformSelectionService platformSelectionService, SmartAgentProperties smartAgentProperties,
			ChatClient backgroundAgent, SessionContextService sessionContextService,
			ToolCallingSearchService toolCallingSearchService) {
		this.searchInfoService = new SearchInfoService(jinaCrawlerService, searchFilterService,
				toolCallingSearchService);
		this.infoCheckService = infoCheckService;
		this.smartAgentSelectionHelper = AgentIntegrationUtil.createSelectionHelper(smartAgentProperties, null,
				questionClassifierService, platformSelectionService);
		this.backgroundAgent = backgroundAgent;
		this.sessionContextService = sessionContextService;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("background investigation node is running.");

		Map<String, Object> resultMap = new HashMap<>();
		List<List<Map<String, String>>> resultsList = new ArrayList<>();
		// optimize_queries 由 RewriteAndMultiQueryNode 写入，包含原始查询及其扩展变体
		List<String> queries = StateUtil.getOptimizeQueries(state);
		assert queries != null && !queries.isEmpty();

		// 对每条优化查询独立搜索：SmartAgent 开启时按问题类型选平台，否则用请求指定引擎
		for (String query : queries) {
			SmartAgentUtil.SearchSelectionResult searchSelection = smartAgentSelectionHelper
				.intelligentSearchSelection(state, query);
			List<Map<String, String>> results;

			results = searchInfoService.searchInfo(StateUtil.isSearchFilter(state), searchSelection.getSearchEnum(),
					query, searchSelection.getSearchPlatform());
			resultsList.add(results);
		}
		// site_information 供后续 reporter 直接引用来源
		resultMap.put("site_information", resultsList);

		List<String> backgroundResults = new ArrayList<>();
		assert resultsList.size() == queries.size();

		// 对每个查询及其搜索结果，调用 backgroundAgent 生成结构化的背景调查小结
		for (int i = 0; i < resultsList.size(); i++) {
			List<Map<String, String>> searchResults = resultsList.get(i);

			String query = queries.get(i);
			List<Message> messageList = new ArrayList<>();
			TemplateUtil.addShortUserRoleMemory(messageList, state);
			Message messages = new UserMessage(
					"搜索问题:" + query + "\n" + "以下是搜索结果：\n\n" + searchResults.stream().map(r -> {
						return String.format("标题: %s\n权重: %s\n内容: %s\nurl: %s\n", r.get("title"), r.get("weight"),
								r.get("content"), r.get("url"));
					}).collect(Collectors.joining("\n\n")));

			// 注入历史报告作为 AssistantMessage，让 Agent 了解用户的研究背景，避免重复
			String sessionId = state.value("session_id", String.class).orElse("__default__");
			List<SessionHistory> reports = sessionContextService.getRecentReports(sessionId);
			Message lastReportMessage;
			if (reports != null && !reports.isEmpty()) {
				lastReportMessage = new AssistantMessage("这是用户前几次使用DeepResearch的报告：\r\n"
						+ reports.stream().map(SessionHistory::toString).collect(Collectors.joining("\r\n\r\n")));
			}
			else {
				lastReportMessage = new AssistantMessage("这是用户的第一次询问，因此没有上下文。");
			}
			messageList.add(lastReportMessage);
			messageList.add(messages);
			String content = backgroundAgent.prompt().messages(messageList).call().content();

			backgroundResults.add(content);

			logger.info("背景调查报告生成已完成: {}", backgroundResults.size());
		}
		resultMap.put("background_investigation_results", backgroundResults);

		// 简单问题（非深度研究）直接跳到 reporter；深度研究需要先规划
		String nextStep = "planner";
		if (!StateUtil.isDeepresearch(state)) {
			nextStep = "reporter";
		}
		resultMap.put("background_investigation_next_node", nextStep);
		return resultMap;
	}

}
