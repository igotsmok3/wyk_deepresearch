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

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * MCP 代理节点分配配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.mcp.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，提供 MCP 功能的开关（{@code enabled}）和配置文件路径（{@code configLocation}）， 并通过内部 record
 * 类型 {@code McpServerConfig} 和 {@code McpServerInfo} 定义 mcp-config.json 的反序列化结构。
 *
 * <p>
 * 被使用情况：被 {@code McpAssignNodeConfiguration} 读取以加载 MCP 配置文件； {@code McpClientUtil} 和
 * {@code McpConfigMergeUtil} 使用其内部类型操作 MCP Server 配置； {@code DeepResearchConfiguration}
 * 通过 {@code @EnableConfigurationProperties} 激活本类。
 *
 * @author Makoto
 */
@ConfigurationProperties(prefix = McpAssignNodeProperties.MCP_ASSIGN_NODE_PREFIX)
public class McpAssignNodeProperties {

	public static final String MCP_ASSIGN_NODE_PREFIX = DeepResearchProperties.PREFIX + ".mcp";

	/** 是否启用 MCP 功能，对应 application.yml mcp.enabled */
	private boolean enabled = true;

	/** mcp-config.json 的 classpath 位置，可通过配置覆盖 */
	private String configLocation = "classpath:mcp-config.json";

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getConfigLocation() {
		return configLocation;
	}

	public void setConfigLocation(String configLocation) {
		this.configLocation = configLocation;
	}

	/**
	 * 对应 mcp-config.json 中每个 agent 下的 { "mcp-servers": [...] } 结构。
	 */
	public static record McpServerConfig(@JsonProperty("mcp-servers") List<McpServerInfo> mcpServers) {
	}

	/**
	 * 单个 MCP Server 的连接信息。 url: Server 基础地址；sseEndpoint: SSE 路径（默认 /sse）；enabled: 是否启用。
	 */
	public static record McpServerInfo(String url, @JsonProperty("sse-endpoint") String sseEndpoint, String description,
			boolean enabled) {
	}

}
