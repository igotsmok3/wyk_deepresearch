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

package com.alibaba.cloud.ai.example.deepresearch.model.req;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 人工反馈请求体，携带用户对 Planner 生成计划的审核结果和补充意见。
 *
 * <p>
 * 项目职责：作为 {@code POST /chat/resume} 接口的请求体， 在图流程暂停于 {@code human_feedback}
 * 节点时，由前端提交用户是否接受计划及附加的反馈内容， 驱动图继续执行或重新规划。
 *
 * <p>
 * 被使用情况：{@code ChatController#resume} 接收该请求并提交给 {@code GraphProcess} 继续流式处理。
 *
 * @author yingzi
 * @since 2025/6/10
 */
public record FeedbackRequest(
		/**
		 * 会话 ID。默认值为 "__default__"，表示使用默认会话。
		 */
		@JsonProperty(value = "session_id", defaultValue = "__default__") String sessionId,

		/**
		 * 线程 ID，用于标识当前对话的唯一性。
		 */
		@JsonProperty(value = "thread_id", defaultValue = "") String threadId,

		/**
		 * 是否接受Planner的计划，true为接受，false为重新生成
		 */
		@JsonProperty(value = "feedback", defaultValue = "true") Boolean feedback,

		/**
		 * 用户反馈内容，重新生成Planner计划是给予额外的上下文信息
		 */
		@JsonProperty(value = "feedback_content", defaultValue = "") String feedbackContent) {
}
