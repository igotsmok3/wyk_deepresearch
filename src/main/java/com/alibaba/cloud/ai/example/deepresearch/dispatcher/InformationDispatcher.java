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
 * {@code information} 节点的条件边路由器，根据计划解析结果和用户配置决定下一跳节点。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，读取 {@code InformationNode} 写入的 {@code information_next_node}
 * 键：计划解析成功时路由至 {@code "human_feedback"}（等待确认） 或 {@code "research_team"}（自动接受）；解析失败时回退至
 * {@code "planner"} 重规划， 超出重试次数则终止（{@code END}）。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("information", ...)} 注册到图配置中。
 */
public class InformationDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		// 读取 InformationNode 写入的路由决策，缺省终止图执行
		return (String) state.value("information_next_node", END);
	}

}
