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
 * 流式节点前缀枚举，标识各类 LLM 流式输出节点的前缀字符串及其前端可见性。
 *
 * <p>项目职责：各节点在向 SSE 通道写入流式内容时，以枚举前缀拼接节点 ID 作为事件名，
 * {@code visible} 字段控制前端是否展示该节点的流式内容。
 *
 * <p>被使用情况：{@code GraphProcess} 通过 {@code match} 方法识别当前节点类型决定处理逻辑；
 * {@code PlannerNode}、{@code ResearcherNode}、{@code CoderNode}、{@code ReporterNode}
 * 各自取对应的前缀拼接流式输出事件名。
 */
public enum StreamNodePrefixEnum {

	PLANNER_LLM_STREAM("planner_llm_stream", false), RESEARCHER_LLM_STREAM("researcher_llm_stream", true),
	RESEARCHER_REFLECT_LLM_STREAM("researcher_reflect_llm_stream", true),
	CODER_REFLECT_LLM_STREAM("coder_reflect_llm_stream", true), CODER_LLM_STREAM("coder_llm_stream", true),
	REPORTER_LLM_STREAM("reporter_llm_stream", true);

	/** 节点前缀字符串 */
	private final String prefix;

	/** 是否需要前端展示 */
	private final boolean visible;

	/**
	 * 构造方法。
	 * @param prefix 节点前缀字符串
	 * @param visible 是否可见
	 */
	StreamNodePrefixEnum(String prefix, boolean visible) {
		this.prefix = prefix;
		this.visible = visible;
	}

	/**
	 * 获取前缀字符串。
	 * @return 前缀
	 */
	public String getPrefix() {
		return prefix;
	}

	/**
	 * 获取是否可见。
	 * @return visible
	 */
	public boolean isVisible() {
		return visible;
	}

	/**
	 * 判断给定节点名是否以任一枚举前缀开头。
	 * @param nodeName 节点名
	 * @return 匹配到的枚举实例，未匹配返回null
	 */
	public static StreamNodePrefixEnum match(String nodeName) {
		for (StreamNodePrefixEnum p : values()) {
			if (nodeName != null && nodeName.startsWith(p.prefix)) {
				return p;
			}
		}
		return null;
	}

}
