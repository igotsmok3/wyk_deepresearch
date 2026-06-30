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

/**
 * {@code research_team} 节点的条件边路由器，控制并行研究任务的循环与退出。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，读取 {@code ResearchTeamNode}（并行 researcher/coder 节点的汇聚点）写入的
 * {@code research_team_next_node} 键：仍有未完成步骤时路由至 {@code "parallel_executor"}
 * 继续分发，全部步骤完成后路由至 {@code "professional_kb_decision"}； 缺省值为 {@code "planner"}
 * 以防止状态键缺失时意外终止图。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("research_team", ...)} 注册到图配置中。
 */
public class ResearchTeamDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		// 读取 ResearchTeamNode 写入的路由决策，缺省回到 planner（防止状态键缺失时异常终止）
		return (String) state.value("research_team_next_node", "planner");
	}

}
