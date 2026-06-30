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
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 信息解析节点：将 PlannerNode 流式输出的 JSON 计划字符串反序列化为 {@code Plan} 对象，并决定后续路由。
 *
 * <p>
 * 项目职责：位于 planner 之后，连接规划输出与执行阶段。从 OverAllState 读取 {@code planner_content}，使用
 * {@code BeanOutputConverter} 将其转换为 {@code Plan}。 反序列化失败时若未超过最大迭代次数则重新路由到 planner；成功后根据
 * {@code auto_accepted_plan} 决定是否跳过人工确认。写入 OverAllState：
 * <ul>
 * <li>{@code current_plan}：反序列化后的计划对象</li>
 * <li>{@code information_next_node}：路由键，取值为 human_feedback、research_team、planner 或
 * END</li>
 * <li>{@code plan_iterations}：自动接受计划时迭代计数 +1</li>
 * </ul>
 *
 * <p>
 * 被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code information} 注册到图中；
 * {@code InformationDispatcher} 读取 {@code information_next_node} 进行边路由。
 *
 * @author yingzi
 * @since 2025/5/18 15:52
 */
public class InformationNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(InformationNode.class);

	private final BeanOutputConverter<Plan> converter;

	public InformationNode() {
		this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<Plan>() {
		});
	}

	@Override
	public Map<String, Object> apply(OverAllState state) {
		String result = state.value("planner_content", "");
		logger.info("planner_content: {}", result);
		assert Strings.isNotBlank(result);

		Plan curPlan = null;
		String nextStep = "research_team";
		Map<String, Object> updated = new HashMap<>();
		try {
			curPlan = converter.convert(result);
			logger.info("反序列成功，convert: {}", curPlan);
		}
		catch (Exception e) {
			// 2.2 反序列化失败，尝试重新生成计划
			logger.error("反序列化失败");
			if (StateUtil.getPlanIterations(state) < StateUtil.getPlanMaxIterations(state)) {
				// 尝试重新生成计划
				updated.put("plan_iterations", StateUtil.getPlanIterations(state) + 1);
				nextStep = "planner";
				updated.put("information_next_node", nextStep);
				logger.info("information node -> {} node", nextStep);
				return updated;
			}
			else {
				nextStep = END;
				updated.put("information_next_node", nextStep);
				logger.warn("information node -> {} node", nextStep);
				return updated;
			}
		}
		// 2.3 上下文不足，跳转到human_feedback节点
		if (!StateUtil.getAutoAcceptedPlan(state)) {
			nextStep = "human_feedback";
		}
		else {
			nextStep = "research_team";
			updated.put("plan_iterations", StateUtil.getPlanIterations(state) + 1);
		}
		updated.put("current_plan", curPlan);
		updated.put("information_next_node", nextStep);
		logger.info("information node -> {} node", nextStep);
		return updated;
	}

}
