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

package com.alibaba.cloud.ai.example.deepresearch.config;

import com.alibaba.cloud.ai.toolcalling.searches.SearchEnum;
import com.google.common.collect.Maps;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DeepResearch 全局配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.*} 前缀的配置项。
 *
 * <p>项目职责：属于配置层，作为整个 DeepResearch 模块的顶层配置入口，提供并行节点数量、
 * MCP 客户端映射、图最大迭代次数以及可用搜索引擎列表等核心参数。
 * 各子模块配置类（如 {@code RagProperties}、{@code ReflectionProperties} 等）的前缀常量均基于本类的 {@code PREFIX} 派生。
 *
 * <p>被使用情况：被 {@code DeepResearchConfiguration}、{@code ChatController}、
 * {@code ParallelExecutorNode}、{@code SearchBeanUtil} 等多处注入，用于控制并行度、图执行上限和搜索策略。
 *
 * @author sixiyida
 * @since 2025/6/14
 */
@ConfigurationProperties(prefix = DeepResearchProperties.PREFIX)
public class DeepResearchProperties {

	public static final String PREFIX = "spring.ai.alibaba.deepresearch";

	/**
	 * Parallel node count, key=node name, value=node count
	 */
	private Map<String, Integer> parallelNodeCount = new HashMap<>();

	/**
	 * McpClient mapping for Agent name. key=Agent name, value=McpClient Name
	 */
	private Map<String, Set<String>> mcpClientMapping = Maps.newHashMap();

	/**
	 * 图执行的最大迭代次数
	 */
	private int maxIterations = 50;

	public Map<String, Integer> getParallelNodeCount() {
		return parallelNodeCount;
	}

	public void setParallelNodeCount(Map<String, Integer> parallelNodeCount) {
		this.parallelNodeCount = parallelNodeCount;
	}

	public Map<String, Set<String>> getMcpClientMapping() {
		return mcpClientMapping;
	}

	public void setMcpClientMapping(Map<String, Set<String>> mcpClientMapping) {
		this.mcpClientMapping = mcpClientMapping;
	}

	public int getMaxIterations() {
		return maxIterations;
	}

	public void setMaxIterations(int maxIterations) {
		this.maxIterations = maxIterations;
	}

	private List<SearchEnum> searchList = List.of();

	public List<SearchEnum> getSearchList() {
		return searchList;
	}

	public void setSearchList(List<SearchEnum> searchList) {
		this.searchList = searchList;
	}

}
