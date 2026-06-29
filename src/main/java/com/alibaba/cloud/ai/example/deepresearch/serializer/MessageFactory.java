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

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

/**
 * {@code Message} 实例的创建工厂函数接口，供 {@link MessageDeserializer} 按消息类型路由调用。
 *
 * <p>项目职责：以函数式接口的形式封装各消息类型（USER/ASSISTANT/SYSTEM/TOOL）的构造逻辑，
 * 在 {@code MessageDeserializer} 的静态注册表中存储，实现按类型名称分发的消息创建策略。
 *
 * <p>被使用情况：由 {@code MessageDeserializer} 的静态初始化块注册，
 * 在反序列化 {@code Message} 对象时根据消息类型调用对应的 {@code create} 方法。
 *
 * @author benym
 * @since 2025/9/3 16:30
 */
@FunctionalInterface
public interface MessageFactory {

	/**
	 * create a Message instance
	 * @param textContent user text content
	 * @param metadata additional metadata
	 * @param toolCalls list of tool calls
	 * @param toolResponses list of tool responses
	 * @return Message
	 */
	Message create(String textContent, Map<String, Object> metadata, List<AssistantMessage.ToolCall> toolCalls,
			List<ToolResponseMessage.ToolResponse> toolResponses);

}
