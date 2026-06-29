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

import java.util.List;

/**
 * 模型参数仓储接口，定义从配置文件加载 Agent 模型参数列表的契约。
 *
 * <p>项目职责：抽象 model-config.json 中 Agent 与模型名称映射关系的加载逻辑，
 * 供 {@code AgentModelsConfiguration} 在启动时按需读取配置，动态创建各 Agent 的 ChatModel Bean。
 *
 * <p>被使用情况：{@code AgentModelsConfiguration} 通过构造器注入本接口，
 * 调用 {@code loadModels} 获取 AgentModel 列表并映射为 ChatModel Bean。
 */
public interface ModelParamRepository {

	/**
	 * Load model configuration list
	 */
	List<ModelParamRepositoryImpl.AgentModel> loadModels();

}
