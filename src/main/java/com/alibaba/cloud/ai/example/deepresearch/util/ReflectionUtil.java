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

package com.alibaba.cloud.ai.example.deepresearch.util;

import com.alibaba.cloud.ai.example.deepresearch.model.dto.Plan;
import com.alibaba.cloud.ai.example.deepresearch.model.dto.ReflectionResult;

import java.util.List;

/**
 * Reflection 静态工具方法集合，封装节点侧判断和辅助逻辑，与 {@link ReflectionProcessor} 配合使用。
 *
 * <p>
 * 项目职责：提供四类静态方法：(1) 判断 Step 是否由指定节点处理； (2) 将反思历史序列化为 Markdown 以注入重试 Prompt； (3) 判断
 * {@code ReflectionHandleResult} 后节点是否继续执行； (4) 根据是否启用 Reflection 返回节点完成后应设置的执行状态字符串。
 *
 * <p>
 * 被使用情况：{@code ResearcherNode} 和 {@code CoderNode} 在 apply() 中调用本类方法， 判断是否应处理当前
 * Step、是否追加历史反思内容，以及获取执行完成后的状态值。
 *
 * @author sixiyida
 * @since 2025/7/10
 */
public class ReflectionUtil {

	/**
	 * 判断 Step 当前是否应由指定节点处理。 满足以下任意状态即返回 true：
	 * <ul>
	 * <li>{@code assigned_<nodeName>} — 首次分配</li>
	 * <li>{@code waiting_processing_<nodeName>} — 反思不通过，等待重新执行</li>
	 * <li>{@code waiting_reflecting_<nodeName>} — 执行完成，等待反思评估</li>
	 * </ul>
	 * @param step 计划步骤
	 * @param nodeName 节点名称，如 {@code researcher_0}
	 * @return 是否应由该节点处理
	 */
	public static boolean shouldProcessStep(Plan.Step step, String nodeName) {
		String status = step.getExecutionStatus();
		if (status == null) {
			return false;
		}

		// 首次分配
		if (status.equals(StateUtil.EXECUTION_STATUS_ASSIGNED_PREFIX + nodeName)) {
			return true;
		}

		// 反思不通过，等待重试
		if (status.equals(StateUtil.EXECUTION_STATUS_WAITING_PROCESSING + nodeName)) {
			return true;
		}

		// 执行完成，ReflectionProcessor 需要在此状态下触发评估
		if (status.equals(StateUtil.EXECUTION_STATUS_WAITING_REFLECTING + nodeName)) {
			return true;
		}

		return false;
	}

	/**
	 * 将 Step 的反思历史序列化为 Markdown 格式， 用于在重新执行时注入到 prompt，让节点了解历史评估意见并改进。
	 * @param step 计划步骤
	 * @return 包含所有历史反思记录的 Markdown 字符串，无历史时返回空串
	 */
	public static String buildReflectionHistoryContent(Plan.Step step) {
		List<ReflectionResult> reflectionHistory = step.getReflectionHistory();
		if (reflectionHistory == null || reflectionHistory.isEmpty()) {
			return "";
		}

		StringBuilder content = new StringBuilder();
		content.append("## Previous Attempts and Feedback\n\n");
		content.append(
				"**Important Note**: The following are reflection results from previous attempts. Please refer to this feedback to improve your response and avoid repeating the same issues.\n\n");

		for (int i = 0; i < reflectionHistory.size(); i++) {
			ReflectionResult record = reflectionHistory.get(i);
			content.append("### Attempt ").append(i + 1).append("\n\n");

			// Add previous execution result
			if (record.hasExecutionResult()) {
				content.append("**Previous Execution Result**:\n");
				content.append(record.getExecutionResult()).append("\n\n");
			}

			content.append("**Reflection Feedback**:\n");
			content.append(record.getFeedback()).append("\n\n");

			content.append("**Evaluation Result**: ").append(record.isPassed() ? "Passed" : "Failed").append("\n\n");
			content.append("---\n\n");
		}

		return content.toString();
	}

	/**
	 * 判断节点在 handleReflection 返回后是否应继续执行业务逻辑。
	 */
	public static boolean shouldContinueAfterReflection(ReflectionProcessor.ReflectionHandleResult result) {
		return result != null && result.shouldContinueProcessing();
	}

	/**
	 * 根据 Reflection 是否启用，返回节点执行完成后应设置的状态：
	 * <ul>
	 * <li>启用：设为 {@code waiting_reflecting_*}，等待评估</li>
	 * <li>未启用：直接设为 {@code completed_*}</li>
	 * </ul>
	 * @param hasReflectionProcessor 是否注入了 ReflectionProcessor（null 表示未启用）
	 * @param nodeName 节点名称
	 * @return 应设置的 executionStatus 字符串
	 */
	public static String getCompletionStatus(boolean hasReflectionProcessor, String nodeName) {
		if (hasReflectionProcessor) {
			return StateUtil.EXECUTION_STATUS_WAITING_REFLECTING + nodeName;
		}
		else {
			return StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName;
		}
	}

	/**
	 * 判断 Step 是否存在历史反思记录（即已经被反思评估过至少一次）。
	 */
	public static boolean hasReflectionHistory(Plan.Step step) {
		return step.getReflectionHistory() != null && !step.getReflectionHistory().isEmpty();
	}

}
