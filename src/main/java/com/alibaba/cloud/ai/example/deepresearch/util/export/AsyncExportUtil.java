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

package com.alibaba.cloud.ai.example.deepresearch.util.export;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * 异步导出工具类，以线程池异步方式将报告内容转换并保存为 PDF 文件。
 *
 * <p>
 * 项目职责：封装异步 PDF 生成逻辑，避免 PDF 渲染（字体加载 + openhtmltopdf 渲染）阻塞主流程； 内部依赖
 * {@link FileOperationUtil} 生成文件名，依赖 {@link FormatConversionUtil} 执行 Markdown→PDF 转换。
 *
 * <p>
 * 被使用情况：静态工具方法，按需调用；目前在 ExportService 的导出流程中可作为异步替代入口使用。
 *
 * @author sixiyida
 * @since 2025/6/20
 */
public class AsyncExportUtil {

	private static final Logger logger = LoggerFactory.getLogger(AsyncExportUtil.class);

	/**
	 * 异步将内容转换为PDF
	 * @param content 报告内容
	 * @param title 报告标题
	 * @param basePath 基础路径
	 * @return CompletableFuture包含PDF文件路径
	 */
	public static CompletableFuture<String> saveAsPdfAsync(String content, String title, String basePath,
			ThreadPoolTaskExecutor executor) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				String filename = FileOperationUtil.generateFilename(title, "pdf");
				String pdfFilePath = basePath + File.separator + filename;

				// 直接将Markdown转换为PDF
				byte[] pdfBytes = FormatConversionUtil.convertMarkdownToPdfBytes(content);

				// 保存到文件
				try (java.io.FileOutputStream fos = new java.io.FileOutputStream(pdfFilePath)) {
					fos.write(pdfBytes);
				}

				logger.info("Async PDF conversion completed: {}", pdfFilePath);
				return pdfFilePath;
			}
			catch (Exception e) {
				logger.error("Failed to convert to PDF asynchronously", e);
				throw new RuntimeException("Failed to convert to PDF asynchronously", e);
			}
		}, executor);
	}

}
