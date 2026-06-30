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

package com.alibaba.cloud.ai.example.deepresearch.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * {@code human_feedback} 节点的条件边路由器，根据用户对计划的反馈决定下一跳节点。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，读取 {@code human_next_node} 键处理人工干预检查点的
 * 流程分支：{@code "planner"}（用户拒绝计划，返回重新规划）、 {@code "research_team"}（用户接受计划，进入研究执行）或
 * {@code END}（终止）。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("human_feedback", ...)} 注册到图配置中。
 */
public class HumanFeedbackDispatcher implements EdgeAction {

	public HumanFeedbackDispatcher() {
	}

	@Override
	public String apply(OverAllState state) throws Exception {
		// 读取人工反馈后写入的路由决策，缺省终止图执行
		return (String) state.value("human_next_node", END);
	}

}
