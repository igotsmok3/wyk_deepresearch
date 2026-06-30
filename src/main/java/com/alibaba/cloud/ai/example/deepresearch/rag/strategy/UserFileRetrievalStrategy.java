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
package com.alibaba.cloud.ai.example.deepresearch.rag.strategy;

import com.alibaba.cloud.ai.example.deepresearch.rag.SourceTypeEnum;
import com.alibaba.cloud.ai.example.deepresearch.rag.core.HybridRagProcessor;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户上传文件的检索策略，根据 session_id 隔离并检索当前会话用户上传的文档。
 *
 * <p>
 * 项目职责：RetrievalStrategy 的用户文件实现，将 source_type 固定为 "user_upload"， 要求 options 中必须携带有效的
 * session_id，委托 HybridRagProcessor 执行完整检索流程。
 *
 * <p>
 * 被使用情况：由 RagNodeService 注入并用于构建用户文件 RagNode； 策略名称为 "userFile"，无 session_id
 * 时直接返回空列表保护隐私边界。
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class UserFileRetrievalStrategy implements RetrievalStrategy {

	private final HybridRagProcessor hybridRagProcessor;

	public UserFileRetrievalStrategy(HybridRagProcessor hybridRagProcessor) {
		this.hybridRagProcessor = hybridRagProcessor;
	}

	@Override
	public String getStrategyName() {
		return "userFile";
	}

	@Override
	public List<Document> retrieve(String query, Map<String, Object> options) {
		String sessionId = (String) options.get("session_id");
		if (sessionId == null || sessionId.isBlank()) {
			// 如果没有 session_id，此策略不应返回任何内容
			return List.of();
		}

		// 构建用户文件检索的上下文选项，与VectorStoreDataIngestionService的元数据逻辑一致
		Map<String, Object> ragOptions = new HashMap<>(options);
		ragOptions.put("source_type", SourceTypeEnum.USER_UPLOAD.getValue());
		ragOptions.put("session_id", sessionId);

		// 使用统一的RAG处理器执行完整的处理流程
		Query ragQuery = new Query(query);
		return hybridRagProcessor.process(ragQuery, ragOptions);
	}

}
