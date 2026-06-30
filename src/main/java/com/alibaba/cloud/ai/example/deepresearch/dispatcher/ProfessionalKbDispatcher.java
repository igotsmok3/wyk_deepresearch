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
 * {@code professional_kb_decision} 节点的条件边路由器，根据专业知识库启用标志决定下一跳节点。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，与其他 Dispatcher 不同，本类不依赖节点写入的路由键， 而是直接读取业务标志
 * {@code use_professional_kb} 在路由层内联完成二选一决策： {@code true} 路由至
 * {@code "professional_kb_rag"}，{@code false} 路由至 {@code "reporter"}。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("professional_kb_decision", ...)} 注册到图配置中。
 */
public class ProfessionalKbDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) {
		// 直接读取专业知识库启用标志，在 Dispatcher 层内联完成路由决策
		Boolean need = state.value("use_professional_kb", false);
		return Boolean.TRUE.equals(need) ? "professional_kb_rag" : "reporter";
	}

}
