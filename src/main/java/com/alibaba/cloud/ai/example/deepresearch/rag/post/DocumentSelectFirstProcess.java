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

package com.alibaba.cloud.ai.example.deepresearch.rag.post;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import java.util.Collections;
import java.util.List;

/**
 * 简单的文档后处理器，从检索结果中只保留排名第一的文档，用于节省 LLM 上下文窗口。
 *
 * <p>
 * 项目职责：RAG 后处理阶段的截断策略，实现 Spring AI 的 DocumentPostProcessor 接口， 当
 * postProcessingSelectFirstEnabled=true 时由 DefaultHybridRagProcessor 使用。
 *
 * <p>
 * 被使用情况：在 DefaultHybridRagProcessor 的构造器中按条件实例化， 作为 postProcess 阶段的最终截断处理器。
 *
 * @author yingzi
 */
public class DocumentSelectFirstProcess implements DocumentPostProcessor {

	@Override
	public List<Document> process(Query query, List<Document> documents) {
		if (documents == null || documents.isEmpty()) {
			return Collections.emptyList();
		}
		return Collections.singletonList(documents.get(0));
	}

}
