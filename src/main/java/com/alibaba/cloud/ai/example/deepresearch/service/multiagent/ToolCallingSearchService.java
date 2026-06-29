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
import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.SearchPlatform;
import com.alibaba.cloud.ai.toolcalling.common.interfaces.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 专用领域工具调用搜索服务，针对不同 {@code SearchPlatform} 调用对应的领域搜索 Bean。
 *
 * <p>项目职责：持有各领域 {@code SearchService} 的引用（OpenAlex、OpenTripMap、
 * TripAdvisor、Wikipedia、WorldBankData、GoogleScholar），根据传入的 {@code SearchPlatform}
 * 路由到对应实现，将结果统一转换为 {@code List<Map<String, String>>} 格式并标注来源。
 * 所有依赖 Bean 均通过 {@code @Nullable} 注入，缺失时优雅跳过。
 * 仅在 smart-agents 功能开启时注册为 Bean。
 *
 * <p>被使用情况：被 {@code SearchInfoService} 在工具调用搜索分支中调用；
 * 被 {@code BackgroundInvestigationNode} 和 {@code DeepResearchConfiguration} 注入，
 * 用于执行专用领域数据源的搜索请求。
 *
 * @author Makoto
 * @since 2025/07/17
 */
@Service
@ConditionalOnProperty(prefix = SmartAgentProperties.PREFIX, name = "enabled", havingValue = "true",
		matchIfMissing = false)
public class ToolCallingSearchService {

	private static final Logger logger = LoggerFactory.getLogger(ToolCallingSearchService.class);

	// 学术研究工具
	private final SearchService openAlexService;

	// 旅游生活工具
	private final SearchService openTripMapService;

	private final SearchService tripAdvisorService;

	// 百科知识工具
	private final SearchService wikipediaService;

	// 数据分析工具
	private final SearchService worldBankDataService;

	private final SearchService googleScholarService;

	/**
	 * 构造器注入所有搜索服务依赖
	 */
	public ToolCallingSearchService(@Nullable @Qualifier("openAlex") SearchService openAlexService,
			@Nullable @Qualifier("openTripMapService") SearchService openTripMapService,
			@Nullable @Qualifier("tripAdvisor") SearchService tripAdvisorService,
			@Nullable @Qualifier("searchWikipedia") SearchService wikipediaService,
			@Nullable @Qualifier("worldBankData") SearchService worldBankDataService,
			@Nullable @Qualifier("googleScholar") SearchService googleScholarService) {
		this.openAlexService = openAlexService;
		this.openTripMapService = openTripMapService;
		this.tripAdvisorService = tripAdvisorService;
		this.wikipediaService = wikipediaService;
		this.worldBankDataService = worldBankDataService;
		this.googleScholarService = googleScholarService;
	}

	/**
	 * 根据搜索平台执行工具调用搜索
	 * @param platform 搜索平台
	 * @param query 搜索查询
	 * @return 搜索结果
	 */
	public List<Map<String, String>> performToolCallingSearch(SearchPlatform platform, String query) {
		try {
			SearchService targetService = getSearchService(platform);
			if (targetService == null) {
				logger.warn("未找到对应的工具调用服务: {}", platform);
				return Collections.emptyList();
			}

			SearchService.Response response = targetService.query(query);

			if (response != null && response.getSearchResult() != null) {
				return convertToSearchResults(response.getSearchResult().results(), platform);
			}

		}
		catch (Exception e) {
			logger.error("工具调用搜索失败: platform={}, query={}", platform, query, e);
		}

		return Collections.emptyList();
	}

	/**
	 * 根据搜索平台获取对应的搜索服务
	 */
	private SearchService getSearchService(SearchPlatform platform) {
		return switch (platform) {
			case OPENALEX -> openAlexService;
			case OPENTRIPMAP -> openTripMapService;
			case TRIPADVISOR -> tripAdvisorService;
			case WIKIPEDIA -> wikipediaService;
			case WORLDBANK_DATA -> worldBankDataService;
			case GOOGLE_SCHOLAR -> googleScholarService;
			default -> null;
		};
	}

	/**
	 * 将工具调用结果转换为标准搜索结果格式
	 */
	private List<Map<String, String>> convertToSearchResults(List<SearchService.SearchContent> contents,
			SearchPlatform platform) {

		List<Map<String, String>> results = new ArrayList<>();

		for (SearchService.SearchContent content : contents) {
			Map<String, String> result = new HashMap<>();
			result.put("title", content.title() != null ? content.title() : "未知标题");
			result.put("content", content.content() != null ? content.content() : "无内容描述");
			result.put("url", content.url() != null ? content.url() : "");
			result.put("weight", "1.0"); // 工具调用结果默认权重
			result.put("source", platform.getName()); // 标识数据来源
			results.add(result);
		}

		logger.info("工具调用搜索完成: platform={}, results_count={}", platform.getName(), results.size());
		return results;
	}

}
