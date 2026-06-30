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
package com.alibaba.cloud.ai.example.deepresearch.service;

import com.alibaba.cloud.ai.example.deepresearch.model.multiagent.SearchPlatform;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.ToolCallingSearchService;
import com.alibaba.cloud.ai.example.deepresearch.util.multiagent.SmartAgentUtil;
import com.alibaba.cloud.ai.toolcalling.common.CommonToolCallUtils;
import com.alibaba.cloud.ai.toolcalling.jinacrawler.JinaCrawlerService;
import com.alibaba.cloud.ai.toolcalling.searches.SearchEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 搜索信息聚合服务，封装搜索执行、过滤、Jina Crawler 抓取和工具调用分支的完整流程。
 *
 * <p>
 * 项目职责：提供两个重载的 {@code searchInfo} 方法——基础版走 {@code SearchFilterService} 过滤管线并可选用 Jina
 * Crawler 替换摘要；扩展版额外支持工具调用平台（OpenAlex/Wikipedia 等）， 优先调用
 * {@code ToolCallingSearchService}，失败或结果为空时回退到传统搜索引擎。 搜索失败最多重试 3 次，间隔 500ms，保证节点健壮性。 非
 * Spring Bean，由使用节点在构造函数中手动创建。
 *
 * <p>
 * 被使用情况：被 {@code ResearcherNode} 和 {@code BackgroundInvestigationNode}
 * 在构造函数中实例化并持有，用于执行每个研究子任务的实际搜索请求。
 *
 * @author vlsmb
 * @since 2025/7/10
 */
public class SearchInfoService {

	private static final Logger logger = LoggerFactory.getLogger(SearchInfoService.class);

	private final Integer MAX_RETRY_COUNT = 3;

	private final Long RETRY_DELAY_MS = 500L;

	private final JinaCrawlerService jinaCrawlerService;

	private final SearchFilterService searchFilterService;

	private final @Nullable ToolCallingSearchService toolCallingSearchService;

	public SearchInfoService(JinaCrawlerService jinaCrawlerService, SearchFilterService searchFilterService,
			@Nullable ToolCallingSearchService toolCallingSearchService) {
		this.jinaCrawlerService = jinaCrawlerService;
		this.searchFilterService = searchFilterService;
		this.toolCallingSearchService = toolCallingSearchService;
	}

	public List<Map<String, String>> searchInfo(boolean enableSearchFilter, SearchEnum searchEnum, String query)
			throws InterruptedException {

		List<Map<String, String>> results = new ArrayList<>();

		// 最多重试 MAX_RETRY_COUNT 次，搜索引擎偶发限流或超时时自动恢复
		for (int i = 0; i < MAX_RETRY_COUNT; i++) {
			try {
				results = searchFilterService.queryAndFilter(enableSearchFilter, searchEnum, query)
					.stream()
					.map(info -> {
						Map<String, String> result = new HashMap<>();
						result.put("title", info.content().title());
						result.put("weight", String.valueOf(info.weight()));
						// 只保留合法 URL，避免把 null/空字符串写入状态
						boolean isUrl = CommonToolCallUtils.isValidUrl(info.content().url());
						String url = null;
						if (isUrl) {
							url = info.content().url();
						}
						result.put("url", url);
						// icon 优先使用搜索结果自带的；没有则从域名根路径推断 favicon.ico
						String icon = info.content().icon();
						if (icon == null || icon.isEmpty()) {
							icon = getIcon(url);
						}
						result.put("icon", icon);

						// Jina Crawler 启用时，用完整页面正文替换搜索摘要，获得更丰富的上下文
						if (jinaCrawlerService == null || !isUrl) {
							result.put("content", info.content().content());
						}
						else {
							try {
								logger.info("Get detail info of a url using Jina Crawler...");
								result.put("content",
										jinaCrawlerService.apply(new JinaCrawlerService.Request(info.content().url()))
											.content());
							}
							catch (Exception e) {
								// Jina Crawler 失败时降级到搜索摘要，不中断主流程
								logger.error("Jina Crawler Service Error", e);
								result.put("content", info.content().content());
							}
						}
						return result;
					})
					.collect(Collectors.toList());
				break;
			}
			catch (Exception e) {
				logger.warn("搜索尝试 {} 失败: {}", i + 1, e.getMessage());
				try {
					Thread.sleep(RETRY_DELAY_MS);
				}
				catch (InterruptedException e1) {
					logger.info("Thread interrupted... {}", e1.getMessage());
					Thread.currentThread().interrupt();
				}
			}
		}
		return results;
	}

	/**
	 * 支持工具调用的搜索方法，优先使用专用领域搜索服务，失败时回退到传统搜索引擎。
	 * <p>
	 * 工具调用平台（OpenAlex/Wikipedia 等）直接调用 ToolCallingSearchService； 传统平台（Tavily/Aliyun 等）走
	 * SearchFilterService 过滤管线。
	 * @param enableSearchFilter 是否启用搜索过滤
	 * @param searchEnum 传统搜索引擎枚举（工具调用时作为回退）
	 * @param query 搜索查询
	 * @param searchPlatform 搜索平台（用于判断是否走工具调用分支）
	 * @return 搜索结果列表
	 * @throws InterruptedException 中断异常
	 */
	public List<Map<String, String>> searchInfo(boolean enableSearchFilter, SearchEnum searchEnum, String query,
			SearchPlatform searchPlatform) throws InterruptedException {

		// 工具调用平台（学术/百科/旅游等专用源）优先，结果为空时再回退
		if (SmartAgentUtil.isToolCallingPlatform(searchPlatform) && toolCallingSearchService != null) {
			try {
				List<Map<String, String>> toolCallingResults = toolCallingSearchService
					.performToolCallingSearch(searchPlatform, query);
				if (!toolCallingResults.isEmpty()) {
					return toolCallingResults;
				}
			}
			catch (Exception e) {
				logger.error("工具调用搜索失败，回退到传统搜索: {}", e.getMessage());
			}
		}

		// 回退到传统搜索方法（searchEnum 为 null 时默认用 Tavily）
		return searchInfo(enableSearchFilter, searchEnum != null ? searchEnum : SearchEnum.TAVILY, query);
	}

	public String getIcon(String url) {
		try {
			URL urlObj = new URL(url);
			String protocol = urlObj.getProtocol();
			String host = urlObj.getHost();
			int port = urlObj.getPort();
			StringBuilder root = new StringBuilder();
			root.append(protocol).append("://").append(host);
			if (port != -1) {
				root.append(":").append(port);
			}
			root.append("/favicon.ico");
			return root.toString();
		}
		catch (MalformedURLException e) {
			logger.error("Invalid URL: {}", url, e);
			return null;
		}
	}

}
