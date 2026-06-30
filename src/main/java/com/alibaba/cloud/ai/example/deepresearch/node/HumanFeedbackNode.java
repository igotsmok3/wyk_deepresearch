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

import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 人工反馈节点：图执行的人工干预检查点，等待用户对研究计划进行确认或修改。
 *
 * <p>
 * 项目职责：位于 information 之后，研究执行之前（interrupt 点）。从 OverAllState 读取 {@code plan_iterations} 和
 * {@code human_feedback}，处理逻辑如下： 超过最大迭代次数时直接放行；用户确认计划（feedback=true）时路由到 research_team；
 * 用户拒绝并提供修改意见（feedback=false）时路由到 planner 重新规划，并写入 {@code feedback_content}。写入
 * OverAllState：
 * <ul>
 * <li>{@code human_next_node}：路由键，取值为 planner 或 research_team</li>
 * <li>{@code plan_iterations}：迭代计数 +1</li>
 * <li>{@code feedback_content}：用户反馈内容（可选）</li>
 * </ul>
 *
 * <p>
 * 被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code human_feedback} 注册到图中， 并配置为
 * interrupt 前置点；{@code HumanFeedbackDispatcher} 读取 {@code human_next_node} 进行边路由。
 *
 * @author yingzi
 * @since 2025/5/18 16:54
 */
public class HumanFeedbackNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(HumanFeedbackNode.class);

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("human_feedback node is running.");
		String nextStep = "research_team";
		Map<String, Object> updated = new HashMap<>();

		// check the Maximum number of iterations
		int planIterations = StateUtil.getPlanIterations(state);
		int maxPlanIterations = StateUtil.getPlanMaxIterations(state);
		if (planIterations >= maxPlanIterations) {
			logger.info("Maximum number of iterations exceeded, planIterations:{}, maxPlanIterations:{}",
					planIterations, maxPlanIterations);
			logger.info("human_feedback node -> {} node", nextStep);
			updated.put("human_next_node", nextStep);
			return updated;
		}

		// iterations+1
		updated.put("plan_iterations", StateUtil.getPlanIterations(state) + 1);

		Map<String, Object> feedbackData = state.humanFeedback().data();
		boolean feedback = (boolean) feedbackData.getOrDefault("feedback", true);

		if (!feedback) {
			nextStep = "planner";
			updated.put("human_next_node", nextStep);

			String feedbackContent = feedbackData.getOrDefault("feedback_content", "").toString();
			if (StringUtils.hasLength(feedbackContent)) {
				updated.put("feedback_content", feedbackContent);
				logger.info("Human feedback content: {}", feedbackContent);
			}
			state.withoutResume();
		}
		else {
			updated.put("human_next_node", nextStep);
		}
		logger.info("human_feedback node -> {} node", nextStep);
		return updated;
	}

}
