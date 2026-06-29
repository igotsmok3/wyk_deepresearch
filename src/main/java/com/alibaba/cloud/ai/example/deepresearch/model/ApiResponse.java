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

package com.alibaba.cloud.ai.example.deepresearch.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 通用 API 响应封装，统一包装 HTTP 接口的返回结构。
 *
 * <p>项目职责：作为各 REST 接口的标准响应体，携带状态码、状态文本、消息和泛型数据字段。
 *
 * <p>被使用情况：{@code ChatController} 用于停止图任务的响应；{@code RagDataController} 用于文件上传接口响应；
 * {@code ShortUserRoleMemoryController} 用于短期记忆查询、删除接口响应。
 */
public record ApiResponse<T>(

		@JsonProperty("code") Integer code,

		@JsonProperty("status") String status,

		@JsonProperty("message") String message,

		@JsonProperty("data") T data) {

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(200, "success", "", data);
	}

	public static <T> ApiResponse<T> error(String message) {
		return new ApiResponse<>(500, "error", message, null);
	}

	public static <T> ApiResponse<T> error(String message, T data) {
		return new ApiResponse<>(500, "error", message, data);
	}
}
