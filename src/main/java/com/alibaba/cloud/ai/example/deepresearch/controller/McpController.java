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

package com.alibaba.cloud.ai.example.deepresearch.controller;

import com.alibaba.cloud.ai.example.deepresearch.model.dto.McpServerInfo;
import com.alibaba.cloud.ai.example.deepresearch.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务信息查询控制器，提供系统已配置的 MCP 服务列表查询接口。
 *
 * <p>
 * 项目职责：controller 层的辅助接口，暴露 {@code /api/mcp/services} REST 端点， 通过
 * {@link com.alibaba.cloud.ai.example.deepresearch.service.McpService} 读取 当前运行时所有已注册的 MCP
 * 工具服务器信息，供前端展示和配置选择使用。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理，无其他 Java 类直接引用。
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

	private static final Logger logger = LoggerFactory.getLogger(McpController.class);

	private final McpService mcpService;

	public McpController(McpService mcpService) {
		this.mcpService = mcpService;
	}

	/**
	 * 获取所有默认 MCP 服务信息概览 返回系统中配置的所有 MCP 服务信息
	 * @return MCP 服务信息概览
	 */
	@GetMapping("/services")
	public ResponseEntity<Map<String, Object>> getAllMcpServices() {
		logger.info("获取默认 MCP 服务信息");

		List<McpServerInfo> mcpServices = mcpService.getAllMcpServices();

		Map<String, Object> response = new HashMap<>();
		response.put("success", true);
		response.put("message", "成功获取默认 MCP 服务信息");
		response.put("data", mcpServices);
		response.put("summary", mcpService.createServiceSummary(mcpServices));

		logger.info("返回 {} 个 MCP 服务信息", mcpServices.size());
		return ResponseEntity.ok(response);
	}

}
