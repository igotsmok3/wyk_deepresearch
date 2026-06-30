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

import com.alibaba.cloud.ai.example.deepresearch.config.McpAssignNodeProperties;
import com.alibaba.cloud.ai.example.deepresearch.util.mcp.McpClientUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.AsyncMcpToolCallbackProvider;
import org.springframework.ai.mcp.client.autoconfigure.configurer.McpAsyncClientConfigurer;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.function.Function;

/**
 * MCP 工具提供者工厂，封装 {@code AsyncMcpToolCallbackProvider} 的创建过程。
 *
 * <p>
 * 项目职责：将创建 MCP 工具回调提供者所需的基础设施依赖（MCP 客户端配置器、 公共属性、WebClient 等）聚合在一起；节点只需调用
 * {@code createProvider(state, agentName)} 即可获得对应 agent 的 MCP 工具集，屏蔽客户端初始化及 SSE 握手细节。 仅在
 * {@code spring.ai.alibaba.deepresearch.mcp.enabled=true} 时注册为 Bean。
 *
 * <p>
 * 被使用情况：被 {@code CoderNode} 和 {@code ResearcherNode} 注入，用于在 节点执行时动态获取对应 agent 的 MCP
 * 工具；也通过 {@code DeepResearchConfiguration} 装配到节点中。
 *
 * @author Makoto
 */
@Service
@ConditionalOnProperty(prefix = McpAssignNodeProperties.MCP_ASSIGN_NODE_PREFIX, name = "enabled", havingValue = "true")
public class McpProviderFactory {

	private final Function<OverAllState, Map<String, McpAssignNodeProperties.McpServerConfig>> mcpConfigProvider;

	private final McpAsyncClientConfigurer mcpAsyncClientConfigurer;

	private final McpClientCommonProperties commonProperties;

	private final WebClient.Builder webClientBuilderTemplate;

	private final ObjectMapper objectMapper;

	@Autowired
	public McpProviderFactory(
			@Qualifier("agent2mcpConfigWithRuntime") Function<OverAllState, Map<String, McpAssignNodeProperties.McpServerConfig>> mcpConfigProvider,
			McpAsyncClientConfigurer mcpAsyncClientConfigurer, McpClientCommonProperties commonProperties,
			WebClient.Builder webClientBuilderTemplate, ObjectMapper objectMapper) {
		this.mcpConfigProvider = mcpConfigProvider;
		this.mcpAsyncClientConfigurer = mcpAsyncClientConfigurer;
		this.commonProperties = commonProperties;
		this.webClientBuilderTemplate = webClientBuilderTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * 为指定 agent 创建 MCP 工具提供者。 内部委托给 McpClientUtil，会建立 SSE 连接并完成 initialize 握手。 若该 agent
	 * 无可用 Server 配置或连接失败，返回 null；调用方应做 null 判断。
	 */
	public AsyncMcpToolCallbackProvider createProvider(OverAllState state, String agentName) {
		return McpClientUtil.createMcpProvider(state, agentName, mcpConfigProvider, mcpAsyncClientConfigurer,
				commonProperties, webClientBuilderTemplate, objectMapper);
	}

}
