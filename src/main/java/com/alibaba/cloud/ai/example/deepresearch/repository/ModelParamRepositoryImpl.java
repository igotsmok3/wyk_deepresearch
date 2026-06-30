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

package com.alibaba.cloud.ai.example.deepresearch.repository;

import com.alibaba.cloud.ai.example.deepresearch.util.ResourceUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * {@link ModelParamRepository} 的默认实现，在启动时从 classpath 的 {@code model-config.json} 加载 Agent
 * 模型配置。
 *
 * <p>
 * 项目职责：读取 JSON 文件中 {@code models} 数组，将每个条目映射为 {@code AgentModel} Record（name +
 * modelName）， 供 {@code AgentModelsConfiguration} 在 Spring 容器初始化阶段动态注册各 Agent 对应的
 * ChatModel Bean。
 *
 * <p>
 * 被使用情况：{@code AgentModelsConfiguration} 通过 {@code ModelParamRepository} 接口注入本类， 调用
 * {@code loadModels()} 获取模型列表后按名称注册 ChatModel Bean 到 BeanFactory。
 *
 * @author ViliamSun
 * @since 0.1.0
 */
@Repository
public class ModelParamRepositoryImpl implements ModelParamRepository {

	// JSON key in configuration file
	private static final String MODELS_ORER_AGENT = "models";

	private final Map<String, List<AgentModel>> modelSet;

	public ModelParamRepositoryImpl(@Value("classpath:model-config.json") Resource agentsConfig,
			ObjectMapper objectMapper) {
		try {
			this.modelSet = objectMapper.readValue(ResourceUtil.loadResourceAsString(agentsConfig),
					new TypeReference<Map<String, List<AgentModel>>>() {
					});

		}
		catch (JsonProcessingException e) {
			throw new RuntimeException("Error in parsing model configuration", e);
		}
	}

	/**
	 * Get the list of agent models.
	 * @return a list of AgentModel parameters.
	 */
	@Override
	public List<AgentModel> loadModels() {
		return modelSet.getOrDefault(MODELS_ORER_AGENT, List.of());
	}

	// fixme: To read external data in the future, this object needs to be redesigned
	public record AgentModel(String name, String modelName) {
	}

}
