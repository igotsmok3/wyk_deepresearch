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
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP 配置合并与 Transport 创建工具类，提供静态配置与请求级动态配置的合并以及 SSE Transport 的构建能力。
 *
 * <p>项目职责：封装三个核心操作：(1) {@code mergeAgent2McpConfigs} 将 mcp-config.json 静态配置
 * 与运行时 mcp_settings 合并；(2) {@code mergeAgent2McpServers} 以 URL 为 key 对 Server 列表去重合并；
 * (3) {@code createAgent2McpTransports} 将 Server 配置转换为 WebFlux SSE Transport，
 * 供后续 MCP 客户端握手使用。
 *
 * <p>被使用情况：{@code McpAssignNodeConfiguration} 调用 {@code mergeAgent2McpConfigs}
 * 在请求粒度合并 MCP 配置；{@code McpClientUtil} 调用 {@code createAgent2McpTransports}
 * 为每个启用的 Server 创建 SSE 传输层。
 *
 * @author Makoto
 */
public class McpConfigMergeUtil {

	private static final Logger logger = LoggerFactory.getLogger(McpConfigMergeUtil.class);

	/**
	 * 将文件静态配置与运行时动态配置合并，返回最终生效的配置 Map。
	 * runtimeSettings 的结构与 mcp-config.json 相同（agentName -> { "mcp-servers": [...] }）。
	 */
	public static Map<String, McpAssignNodeProperties.McpServerConfig> mergeAgent2McpConfigs(
			Map<String, McpAssignNodeProperties.McpServerConfig> staticConfig, Map<String, Object> runtimeSettings,
			ObjectMapper objectMapper) {

		Map<String, McpAssignNodeProperties.McpServerConfig> result = new HashMap<>();

		// 这边复制所有静态配置
		for (Map.Entry<String, McpAssignNodeProperties.McpServerConfig> entry : staticConfig.entrySet()) {
			String agentName = entry.getKey();
			List<McpAssignNodeProperties.McpServerInfo> staticServers = new ArrayList<>(
					Optional.ofNullable(entry.getValue().mcpServers()).orElse(List.of()));
			result.put(agentName, new McpAssignNodeProperties.McpServerConfig(staticServers));
		}

		// 处理动态配置
		for (Map.Entry<String, Object> entry : runtimeSettings.entrySet()) {
			String agentName = entry.getKey();

			if (entry.getValue() instanceof Map) {
				Map<String, Object> agentConfig = (Map<String, Object>) entry.getValue();

				if (agentConfig.containsKey("mcp-servers")) {
					McpAssignNodeProperties.McpServerConfig dynamicConfig = objectMapper.convertValue(agentConfig,
							McpAssignNodeProperties.McpServerConfig.class);

					// 合并该Agent的服务器配置
					List<McpAssignNodeProperties.McpServerInfo> mergedServers = mergeAgent2McpServers(
							result.getOrDefault(agentName, new McpAssignNodeProperties.McpServerConfig(List.of()))
								.mcpServers(),
							dynamicConfig.mcpServers());

					result.put(agentName, new McpAssignNodeProperties.McpServerConfig(mergedServers));
					logger.debug("Merged MCP config for agent: {}", agentName);
				}
			}
		}

		return result;
	}

	/**
	 * 合并两个 Server 列表：以 URL 为 key，动态配置覆盖静态配置，新 URL 则追加。
	 * 使用 LinkedHashMap 保持 Server 顺序稳定。
	 */
	public static List<McpAssignNodeProperties.McpServerInfo> mergeAgent2McpServers(
			List<McpAssignNodeProperties.McpServerInfo> staticServers,
			List<McpAssignNodeProperties.McpServerInfo> dynamicServers) {

		Map<String, McpAssignNodeProperties.McpServerInfo> serverMap = new LinkedHashMap<>();

		for (McpAssignNodeProperties.McpServerInfo server : staticServers) {
			serverMap.put(server.url(), server);
		}

		// 动态服务器覆盖或添加
		for (McpAssignNodeProperties.McpServerInfo server : dynamicServers) {
			serverMap.put(server.url(), server);
		}

		return new ArrayList<>(serverMap.values());
	}

	/**
	 * 将 McpServerConfig 中启用的 Server 转换为 WebFluxSseClientTransport 列表。
	 * transportName 格式：agentName-{url.hashCode()}，用于在 configurer 中区分不同连接。
	 * sseEndpoint 未配置时默认使用 /sse。
	 */
	public static List<NamedClientMcpTransport> createAgent2McpTransports(String agentName,
			McpAssignNodeProperties.McpServerConfig config, WebClient.Builder webClientBuilderTemplate,
			ObjectMapper objectMapper) {
		List<NamedClientMcpTransport> transports = new ArrayList<>();

		for (McpAssignNodeProperties.McpServerInfo serverInfo : config.mcpServers()) {
			if (!serverInfo.enabled()) {
				continue;
			}

			WebClient.Builder webClientBuilder = webClientBuilderTemplate.clone().baseUrl(serverInfo.url());
			String sseEndpoint = serverInfo.sseEndpoint() != null ? serverInfo.sseEndpoint() : "/sse";
			WebFluxSseClientTransport transport = WebFluxSseClientTransport.builder(webClientBuilder)
				.sseEndpoint(sseEndpoint)
				.objectMapper(objectMapper)
				.build();
			String transportName = agentName + "-" + serverInfo.url().hashCode();
			transports.add(new NamedClientMcpTransport(transportName, transport));
		}

		return transports;
	}

}
