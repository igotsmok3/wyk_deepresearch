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

import com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报告导出功能的配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.export.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，提供报告文件的导出根路径配置，默认为 {@code ~/reports}， 可通过环境变量
 * {@code AI_DEEPRESEARCH_EXPORT_PATH} 或配置文件覆盖。
 *
 * <p>
 * 被使用情况：被 {@code ExportConfiguration} 读取以构造 {@code ExportService}； {@code ExportService}
 * 中也有文档说明直接依赖本属性提供的路径。
 *
 * @author sixiyida
 * @since 2025/6/20
 */
@ConfigurationProperties(prefix = ExportProperties.EXPORT_PREFIX)
public class ExportProperties {

	public static final String EXPORT_PREFIX = DeepResearchProperties.PREFIX + ".export";

	private String path = "${user.home}/reports";

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

}
