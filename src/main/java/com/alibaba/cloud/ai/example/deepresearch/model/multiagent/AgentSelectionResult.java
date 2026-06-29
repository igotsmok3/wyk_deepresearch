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

package com.alibaba.cloud.ai.example.deepresearch.model.multiagent;

import org.springframework.ai.chat.client.ChatClient;

import java.util.Map;

/**
 * 智能 Agent 最终选择结果，封装执行研究任务所用的 Agent 实例、类型及选择原因。
 *
 * <p>项目职责：由 {@code SmartAgentSelectionHelperService} 组装并返回，
 * 作为节点执行前的 Agent 决策载体，{@code isSmartAgent} 标识是否启用了多 Agent 路由，
 * {@code stateUpdate} 携带需要写回图状态的配置信息。
 *
 * <p>被使用情况：{@code ResearcherNode} 调用 {@code selectSmartAgent} 获取该结果，
 * 并据此选择实际执行研究任务的 Agent；
 * {@code SmartAgentSelectionHelperService} 负责构建并返回该对象。
 *
 * @author Makoto
 * @since 2025/07/17
 */
public class AgentSelectionResult {

	private final ChatClient selectedAgent;

	private final AgentType agentType;

	private final boolean isSmartAgent;

	private final String reason;

	private final Map<String, Object> stateUpdate;

	public AgentSelectionResult(ChatClient selectedAgent, AgentType agentType, boolean isSmartAgent, String reason) {
		this(selectedAgent, agentType, isSmartAgent, reason, Map.of());
	}

	public AgentSelectionResult(ChatClient selectedAgent, AgentType agentType, boolean isSmartAgent, String reason,
			Map<String, Object> stateUpdate) {
		this.selectedAgent = selectedAgent;
		this.agentType = agentType;
		this.isSmartAgent = isSmartAgent;
		this.reason = reason;
		this.stateUpdate = stateUpdate != null ? stateUpdate : Map.of();
	}

	public ChatClient getSelectedAgent() {
		return selectedAgent;
	}

	public AgentType getAgentType() {
		return agentType;
	}

	public boolean isSmartAgent() {
		return isSmartAgent;
	}

	public String getReason() {
		return reason;
	}

	public Map<String, Object> getStateUpdate() {
		return stateUpdate;
	}

	@Override
	public String toString() {
		return String.format("AgentSelectionResult{agentType=%s, isSmartAgent=%s, reason='%s'}", agentType,
				isSmartAgent, reason);
	}

}
