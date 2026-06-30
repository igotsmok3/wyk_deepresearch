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

/**
 * 反思评估结果数据传输对象，记录 LLM 对某一步骤执行结果的质量判断及改进建议。
 *
 * <p>
 * 项目职责：由 {@code ReflectionProcessor} 将 LLM 输出的 JSON 反序列化为此类， 并追加到
 * {@code Plan.Step#reflectionHistory} 列表，驱动节点在质量不达标时重试执行。
 *
 * <p>
 * 被使用情况：{@code ReflectionProcessor} 创建实例并写入执行结果快照； {@code ReflectionUtil} 读取历史列表构建反思
 * Prompt； {@code Plan.Step} 持有反思历史列表。
 *
 * @author sixiyida
 * @since 2025/7/10
 */
public class ReflectionResult {

	/**
	 * 评估是否通过：true=通过，false=需要重新执行。
	 */
	private boolean passed;

	/**
	 * 评估反馈文本，包含具体的不足之处和改进建议， 会在下一次执行时注入 prompt 让节点参考改进。
	 */
	private String feedback;

	/**
	 * 本次被评估的执行结果快照，便于在历史记录中回溯对比。 由 ReflectionProcessor 在评估后写入，并非 LLM 输出字段。
	 */
	private String executionResult;

	public ReflectionResult() {
	}

	public ReflectionResult(boolean passed, String feedback) {
		this.passed = passed;
		this.feedback = feedback;
	}

	public ReflectionResult(boolean passed, String feedback, String executionResult) {
		this.passed = passed;
		this.feedback = feedback;
		this.executionResult = executionResult;
	}

	public boolean isPassed() {
		return passed;
	}

	public void setPassed(boolean passed) {
		this.passed = passed;
	}

	public String getFeedback() {
		return feedback;
	}

	public void setFeedback(String feedback) {
		this.feedback = feedback;
	}

	public String getExecutionResult() {
		return executionResult;
	}

	public void setExecutionResult(String executionResult) {
		this.executionResult = executionResult;
	}

	public boolean hasExecutionResult() {
		return executionResult != null && !executionResult.trim().isEmpty();
	}

	@Override
	public String toString() {
		return String.format("ReflectionResult{passed=%s, feedback='%s', executionResult='%s'}", passed, feedback,
				executionResult);
	}

}
