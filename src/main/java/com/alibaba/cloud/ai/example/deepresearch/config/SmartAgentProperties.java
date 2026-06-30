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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 智能 Agent 功能的配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.smart-agents.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，提供智能 Agent 功能的启用开关（{@code enabled}）和
 * 搜索平台映射配置（{@code searchPlatformMapping}）， 控制 BackgroundInvestigationNode 和
 * ResearcherNode 在执行搜索时选择哪种搜索平台。
 *
 * <p>
 * 被使用情况：被 {@code DeepResearchConfiguration}、{@code BackgroundInvestigationNode}、
 * {@code ResearcherNode}、{@code QuestionClassifierService}、{@code AgentIntegrationUtil}
 * 等注入， 用于判断是否启用智能 Agent 及选择对应搜索平台。
 *
 * @author Makoto
 * @since 2025/07/17
 */
@ConfigurationProperties(prefix = SmartAgentProperties.PREFIX)
public class SmartAgentProperties {

	public static final String PREFIX = DeepResearchProperties.PREFIX + ".smart-agents";

	private boolean enabled = true;

	private Map<String, SearchPlatformConfig> searchPlatformMapping;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Map<String, SearchPlatformConfig> getSearchPlatformMapping() {
		return searchPlatformMapping;
	}

	public void setSearchPlatformMapping(Map<String, SearchPlatformConfig> searchPlatformMapping) {
		this.searchPlatformMapping = searchPlatformMapping;
	}

	/**
	 * 搜索平台配置
	 */
	public static class SearchPlatformConfig {

		private String primary;

		public String getPrimary() {
			return primary;
		}

		public void setPrimary(String primary) {
			this.primary = primary;
		}

	}

}
