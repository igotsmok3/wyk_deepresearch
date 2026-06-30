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
 * {@code background_investigator} 节点的条件边路由器，根据背景调查结果决定下一跳节点。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，读取 {@code BackgroundInvestigationNode} 执行后 写入状态的
 * {@code background_investigation_next_node} 键，将图流程引导至：
 * {@code "planner"}（调查充分，进入规划）、{@code "reporter"}（问题简单，直接生成报告） 或 {@code END}（异常终止）。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("background_investigator", ...)} 注册到图配置中。
 */
public class BackgroundInvestigationDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		// 读取 BackgroundInvestigationNode 写入的路由决策，缺省终止图执行
		return (String) state.value("background_investigation_next_node", END);
	}

}
