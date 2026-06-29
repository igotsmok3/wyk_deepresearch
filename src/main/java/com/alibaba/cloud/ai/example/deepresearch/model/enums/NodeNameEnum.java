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
 * StateGraph 节点名称枚举，统一管理所有流程节点的内部名称及对应的前端展示标题。
 *
 * <p>项目职责：集中定义图中全部节点的字符串标识，避免硬编码分散，
 * 同时提供前端友好的 displayTitle 供 SSE 事件渲染使用。
 *
 * <p>被使用情况：{@code GraphProcess} 通过 {@code fromNodeName} 和 {@code getDisplayTitleByNodeName}
 * 将节点名称映射为前端展示标题。
 */
public enum NodeNameEnum {

	START("__START__", "开始"), END("__END__", "结束"), COORDINATOR("coordinator", "意图识别"),
	REWRITE_MULTI_QUERY("rewrite_multi_query", "查询问题相关信息"), BACKGROUND_INVESTIGATOR("background_investigator", "背景调查"),
	PLANNER("planner", "研究计划"), INFORMATION("information", ""), HUMAN_FEEDBACK("human_feedback", "人工反馈"),
	RESEARCH_TEAM("research_team", "等待并行节点执行完成"), PARALLEL_EXECUTOR("parallel_executor", ""),
	REPORTER("reporter", "报告生成"), RAG_NODE("rag_node", "");

	private final String nodeName;

	/**
	 * 前端渲染的标题
	 */
	private final String displayTitle;

	NodeNameEnum(String nodeName, String displayTitle) {
		this.nodeName = nodeName;
		this.displayTitle = displayTitle;
	}

	public String nodeName() {
		return nodeName;
	}

	public String displayTitle() {
		return displayTitle;
	}

	public static String getDisplayTitleByNodeName(String nodeName) {
		for (NodeNameEnum n : values()) {
			if (n.nodeName.equals(nodeName)) {
				return n.displayTitle;
			}
		}
		return "";
	}

	public static NodeNameEnum fromNodeName(String nodeName) {
		for (NodeNameEnum n : values()) {
			if (n.nodeName.equals(nodeName)) {
				return n;
			}
		}
		return null;
	}

}
