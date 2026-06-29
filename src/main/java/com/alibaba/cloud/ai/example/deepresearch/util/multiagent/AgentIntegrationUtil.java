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

package com.alibaba.cloud.ai.example.deepresearch.util.multiagent;

import com.alibaba.cloud.ai.example.deepresearch.config.SmartAgentProperties;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SmartAgentDispatcherService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.QuestionClassifierService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SearchPlatformSelectionService;
import com.alibaba.cloud.ai.example.deepresearch.service.multiagent.SmartAgentSelectionHelperService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能 Agent 集成工具类，提供 SmartAgent 可用性检查与 {@code SmartAgentSelectionHelperService} 实例创建能力。
 *
 * <p>项目职责：封装智能 Agent 子系统的可用性判断（Feature Flag 开关 + 必要服务注入检查）
 * 以及选择器辅助服务的统一创建，简化各节点中的重复集成代码。
 *
 * <p>被使用情况：{@code ResearcherNode} 和 {@code BackgroundInvestigationNode} 在构造期间
 * 调用 {@code createSelectionHelper} 初始化智能 Agent 选择器；
 * {@code SmartAgentSelectionHelperService} 调用 {@code isSmartAgentAvailable} 进行前置校验。
 *
 * @author Makoto
 * @since 2025/07/17
 */
public class AgentIntegrationUtil {

	private static final Logger logger = LoggerFactory.getLogger(AgentIntegrationUtil.class);

	/**
	 * 创建智能Agent选择辅助器
	 */
	public static SmartAgentSelectionHelperService createSelectionHelper(SmartAgentProperties smartAgentProperties,
			SmartAgentDispatcherService smartAgentDispatcher, QuestionClassifierService questionClassifierService,
			SearchPlatformSelectionService searchPlatformSelectionService) {
		return new SmartAgentSelectionHelperService(smartAgentProperties, smartAgentDispatcher,
				questionClassifierService, searchPlatformSelectionService);
	}

	public static boolean isSmartAgentAvailable(SmartAgentProperties smartAgentProperties, Object... services) {
		if (smartAgentProperties == null || !smartAgentProperties.isEnabled()) {
			return false;
		}

		for (Object service : services) {
			if (service == null) {
				logger.warn("智能Agent必要服务不可用，回退到原有逻辑");
				return false;
			}
		}

		return true;
	}

}
