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

package com.alibaba.cloud.ai.example.deepresearch.tool;

import com.alibaba.cloud.ai.example.deepresearch.service.SearchFilterService;
import com.alibaba.cloud.ai.toolcalling.searches.SearchEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 带权重过滤的搜索工具，供 LLM 调用以执行搜索并按网站信任权重过滤和排序结果。
 *
 * <p>项目职责：作为 Spring AI Tool 封装 {@link com.alibaba.cloud.ai.example.deepresearch.service.SearchFilterService}
 * 的调用，在 Researcher Agent 执行搜索时对不可信网站的内容进行过滤，
 * 返回按信任权重排序的 {@code SearchContentWithWeight} 列表，提升研究结果质量。
 *
 * <p>被使用情况：由 Spring AI 工具调用框架管理，在 ResearcherNode 中按需创建实例并注入 Researcher ChatClient；
 * 不由 Spring 容器直接管理（非 @Service），而是通过节点构造时手动实例化。
 *
 * @author vlsmb
 * @since 2025/7/10
 */
public class SearchFilterTool {

	private static final Logger log = LoggerFactory.getLogger(SearchFilterTool.class);

	private final SearchFilterService searchFilterService;

	private final SearchEnum searchEnum;

	private final Boolean isEnabledFilter;

	public SearchFilterTool(SearchFilterService searchFilterService, SearchEnum searchEnum, Boolean isEnabledFilter) {
		this.searchFilterService = searchFilterService;
		this.searchEnum = searchEnum;
		this.isEnabledFilter = isEnabledFilter;
	}

	@Tool(description = "Use SearchService to retrieve relevant information and return search results ranked by website trust weights. Information from untrusted websites will be filtered out.")
	public List<SearchFilterService.SearchContentWithWeight> searchFilterTool(
			@ToolParam(description = "Content to be queried using search engines") String query) {
		log.debug("SearchFilterTool start.");
		return searchFilterService.queryAndFilter(isEnabledFilter, searchEnum, query);
	}

}
