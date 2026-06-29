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

package com.alibaba.cloud.ai.example.deepresearch.agents;

import com.alibaba.cloud.ai.example.deepresearch.config.ObservationProperties;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置类，负责注册工具调用（ToolCalling）的观测处理器和 {@link ObservationRegistry}。
 *
 * <p>项目职责：属于配置层，在 {@code spring.ai.alibaba.deepresearch.observation.enabled=true}（默认启用）时生效。
 * 注册 {@code ObservationHandler<ToolCallingObservationContext>}，在工具调用开始和结束时打印日志，
 * 并在容器中尚无 {@code observationRegistry} Bean 时创建默认实例，支持对 AI 工具调用行为进行监控与追踪。
 *
 * <p>被使用情况：由 Spring 容器直接管理；其创建的 {@code ObservationRegistry} Bean 被
 * {@code ChatController} 通过 {@code ObjectProvider} 注入使用。
 *
 * @author Allen Hu
 * @since 0.1.0
 */
@Configuration
@EnableConfigurationProperties({ ObservationProperties.class })
@ConditionalOnProperty(prefix = ObservationProperties.PREFIX, name = "enabled", havingValue = "true",
		matchIfMissing = true)
public class ObservationConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(ObservationConfiguration.class);

	@Bean
	public ObservationHandler<ToolCallingObservationContext> toolCallingObservationContextObservationHandler() {
		return new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return context instanceof ToolCallingObservationContext;
			}

			@Override
			public void onStart(ToolCallingObservationContext context) {
				ToolDefinition toolDefinition = context.getToolDefinition();
				logger.info("🔨ToolCalling start: {} - {}", toolDefinition.name(), context.getToolCallArguments());
			}

			@Override
			public void onStop(ToolCallingObservationContext context) {
				ToolDefinition toolDefinition = context.getToolDefinition();
				logger.info("✅ToolCalling done: {} - {}", toolDefinition.name(), context.getToolCallResult());
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean(name = "observationRegistry")
	public ObservationRegistry observationRegistry(
			ObjectProvider<ObservationHandler<?>> observationHandlerObjectProvider) {
		ObservationRegistry observationRegistry = ObservationRegistry.create();
		ObservationRegistry.ObservationConfig observationConfig = observationRegistry.observationConfig();
		observationHandlerObjectProvider.orderedStream().forEach(observationConfig::observationHandler);
		return observationRegistry;
	}

}
