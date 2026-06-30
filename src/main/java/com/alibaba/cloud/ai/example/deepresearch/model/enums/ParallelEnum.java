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

package com.alibaba.cloud.ai.example.deepresearch.model.enums;

/**
 * 并行节点类型枚举，定义可动态扩展的并行 Agent 角色（研究员和编程员）。
 *
 * <p>
 * 项目职责：枚举值作为节点名前缀，配合并行节点数量配置动态生成 {@code researcher_N} 和 {@code coder_N} 节点，驱动图的并行执行结构。
 *
 * <p>
 * 被使用情况：{@code DeepResearchConfiguration} 根据该枚举遍历配置动态注册并行节点； {@code ParallelExecutorNode}
 * 按步骤类型分配任务到对应并行节点； {@code ReporterNode} 聚合并行节点产出内容。
 *
 * @author yingzi
 * @since 2025/6/14
 */
public enum ParallelEnum {

	RESEARCHER("researcher"), CODER("coder");

	private final String value;

	ParallelEnum(String value) {
		this.value = value;
	}

	public String getValue() {
		return value;
	}

}
