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

package com.alibaba.cloud.ai.example.deepresearch.rag.kb.model;

import java.util.Map;

/**
 * 专业知识库搜索结果的不可变数据模型，统一封装来自不同知识库 API 的检索条目。
 *
 * <p>项目职责：知识库层统一数据传输对象（DTO），由 CustomKbApiClient 和 DashScopeKbApiClient
 * 的 search() 方法返回，再由 ProfessionalKbApiStrategy 将其转换为 Spring AI Document 格式。
 *
 * <p>被使用情况：ProfessionalKbApiClient 接口的 search() 返回值类型；
 * ProfessionalKbApiStrategy 负责将 KbSearchResult 列表转换为 RAG 管道所需的 Document 列表。
 *
 * @author hupei
 */
public record KbSearchResult(String id, String title, String content, String url, Double score,
		Map<String, Object> metadata) {
	// 无参构造函数对应的静态工厂方法
	public static KbSearchResult empty() {
		return new KbSearchResult(null, null, null, null, null, null);
	}

	// 两个参数构造函数对应的静态工厂方法
	public static KbSearchResult of(String title, String content) {
		return new KbSearchResult(null, title, content, null, null, null);
	}

	// 五个参数构造函数（id, title, content, url, score）
	public static KbSearchResult of(String id, String title, String content, String url, Double score) {
		return new KbSearchResult(id, title, content, url, score, null);
	}
}
