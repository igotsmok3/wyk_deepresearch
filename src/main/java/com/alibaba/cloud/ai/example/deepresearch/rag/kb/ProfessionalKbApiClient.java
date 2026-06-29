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

package com.alibaba.cloud.ai.example.deepresearch.rag.kb;

import com.alibaba.cloud.ai.example.deepresearch.rag.kb.model.KbSearchResult;

import java.util.List;
import java.util.Map;

/**
 * 专业知识库 API 客户端接口，定义查询、提供商标识及可用性检测的统一契约。
 *
 * <p>项目职责：知识库接入层的核心抽象，屏蔽不同知识库服务的 API 差异，
 * 使上层策略可以一致地调用各种知识库；实现类由 ProfessionalKbApiClientFactory 根据配置动态创建。
 *
 * <p>被使用情况：由 CustomKbApiClient（通用 REST）和 DashScopeKbApiClient（DashScope）实现；
 * ProfessionalKbApiClientFactory 负责创建实例，ProfessionalKbApiStrategy 持有并调用本接口进行检索。
 *
 * @author hupei
 */
public interface ProfessionalKbApiClient {

	/**
	 * 搜索知识库
	 * @param query 查询文本
	 * @param options 选项参数，可包含maxResults、timeout等
	 * @return 搜索结果列表
	 */
	List<KbSearchResult> search(String query, Map<String, Object> options);

	/**
	 * 获取支持的提供商类型
	 * @return 提供商类型，如"dashscope", "custom"等
	 */
	String getProvider();

	/**
	 * 检查客户端是否已正确配置
	 * @return 是否可用
	 */
	boolean isAvailable();

}
