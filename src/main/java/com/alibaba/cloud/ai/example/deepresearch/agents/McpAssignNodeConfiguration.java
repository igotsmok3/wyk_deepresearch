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

package com.alibaba.cloud.ai.example.deepresearch.agents;

import com.alibaba.cloud.ai.example.deepresearch.config.McpAssignNodeProperties;
import com.alibaba.cloud.ai.example.deepresearch.util.mcp.McpConfigMergeUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP 节点分配配置类，负责读取 mcp-config.json 并向容器暴露 Agent 与 MCP Server 的映射关系。
 *
 * <p>
 * 项目职责：属于配置层，仅在 {@code spring.ai.alibaba.deepresearch.mcp.enabled=true} 时生效。 向容器暴露两个
 * Bean：{@code agent2mcpConfig}（解析自 mcp-config.json 的静态配置 Map）以及
 * {@code agent2mcpConfigWithRuntime}（在节点运行时将静态配置与请求携带的 mcp_settings 动态合并的 Function）。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理；{@code agent2mcpConfig} 和 {@code agent2mcpConfigWithRuntime} Bean
 * 被 {@code McpClientUtil}、{@code McpConfigMergeUtil} 及各图节点类在运行时按需注入使用。
 *
 * @author Makoto
 */
@ConditionalOnProperty(prefix = McpAssignNodeProperties.MCP_ASSIGN_NODE_PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties({ McpAssignNodeProperties.class, McpClientCommonProperties.class })
@Configuration
public class McpAssignNodeConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(McpAssignNodeConfiguration.class);

	@Autowired
	private McpAssignNodeProperties mcpAssignNodeProperties;

	@Autowired
	private ResourceLoader resourceLoader;

	@Autowired
	private ObjectMapper objectMapper;

	/**
	 * 读取 mcp-config.json，返回 agentName -> McpServerConfig 的静态配置 Map。 文件不存在时返回空
	 * Map，不影响应用启动。
	 */
	@Bean(name = "agent2mcpConfig")
	public Map<String, McpAssignNodeProperties.McpServerConfig> agent2mcpConfig() {
		try {
			Resource resource = resourceLoader.getResource(mcpAssignNodeProperties.getConfigLocation());
			if (!resource.exists()) {
				return new HashMap<>();
			}

			try (InputStream inputStream = resource.getInputStream()) {
				TypeReference<Map<String, McpAssignNodeProperties.McpServerConfig>> typeRef = new TypeReference<>() {
				};
				return objectMapper.readValue(inputStream, typeRef);
			}
		}
		catch (IOException e) {
			logger.error("读取MCP配置失败", e);
			return new HashMap<>();
		}
	}

	/**
	 * 返回一个 Function，在节点执行时调用，将静态配置与请求携带的 mcp_settings 合并。 mcp_settings 来自
	 * ChatRequest.mcpSettings，经由 OverAllState 传递到此处。 合并规则：相同 URL 的 Server 以动态配置覆盖静态配置，新
	 * URL 追加。
	 */
	@Bean(name = "agent2mcpConfigWithRuntime")
	public Function<OverAllState, Map<String, McpAssignNodeProperties.McpServerConfig>> agent2mcpConfigWithRuntime(
			@Qualifier("agent2mcpConfig") Map<String, McpAssignNodeProperties.McpServerConfig> staticConfig) {

		return state -> {
			// 从请求级 state 中取出前端传入的 mcp_settings（可为空）
			Map<String, Object> runtimeMcpSettings = state.value("mcp_settings", Map.class)
				.orElse(Collections.emptyMap());
			return McpConfigMergeUtil.mergeAgent2McpConfigs(staticConfig, runtimeMcpSettings, objectMapper);
		};
	}

	// MCP客户端创建逻辑已移动到各个节点内部处理
	// 配置类现在只负责提供配置信息，实际的客户端创建在节点运行时进行

	private String connectedClientName(String clientName, String serverConnectionName) {
		return clientName + " - " + serverConnectionName;
	}

}
