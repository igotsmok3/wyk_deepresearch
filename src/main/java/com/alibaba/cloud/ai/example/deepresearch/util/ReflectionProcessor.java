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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Reflection 核心处理器，负责在节点执行完成后对结果进行质量评估，并根据评估结论决定是通过还是驱动节点重新执行。
 *
 * <p>项目职责：封装"执行完成 → 质量评估 → 通过/重试"的完整反思循环，调用 reflectionAgent（LLM）
 * 对 Step 执行结果评分，超过最大重试次数时强制通过，防止死循环；
 * 由 {@code DeepResearchConfiguration#reflectionProcessor()} 创建，以同一实例注入所有并行节点。
 *
 * <p>被使用情况：{@code DeepResearchConfiguration} 在 Reflection 功能启用时创建本类 Bean 并注入；
 * {@code ResearcherNode} 和 {@code CoderNode} 在 {@code apply()} 入口处调用 {@code handleReflection}
 * 决定是否继续执行业务逻辑；{@code ReflectionUtil} 提供配套的静态判断方法。
 *
 * @author sixiyida
 * @since 2025/7/10
 */
public class ReflectionProcessor {

	private static final Logger logger = LoggerFactory.getLogger(ReflectionProcessor.class);

	/** 专门用于质量评估的 LLM 客户端，system prompt 为 prompts/reflection.md */
	private final ChatClient reflectionAgent;

	/** 单个 Step 允许的最大反思次数，超出后强制标记通过 */
	private final int maxReflectionAttempts;

	/** 将 LLM 的 JSON 输出反序列化为 ReflectionResult 的转换器 */
	private final BeanOutputConverter<ReflectionResult> converter;

	public ReflectionProcessor(ChatClient reflectionAgent, int maxReflectionAttempts) {
		this.reflectionAgent = reflectionAgent;
		this.maxReflectionAttempts = maxReflectionAttempts;
		this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<ReflectionResult>() {
		});
	}

	/**
	 * Reflection 入口，在节点 apply() 开头调用，根据 Step 当前状态决定下一步动作。
	 *
	 * <ul>
	 *   <li>{@code waiting_reflecting_*}：执行评估，通过则 completed，否则转 waiting_processing</li>
	 *   <li>{@code waiting_processing_*}：清空旧结果，返回 continueProcessing 让节点重新执行</li>
	 *   <li>其他状态（assigned / processing）：直接 continueProcessing，走正常首次执行路径</li>
	 * </ul>
	 *
	 * @param step     当前被处理的计划步骤
	 * @param nodeName 节点名称，如 {@code researcher_0}
	 * @param nodeType 节点类型，{@code "researcher"} 或 {@code "coder"}
	 * @return 指示节点是否应继续执行业务逻辑的结果对象
	 */
	public ReflectionHandleResult handleReflection(Plan.Step step, String nodeName, String nodeType) {
		String currentStatus = step.getExecutionStatus();

		// Step 刚完成执行，等待本方法触发质量评估
		if (currentStatus != null && currentStatus.startsWith(StateUtil.EXECUTION_STATUS_WAITING_REFLECTING)) {
			return performReflection(step, nodeName, nodeType);
		}

		// 上一轮反思不通过，节点需要重新执行；清空旧结果并返回继续执行
		if (currentStatus != null && currentStatus.startsWith(StateUtil.EXECUTION_STATUS_WAITING_PROCESSING)) {
			step.setExecutionStatus(StateUtil.EXECUTION_STATUS_PROCESSING_PREFIX + nodeName);
			step.setExecutionRes("");
			logger.info("Step {} is ready for reprocessing", step.getTitle());
			return ReflectionHandleResult.continueProcessing();
		}

		return ReflectionHandleResult.continueProcessing();
	}

	/**
	 * 执行质量评估，调用 reflectionAgent 判断结果好坏。
	 * 超出最大重试次数时强制通过，防止死循环。
	 */
	private ReflectionHandleResult performReflection(Plan.Step step, String nodeName, String nodeType) {
		try {
			int attemptCount = getReflectionAttemptCount(step);
			// 已达上限，强制视为通过
			if (attemptCount >= maxReflectionAttempts) {
				logger.warn("Step {} has reached maximum reflection attempts {}, forcing pass", step.getTitle(),
						maxReflectionAttempts);
				step.setExecutionStatus(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName);
				return ReflectionHandleResult.skipProcessing();
			}

			boolean qualityGood = evaluateStepQuality(step, nodeType);

			if (qualityGood) {
				// 评估通过：标记完成，节点无需再执行
				step.setExecutionStatus(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName);
				logger.info("Step {} reflection passed, quality is acceptable", step.getTitle());
				return ReflectionHandleResult.skipProcessing();
			}
			else {
				// 评估不通过：记录重试次数，等待下一轮重新执行
				incrementReflectionAttemptCount(step);
				step.setExecutionStatus(StateUtil.EXECUTION_STATUS_WAITING_PROCESSING + nodeName);
				logger.info("Step {} reflection failed, marked for reprocessing (attempt {})", step.getTitle(),
						attemptCount + 1);
				return ReflectionHandleResult.skipProcessing();
			}

		}
		catch (Exception e) {
			// 评估过程异常时默认通过，避免阻塞整个图执行
			logger.error("Reflection process failed, defaulting to pass: {}", e.getMessage());
			step.setExecutionStatus(StateUtil.EXECUTION_STATUS_COMPLETED_PREFIX + nodeName);
			return ReflectionHandleResult.skipProcessing();
		}
	}

	/**
	 * 调用 reflectionAgent 对 Step 执行结果进行质量评估，
	 * 并将评估结果追加到 Step 的 reflectionHistory。
	 */
	private boolean evaluateStepQuality(Plan.Step step, String nodeType) {
		String evaluationPrompt = buildEvaluationPrompt(step, nodeType);

		try {
			// converter.getFormat() 会在 prompt 末尾注入 JSON 输出格式说明
			var response = reflectionAgent.prompt(converter.getFormat()).user(evaluationPrompt).call().chatResponse();

			String responseText = response.getResult().getOutput().getText().trim();
			ReflectionResult reflectionResult = converter.convert(responseText);

			// 将执行结果快照写入记录，供后续历史展示使用
			reflectionResult.setExecutionResult(step.getExecutionRes());
			step.addReflectionRecord(reflectionResult);

			logger.debug("Step {} quality evaluation result: passed={}, feedback={}", step.getTitle(),
					reflectionResult.isPassed(), reflectionResult.getFeedback());

			return reflectionResult.isPassed();

		}
		catch (Exception e) {
			logger.error("Quality evaluation failed, defaulting to pass: {}", e.getMessage());
			// 解析失败也记录一条默认通过的记录，保持 history 完整性
			ReflectionResult defaultResult = new ReflectionResult(true,
					"Evaluation failed, system default pass: " + e.getMessage(), step.getExecutionRes());
			step.addReflectionRecord(defaultResult);
			return true;
		}
	}

	/**
	 * 拼装发给 reflectionAgent 的评估 prompt，包含任务类型、标题、描述和执行结果。
	 */
	private String buildEvaluationPrompt(Plan.Step step, String nodeType) {
		String taskTypeDescription = switch (nodeType) {
			case "researcher" -> "research task";
			case "coder" -> "coding task";
			default -> "task";
		};

		return String.format("""
				Please evaluate the completion quality of the following %s:

				**Task Title:** %s

				**Task Description:** %s

				**Completion Result:**
				%s
				""", taskTypeDescription, step.getTitle(), step.getDescription(), step.getExecutionRes());
	}

	/**
	 * 从 reflectionHistory 列表长度读取已反思次数。
	 * 兼容旧版本通过 executionStatus 字符串存储次数的方式。
	 */
	private int getReflectionAttemptCount(Plan.Step step) {
		if (step.getReflectionHistory() != null) {
			return step.getReflectionHistory().size();
		}

		// 兼容旧版：从状态字符串 "_attempt_N" 解析次数
		String status = step.getExecutionStatus();
		if (status != null && status.contains("_attempt_")) {
			try {
				String[] parts = status.split("_attempt_");
				if (parts.length > 1) {
					return Integer.parseInt(parts[1].split("_")[0]);
				}
			}
			catch (NumberFormatException e) {
				logger.debug("Failed to parse reflection attempt count: {}", status);
			}
		}
		return 0;
	}

	/**
	 * 在状态字符串中递增重试计数（仅在 reflectionHistory 为 null 时才会用到）。
	 */
	private void incrementReflectionAttemptCount(Plan.Step step) {
		int currentCount = getReflectionAttemptCount(step);
		String baseStatus = step.getExecutionStatus().split("_attempt_")[0];
		step.setExecutionStatus(baseStatus + "_attempt_" + (currentCount + 1));
	}

	/**
	 * handleReflection 的返回值，告知调用方是否应继续执行节点业务逻辑。
	 * continueProcessing=true  → 节点继续正常执行
	 * continueProcessing=false → 节点跳过本次执行（反思已处理完毕）
	 */
	public static class ReflectionHandleResult {

		private final boolean shouldContinueProcessing;

		private ReflectionHandleResult(boolean shouldContinueProcessing) {
			this.shouldContinueProcessing = shouldContinueProcessing;
		}

		public static ReflectionHandleResult continueProcessing() {
			return new ReflectionHandleResult(true);
		}

		public static ReflectionHandleResult skipProcessing() {
			return new ReflectionHandleResult(false);
		}

		public boolean shouldContinueProcessing() {
			return shouldContinueProcessing;
		}

	}

}
