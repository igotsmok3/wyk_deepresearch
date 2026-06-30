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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 报告导出操作的结果数据传输对象，封装导出文件的路径、格式及错误信息。
 *
 * <p>
 * 项目职责：作为报告导出接口的响应数据，包含导出是否成功、文件路径、下载链接等信息。
 *
 * <p>
 * 被使用情况：{@code ReportController} 在导出报告时将其包装进 {@code ReportResponse} 返回给前端。
 */
public record ExportData(
		/**
		 * 操作是否成功
		 */
		@JsonProperty("success") boolean success,

		/**
		 * 导出格式
		 */
		@JsonProperty("format") String format,

		/**
		 * 导出文件路径
		 */
		@JsonProperty("file_path") String filePath,

		/**
		 * 下载URL
		 */
		@JsonProperty("download_url") String downloadUrl,

		/**
		 * 错误信息，仅当success为false时有值
		 */
		@JsonProperty("error") String error) {
	public static ExportData success(String format, String filePath, String downloadUrl) {
		return new ExportData(true, format, filePath, downloadUrl, null);
	}

	public static ExportData error(String errorMessage) {
		return new ExportData(false, null, null, null, errorMessage);
	}
}
