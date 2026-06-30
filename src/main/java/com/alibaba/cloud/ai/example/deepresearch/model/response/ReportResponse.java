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

package com.alibaba.cloud.ai.example.deepresearch.model.response;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 报告相关接口的通用响应体，封装线程 ID、状态文本、消息及泛型报告数据。
 *
 * <p>
 * 项目职责：作为 {@code ReportController} 所有接口的标准响应结构， 支持成功、未找到、错误三种状态的静态工厂方法，便于统一前端响应格式。
 *
 * <p>
 * 被使用情况：{@code ReportController} 在获取报告、检查是否存在、删除报告、导出报告 等接口中构建并返回该对象。
 *
 * @author huangzhen
 * @since 2025/6/20
 */
public record ReportResponse<T>(

		/**
		 * 线程ID，用于标识当前对话的唯一性
		 */
		@JsonProperty("thread_id") String threadId,

		/**
		 * 状态
		 */
		@JsonProperty("status") String status,

		/**
		 * 消息
		 */
		@JsonProperty("message") String message,

		/**
		 * 数据
		 */
		@JsonProperty("report_information") T data) {
	public static <T> ReportResponse<T> success(String threadId, String message, T data) {
		return new ReportResponse(threadId, "success", message, data);
	}

	public static <T> ReportResponse<T> notfound(String threadId, String message) {
		return new ReportResponse(threadId, "notfound", message, null);
	}

	public static ReportResponse error(String threadId, String message) {
		return new ReportResponse(threadId, "error", message, null);
	}
}
