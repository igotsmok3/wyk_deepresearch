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

package com.alibaba.cloud.ai.example.deepresearch.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 研究计划数据传输对象，描述 Planner 节点生成的完整执行计划及其步骤列表。
 *
 * <p>
 * 项目职责：作为图状态（OverAllState）中 {@code current_plan} 键对应的核心数据结构， 在
 * Planner、ParallelExecutor、Researcher、Coder 等节点之间传递任务上下文， 内部 {@code Step}
 * 嵌套类记录每步执行结果和反思历史。
 *
 * <p>
 * 被使用情况：{@code DeepResearchDeserializer} 反序列化图状态中的计划；
 * {@code StateUtil}、{@code ReflectionProcessor}、{@code ReflectionUtil} 读取步骤信息进行反思判断；
 * {@code ParallelExecutorNode} 遍历步骤分配并行任务。
 *
 * @author yingzi
 * @author ViliamSun
 * @since 2025/5/18 17:48
 */
public class Plan {

	private String title;

	@JsonProperty("has_enough_context")
	private boolean hasEnoughContext;

	private String thought;

	private List<Step> steps;

	public static class Step {

		@JsonProperty("need_web_search")
		private boolean needWebSearch;

		private String title;

		private String description;

		@JsonProperty("step_type")
		private StepType stepType;

		private String executionRes;

		private String executionStatus;

		/**
		 * 反思历史，记录每轮反思的评估结果（含 feedback 和 executionResult 快照）。 列表长度等于已经触发的反思次数，可作为重试计数器。
		 * 非空时会将历史 feedback 注入到下一次执行的 prompt 中。
		 */
		private List<ReflectionResult> reflectionHistory;

		public boolean isNeedWebSearch() {
			return needWebSearch;
		}

		public void setNeedWebSearch(boolean needWebSearch) {
			this.needWebSearch = needWebSearch;
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public String getDescription() {
			return description;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public StepType getStepType() {
			return stepType;
		}

		public void setStepType(StepType stepType) {
			this.stepType = stepType;
		}

		public String getExecutionRes() {
			return executionRes;
		}

		public void setExecutionRes(String executionRes) {
			this.executionRes = executionRes;
		}

		public String getExecutionStatus() {
			return executionStatus;
		}

		public void setExecutionStatus(String executionStatus) {
			this.executionStatus = executionStatus;
		}

		public List<ReflectionResult> getReflectionHistory() {
			if (reflectionHistory == null) {
				reflectionHistory = new ArrayList<>();
			}
			return reflectionHistory;
		}

		public void setReflectionHistory(List<ReflectionResult> reflectionHistory) {
			this.reflectionHistory = reflectionHistory;
		}

		/**
		 * 添加反思记录
		 */
		public void addReflectionRecord(ReflectionResult record) {
			getReflectionHistory().add(record);
		}

	}

	public enum StepType {

		@JsonProperty("research")
		@JsonAlias("RESEARCH")
		RESEARCH,

		@JsonProperty("processing")
		@JsonAlias("PROCESSING")
		PROCESSING

	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getThought() {
		return thought;
	}

	public void setThought(String thought) {
		this.thought = thought;
	}

	public List<Step> getSteps() {
		return steps;
	}

	public void setSteps(List<Step> steps) {
		this.steps = steps;
	}

	public void setHasEnoughContext(boolean hasEnoughContext) {
		this.hasEnoughContext = hasEnoughContext;
	}

	public boolean isHasEnoughContext() {
		return hasEnoughContext;
	}

}
