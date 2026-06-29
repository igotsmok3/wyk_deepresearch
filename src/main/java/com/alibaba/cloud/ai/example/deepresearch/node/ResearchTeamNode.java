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

package com.alibaba.cloud.ai.example.deepresearch.node;

import com.alibaba.cloud.ai.example.deepresearch.model.dto.Plan;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 研究团队调度节点：检查研究计划中所有步骤是否都已执行完毕，决定是继续分配任务还是进入报告阶段。
 *
 * <p>项目职责：并行 researcher/coder 节点的汇聚点和循环调度入口。从 OverAllState 读取
 * {@code current_plan}，检查所有步骤的 {@code executionStatus} 是否均已为 completed 或 error。
 * 写入 OverAllState：{@code research_team_next_node}，取值为：
 * <ul>
 *   <li>{@code parallel_executor}：仍有未完成步骤，继续分配执行</li>
 *   <li>{@code professional_kb_decision}：所有步骤完成，进入知识库决策阶段</li>
 * </ul>
 *
 * <p>被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code research_team} 注册到图中；
 * {@code ResearchTeamDispatcher} 读取 {@code research_team_next_node} 进行边路由；
 * {@code HumanFeedbackNode} 用户确认计划后路由到本节点。
 *
 * @author sixiyida
 * @since 2025/6/12 09:14
 */
public class ResearchTeamNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(ResearchTeamNode.class);

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("research_team node is running.");
		String nextStep = "professional_kb_decision";
		Map<String, Object> updated = new HashMap<>();

		Plan curPlan = StateUtil.getPlan(state);
		// 判断steps里的每个step都有执行结果
		if (!areAllExecutionResultsPresent(curPlan)) {
			nextStep = "parallel_executor";
		}
		updated.put("research_team_next_node", nextStep);
		logger.info("research_team node -> {} node", nextStep);
		return updated;
	}

	public boolean areAllExecutionResultsPresent(Plan plan) {
		if (CollectionUtils.isEmpty(plan.getSteps())) {
			return false;
		}

		return plan.getSteps()
			.stream()
			.allMatch(step -> step.getExecutionStatus() != null
					&& (step.getExecutionStatus().startsWith(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX)
							|| step.getExecutionStatus().startsWith(StateUtil.EXECUTION_STATUS_ERROR_PREFIX)));
	}

}
