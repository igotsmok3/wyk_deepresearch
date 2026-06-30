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
package com.alibaba.cloud.ai.example.deepresearch.serializer;

import com.alibaba.cloud.ai.example.deepresearch.model.dto.Plan;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.toolcalling.searches.SearchEnum;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code OverAllState} 的自定义 JSON 反序列化器，负责将持久化的图状态 JSON 正确还原为领域对象。
 *
 * <p>
 * 项目职责：在 {@code DeepResearchStateSerializer} 的反序列化阶段处理类型擦除问题， 将 JSON 中的
 * {@code current_plan} 节点还原为 {@code Plan} 对象， 将 {@code search_engine} 节点还原为
 * {@code SearchEnum} 枚举，其余字段原样保留。
 *
 * <p>
 * 被使用情况：由 {@code DeepResearchStateSerializer} 在构造时注册到 Jackson {@code SimpleModule}，
 * 在图状态从检查点存储恢复时自动触发。
 */
public class DeepResearchDeserializer extends JsonDeserializer<OverAllState> {

	private final ObjectMapper objectMapper;

	public DeepResearchDeserializer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public OverAllState deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		ObjectNode node = objectMapper.readTree(p);

		Map<String, Object> data = objectMapper.convertValue(node.get("data"), new TypeReference<>() {
		});
		Map<String, Object> newData = new HashMap<>();

		// 处理Plan
		Plan currentPlan = objectMapper.convertValue(data.get("current_plan"), Plan.class);
		newData.put("current_plan", currentPlan);

		// 处理search_engine
		SearchEnum searchEnum = objectMapper.convertValue(data.get("search_engine"), SearchEnum.class);
		newData.put("search_engine", searchEnum);

		// 处理其他数据
		data.forEach((key, value) -> {
			if (!newData.containsKey(key)) {
				newData.put(key, value);
			}
		});

		return new OverAllState(newData);
	}

}
