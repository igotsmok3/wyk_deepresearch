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

package com.alibaba.cloud.ai.example.deepresearch.util.mcp;

import com.alibaba.cloud.ai.example.deepresearch.config.McpAssignNodeProperties;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpAsyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * MCP 客户端创建工具类，封装为每个启用的 MCP Server 建立 SSE 长连接并完成 initialize 握手的流程。
 *
 * <p>项目职责：核心职责是根据合并后的 MCP 配置为各 Agent 创建 {@code AsyncMcpToolCallbackProvider}，
 * Provider 内含全部可调用的 Tool 列表，可直接注入 ChatClient 的 toolCallbacks；
 * 单个 Server 连接失败只记录错误日志，不影响其他 Server。
 *
 * <p>被使用情况：{@code McpProviderFactory} 委托本类的 {@code createMcpProvider} 方法，
 * 在各 MCP-aware 节点（如 ResearcherNode）执行前动态建立 MCP 连接。
 *
 * @author Makoto
 */
public class McpClientUtil {

	private static final Logger logger = LoggerFactory.getLogger(McpClientUtil.class);

	/**
	 * 为指定 agentName 创建 MCP 提供者。
	 * 流程：
	 *   1. 通过 mcpConfigProvider.apply(state) 获取合并后的配置（静态 + 运行时）
	 *   2. 遍历该 agent 的 enabled Server，用 WebFluxSseClientTransport 建立 SSE 连接
	 *   3. 对每个 Server 调用 client.initialize().block() 完成 MCP 握手（超时 2 分钟）
	 *   4. 将所有客户端汇总到 AsyncMcpToolCallbackProvider 返回
	 * 任何 Server 连接失败只记录 error 日志，不影响其他 Server。
	 */
	public static AsyncMcpToolCallbackProvider createMcpProvider(OverAllState state, String agentName,
			Function<OverAllState, Map<String, McpAssignNodeProperties.McpServerConfig>> mcpConfigProvider,
			McpAsyncClientConfigurer mcpAsyncClientConfigurer, McpClientCommonProperties commonProperties,
			WebClient.Builder webClientBuilderTemplate, ObjectMapper objectMapper) {

		if (mcpConfigProvider == null || mcpAsyncClientConfigurer == null) {
			logger.debug("MCP configuration not available for {}", agentName);
			return null;
		}

		try {
			// 从 state 获取合并后配置（静态文件 + 请求级 mcp_settings）
			Map<String, McpAssignNodeProperties.McpServerConfig> mcpAgentConfigs = mcpConfigProvider.apply(state);
			McpAssignNodeProperties.McpServerConfig config = mcpAgentConfigs.get(agentName);

			if (config == null || config.mcpServers().isEmpty()) {
				logger.debug("No MCP servers configured for {}", agentName);
				return null;
			}

			List<McpAsyncClient> mcpAsyncClients = new ArrayList<>();
			String threadId = state.value("thread_id", "__default__");

			logger.debug("Creating MCP clients for {} in thread: {}", agentName, threadId);

			for (McpAssignNodeProperties.McpServerInfo serverInfo : config.mcpServers()) {
				if (!serverInfo.enabled()) {
					logger.debug("Skipping disabled MCP server: {} for {}", serverInfo.url(), agentName);
					continue;
				}

				// 为每个启用的 Server 创建独立的 SSE Transport
				List<NamedClientMcpTransport> namedTransports = McpConfigMergeUtil.createAgent2McpTransports(agentName,
						new McpAssignNodeProperties.McpServerConfig(List.of(serverInfo)), webClientBuilderTemplate,
						objectMapper);

				for (NamedClientMcpTransport namedTransport : namedTransports) {
					// clientInfo 用于 MCP 握手时标识本客户端身份
					McpSchema.Implementation clientInfo = new McpSchema.Implementation(commonProperties.getName(),
							commonProperties.getVersion());

					McpClient.AsyncSpec spec = McpClient.async(namedTransport.transport()).clientInfo(clientInfo);
					// configurer 注入超时、重试等通用设置
					spec = mcpAsyncClientConfigurer.configure(namedTransport.name(), spec);
					McpAsyncClient client = spec.build();

					// 阻塞等待 initialize 握手完成，最长 2 分钟
					client.initialize().block(java.time.Duration.ofMinutes(2));

					mcpAsyncClients.add(client);
					logger.debug("Created MCP client for server: {} (agent: {})", serverInfo.url(), agentName);
				}
			}

			if (!mcpAsyncClients.isEmpty()) {
				logger.info("Successfully created {} MCP clients for {}", mcpAsyncClients.size(), agentName);
				return new AsyncMcpToolCallbackProvider(mcpAsyncClients);
			}

		}
		catch (Exception e) {
			logger.error("Failed to create MCP clients for {}", agentName, e);
		}

		return null;
	}

	/**
	 * 检查创建 MCP Provider 所需的所有依赖是否都已注入（非 null）。
	 * 节点在尝试 createMcpProvider 之前可先调用此方法快速判断。
	 */
	public static boolean isMcpConfigurationAvailable(
			Function<OverAllState, Map<String, McpAssignNodeProperties.McpServerConfig>> mcpConfigProvider,
			McpAsyncClientConfigurer mcpAsyncClientConfigurer, McpClientCommonProperties commonProperties,
			WebClient.Builder webClientBuilderTemplate, ObjectMapper objectMapper) {

		return mcpConfigProvider != null && mcpAsyncClientConfigurer != null && commonProperties != null
				&& webClientBuilderTemplate != null && objectMapper != null;
	}

}
