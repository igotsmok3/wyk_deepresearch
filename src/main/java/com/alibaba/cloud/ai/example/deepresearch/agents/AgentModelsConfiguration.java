/*
 * Copyright 2024-2025 the original author or authors.
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

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeConnectionProperties;
import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.example.deepresearch.repository.ModelParamRepository;
import com.alibaba.cloud.ai.example.deepresearch.repository.ModelParamRepositoryImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * Agent 模型配置类，根据 {@code model-config.json} 文件为各 Agent 创建对应的 {@link ChatClient.Builder} Bean。
 *
 * <p>项目职责：属于配置层，负责在应用启动时将 model-config.json 中定义的模型参数转换为
 * {@link DashScopeChatModel} 实例，并以 {@code <agentName>ChatClientBuilder} 为名称注册到 Spring 容器，
 * 供各 Agent Bean（如 researchAgent、coderAgent 等）在 {@link AgentsConfiguration} 中按名称注入。
 *
 * <p>被使用情况：由 Spring 容器直接管理，其注册的动态 Builder Bean 被 {@code AgentsConfiguration}
 * 中各 {@code @Bean} 方法以参数形式注入使用。
 *
 * @author ViliamSun
 * @since 0.1.0
 */
@Configuration
public class AgentModelsConfiguration implements InitializingBean {

	// 注册到 Spring 容器时 Bean 名称的后缀，例如 "researcherChatClientBuilder"
	private static final String BEAN_NAME_SUFFIX = "ChatClientBuilder";

	// 从 model-config.json 读取的所有 Agent 模型配置列表
	private final List<ModelParamRepositoryImpl.AgentModel> models;

	// DashScope 连接配置（包含 API Key 等），由 Spring 自动注入
	private final DashScopeConnectionProperties commonProperties;

	// 工具调用管理器，负责将 Java 方法作为"函数工具"暴露给 AI 模型
	private final ToolCallingManager toolCallingManager;

	/**
	 * 将一个模型注册为 Spring Bean 的函数式操作：
	 * key   = Agent 名称（如 "researcher"）
	 * value = 对应的 DashScopeChatModel 实例
	 * 注册后 Bean 名称变为 "researcherChatClientBuilder"，供 AgentsConfiguration 按名注入。
	 */
	private final BiConsumer<String, DashScopeChatModel> registerConsumer;

	/**
	 * 构造方法——Spring 启动时自动调用，完成以下三件事：
	 * 1. 从 ModelParamRepository 加载 model-config.json 中定义的所有模型参数
	 * 2. 保存 DashScope API Key 等连接信息
	 * 3. 准备好"注册 Bean"的逻辑（registerConsumer），等 afterPropertiesSet 时批量执行
	 *
	 * @param modelParamRepository       模型参数仓库，负责读取 JSON 配置
	 * @param beanFactory                Spring Bean 工厂，用于动态注册 Bean
	 * @param dashScopeConnectionProperties  DashScope 连接属性（含 apiKey）
	 * @param toolCallingManager         工具调用管理器
	 */
	public AgentModelsConfiguration(ModelParamRepository modelParamRepository, ConfigurableBeanFactory beanFactory,
			DashScopeConnectionProperties dashScopeConnectionProperties, ToolCallingManager toolCallingManager) {
		this.toolCallingManager = toolCallingManager;
		Assert.notNull(modelParamRepository, "ModelParamRepository must not be null");
		this.commonProperties = dashScopeConnectionProperties;
		// 从配置文件（model-config.json）加载所有 Agent 的模型参数
		this.models = modelParamRepository.loadModels();
		// 定义注册逻辑：将 DashScopeChatModel 包装成 ChatClient.Builder 后注册到 Spring 容器
		// ChatClient.Builder 是后续各 Agent 创建聊天客户端的工厂对象
		this.registerConsumer = (key, value) -> beanFactory.registerSingleton(key.concat(BEAN_NAME_SUFFIX),
				ChatClient.create(value).mutate());
	}

	/**
	 * 将 model-config.json 中的每条 AgentModel 配置转换为 DashScopeChatModel 实例。
	 *
	 * <p>转换规则：
	 * <ul>
	 *   <li>以 AgentModel 的 name 字段作为 Map 的 key（如 "researcher"、"coder"）</li>
	 *   <li>用 AgentModel 的 modelName 字段指定底层大模型（如 "qwen-max"）</li>
	 *   <li>若配置文件中存在同名重复项，保留第一条，忽略后续重复项</li>
	 * </ul>
	 *
	 * @return key=Agent名称, value=对应的 DashScopeChatModel 实例
	 */
	private Map<String, DashScopeChatModel> agentModels() {
		return models.stream()
			.filter(Objects::nonNull) // 过滤掉 JSON 中可能存在的 null 条目
			.collect(Collectors.toMap(
					ModelParamRepositoryImpl.AgentModel::name, // key：Agent 名称
					model -> DashScopeChatModel.builder()
						// 使用公共 API Key 创建 DashScope API 客户端
						.dashScopeApi(DashScopeApi.builder().apiKey(commonProperties.getApiKey()).build())
						// 注入工具调用管理器，使该模型支持 FunctionTool 调用
						.toolCallingManager(toolCallingManager)
						// 设置默认推理参数：指定模型名称和温度值
						.defaultOptions(DashScopeChatOptions.builder()
							.withModel(model.modelName())                        // 具体模型，如 "qwen-max"
							.withTemperature(DashScopeChatModel.DEFAULT_TEMPERATURE) // 默认温度（控制随机性）
							.build())
						.build(),
					(existing, replacement) -> existing) // 遇到重复 key 时保留已有值
			);
	}

	/**
	 * Spring 容器初始化完成后自动回调此方法（来自 InitializingBean 接口）。
	 * 遍历所有 Agent 模型，逐一调用 registerConsumer 将其注册为 Spring Bean，
	 * 注册后各 Agent 可通过名称（如 "researcherChatClientBuilder"）从容器中获取对应的 ChatClient.Builder。
	 */
	@Override
	public void afterPropertiesSet() throws Exception {
		this.agentModels().forEach(registerConsumer);
	}

}
