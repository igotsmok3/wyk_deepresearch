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

package com.alibaba.cloud.ai.example.deepresearch.config.export;

import com.alibaba.cloud.ai.example.deepresearch.service.ExportService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 导出服务配置类，负责创建并注册 {@code ExportService} Bean。
 *
 * <p>项目职责：属于配置层，读取 {@link ExportProperties} 中的导出路径配置，
 * 构造 {@code ExportService} 实例并注入到 Spring 容器，为报告导出功能提供服务支撑。
 *
 * <p>被使用情况：由 Spring 容器直接管理；产出的 {@code ExportService} Bean 被
 * {@code ReportController} 注入以处理报告导出请求。
 *
 * @author sixiyida
 * @since 2025/6/20
 */
@Configuration
@EnableConfigurationProperties(ExportProperties.class)
public class ExportConfiguration {

	@Bean
	public ExportService exportService(ExportProperties exportProperties) {
		return new ExportService(exportProperties.getPath());
	}

}
