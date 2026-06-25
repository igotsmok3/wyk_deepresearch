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

package com.alibaba.cloud.ai.example.deepresearch.util;

import com.alibaba.cloud.ai.example.deepresearch.model.dto.memory.ShortUserRoleExtractResult;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author yingzi
 * @since 2025/5/17 17:20
 */

public class TemplateUtil {

	public static Message getMessage(String promptName) throws IOException {
		// 读取 resources/prompts 下的 md 文件
		ClassPathResource resource = new ClassPathResource("prompts/" + promptName + ".md");
		String template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);

		// 替换 {{ CURRENT_TIME }} 占位符
		String systemPrompt = template.replace("{{ CURRENT_TIME }}", LocalDateTime.now().toString());
		SystemMessage systemMessage = new SystemMessage(systemPrompt);
		return systemMessage;
	}

	public static Message getMessage(String promptName, OverAllState state) throws IOException {
		// 读取 resources/prompts 下的 md 文件
		ClassPathResource resource = new ClassPathResource("prompts/" + promptName + ".md");
		String template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		// 替换 {{ CURRENT_TIME }} 占位符
		String systemPrompt = template.replace("{{ CURRENT_TIME }}", LocalDateTime.now().toString());
		// 替换 {{ max_step_num }} 占位符
		systemPrompt = systemPrompt.replace("{{ max_step_num }}", StateUtil.getMaxStepNum(state).toString());

		SystemMessage systemMessage = new SystemMessage(systemPrompt);
		return systemMessage;
	}

	public static Message getShortMemoryExtractMessage(String query, String historyUserMessages) throws IOException {
		// 读取 短期记忆抽取 md 文件
		ClassPathResource resource = new ClassPathResource("prompts/memory/short/shortmemory-extract.md");
		String template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		// 替换 {{ last_user_message }} 占位符
		String systemPrompt = template.replace("{{ last_user_message }}", query);
		// 替换 {{ history_user_messages }} 占位符
		systemPrompt = systemPrompt.replace("{{ history_user_messages }}", historyUserMessages);
		// 替换 {{ locale }} 占位符
		systemPrompt = systemPrompt.replace("{{ locale }}", "zh-CN");
		return new SystemMessage(systemPrompt);
	}

	public static Message getShortMemoryUpdateMessage(ShortUserRoleExtractResult currentExtractResult,
			ShortUserRoleExtractResult previousExtractResult, List<ShortUserRoleExtractResult> historyExtractTracks,
			Double updateSimilarityThreshold) throws IOException {
		// 读取 短期记忆更新 md 文件
		ClassPathResource resource = new ClassPathResource("prompts/memory/short/shortmemory-update.md");
		String template = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
		// 替换 {{ current_extract_result }} 占位符
		String systemPrompt = template.replace("{{ current_extract_result }}", JsonUtil.toJson(currentExtractResult));
		// 替换 {{ previous_extract_results }} 占位符
		systemPrompt = systemPrompt.replace("{{ previous_extract_results }}", JsonUtil.toJson(previousExtractResult));
		// 替换 {{ history_tracks }} 占位符
		systemPrompt = systemPrompt.replace("{{ history_extract_track }}", JsonUtil.toJson(historyExtractTracks));
		// 替换 {{ update_similarity_threshold }} 占位符
		systemPrompt = systemPrompt.replace("{{ update_similarity_threshold }}", updateSimilarityThreshold.toString());
		return new SystemMessage(systemPrompt);
	}

	public static Message getOptQueryMessage(OverAllState state) throws IOException {
		List<String> queries = StateUtil.getOptimizeQueries(state);
		assert queries != null && !queries.isEmpty();
		String originalQuery = StateUtil.getQuery(state);

		List<String> results = queries.stream()
			.map(optimizeQuery -> "original query:" + originalQuery + "optimize query:" + optimizeQuery + "\n")
			.collect(Collectors.toList());

		Message userMessage = new UserMessage(String.valueOf(results));
		return userMessage;
	}

	public static void addShortUserRoleMemory(List<Message> messages, OverAllState state) {
		String shortUserRoleMemory = state.value("short_user_role_memory", "");
		if (StringUtils.hasText(shortUserRoleMemory)) {
			ShortUserRoleExtractResult shortUserRoleExtractResult = JsonUtil.fromJson(shortUserRoleMemory,
					ShortUserRoleExtractResult.class);
			if (shortUserRoleExtractResult != null) {
				messages.add(new SystemMessage(
						"You are having a conversation with " + shortUserRoleExtractResult.getUserOverview()));
			}
		}
	}

}
