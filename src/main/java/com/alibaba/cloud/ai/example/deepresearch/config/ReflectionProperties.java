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

package com.alibaba.cloud.ai.example.deepresearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Reflection 质量评估机制的配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.reflection.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，控制 ResearcherNode 和 CoderNode 执行结果的自我反思评估功能，
 * 提供功能开关（{@code enabled}）和最大重试次数（{@code maxAttempts}）两个核心参数， 防止评估过程因质量不达标而陷入无限循环。
 *
 * <p>
 * 被使用情况：被 {@code DeepResearchConfiguration#reflectionProcessor()} 读取以决定是否创建
 * {@code ReflectionProcessor} Bean；{@code DeepResearchConfiguration} 通过
 * {@code @EnableConfigurationProperties} 激活本类。
 *
 * @author sixiyida
 * @since 2025/7/10
 */
@ConfigurationProperties(prefix = ReflectionProperties.PREFIX)
public class ReflectionProperties {

	public static final String PREFIX = DeepResearchProperties.PREFIX + ".reflection";

	/**
	 * 是否启用 Reflection 质量评估机制。 false 时 ResearcherNode / CoderNode 完成后直接标记
	 * completed，跳过评估循环。
	 */
	private boolean enabled = true;

	/**
	 * 单个 Step 最多允许反思重试的次数。 超出后强制标记通过，防止无限循环。
	 */
	private int maxAttempts = 2;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

}
