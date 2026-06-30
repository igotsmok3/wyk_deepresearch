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
 * 可观测性功能的配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.observation.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，提供可观测性功能的启用开关（{@code enabled}）， 控制 {@code ObservationConfiguration}
 * 中工具调用日志监控是否激活。
 *
 * <p>
 * 被使用情况：被 {@code ObservationConfiguration} 通过 {@code @EnableConfigurationProperties}
 * 激活并注入， 用于条件判断是否注册 {@code ObservationHandler} 和 {@code ObservationRegistry} Bean。
 *
 * @author Allen Hu
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = ObservationProperties.PREFIX)
public class ObservationProperties {

	public static final String PREFIX = DeepResearchProperties.PREFIX + ".observation";

	private boolean enabled;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

}
