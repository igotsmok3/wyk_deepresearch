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
 * {@code rewrite_multi_query} 节点的条件边路由器，根据查询改写结果决定后续检索路径。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，读取 {@code RewriteAndMultiQueryNode}（负责对原始查询 进行改写和多路扩展）写入的
 * {@code rewrite_multi_query_next_node} 键：有网络搜索需求时路由至
 * {@code "background_investigator"}，仅需检索用户上传文件时路由至 {@code "user_file_rag"}，
 * 异常情况终止（{@code END}）。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("rewrite_multi_query", ...)} 注册到图配置中。
 */
public class RewriteAndMultiQueryDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		// 读取 RewriteAndMultiQueryNode 写入的路由决策，缺省终止图执行
		return (String) state.value("rewrite_multi_query_next_node", END);
	}

}
