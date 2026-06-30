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

package com.alibaba.cloud.ai.example.deepresearch.node;

import com.alibaba.cloud.ai.example.deepresearch.rag.core.HybridRagProcessor;
import com.alibaba.cloud.ai.example.deepresearch.rag.strategy.FusionStrategy;
import com.alibaba.cloud.ai.example.deepresearch.rag.strategy.RetrievalStrategy;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.FluxConverter;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 图节点：通过 RAG 管道检索相关文档，再将文档内容作为上下文喂给 LLM 流式生成答案。
 *
 * <p>
 * 项目职责：支持两种工作模式（向后兼容）。推荐模式使用 {@code HybridRagProcessor} 走完整的查询处理→混合检索→后处理管道；兼容模式使用多个
 * {@code RetrievalStrategy} 各自检索、 {@code FusionStrategy} 融合结果。从 OverAllState 读取
 * {@code query}、{@code session_id}、 {@code user_id}，检索完成后将文档内容拼接为上下文，流式调用 ragAgent 生成答案。
 * 写入 OverAllState：{@code rag_content}（携带流式 Flux 的 GraphResponse）。
 *
 * <p>
 * 被使用情况：由 {@code RagNodeService} 创建两个实例并通过 {@code DeepResearchConfiguration} 注册：
 * {@code user_file_rag}（检索用户上传文件）和 {@code professional_kb_rag}（检索专业知识库）。
 * {@code RewriteAndMultiQueryNode} 在用户上传文件时路由到 user_file_rag；
 * {@code ProfessionalKbDispatcher} 在决策结果为 true 时路由到 professional_kb_rag。
 *
 * @author hupei
 */
public class RagNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(RagNode.class);

	private final ChatClient ragAgent;

	private final List<RetrievalStrategy> retrievalStrategies;

	private final FusionStrategy fusionStrategy;

	private final HybridRagProcessor hybridRagProcessor;

	/**
	 * 支持传统的策略模式构造函数（向后兼容）
	 */
	public RagNode(List<RetrievalStrategy> retrievalStrategies, FusionStrategy fusionStrategy, ChatClient ragAgent) {
		this.retrievalStrategies = retrievalStrategies;
		this.fusionStrategy = fusionStrategy;
		this.ragAgent = ragAgent;
		this.hybridRagProcessor = null;
	}

	/**
	 * 新的统一RAG处理器构造函数
	 */
	public RagNode(HybridRagProcessor hybridRagProcessor, ChatClient ragAgent) {
		this.hybridRagProcessor = hybridRagProcessor;
		this.ragAgent = ragAgent;
		this.retrievalStrategies = null;
		this.fusionStrategy = null;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("rag_node is running.");
		String queryText = StateUtil.getQuery(state);

		// session_id 和 user_id 传入 options，用于 buildFilterExpression 进行数据隔离
		Map<String, Object> options = new HashMap<>();
		state.value("session_id", String.class).ifPresent(v -> options.put("session_id", v));
		state.value("user_id", String.class).ifPresent(v -> options.put("user_id", v));
		options.put("query", queryText); // RRF 后处理需要原始查询文本

		List<Document> documents = new ArrayList<>();

		if (hybridRagProcessor != null) {
			// 走完整 RAG 管道：查询处理 → 混合检索 → 后处理
			Query query = new Query(queryText);
			documents = hybridRagProcessor.process(query, options);
		}
		else if (retrievalStrategies != null && fusionStrategy != null) {
			// 兼容旧版策略模式：各策略独立检索，最后 FusionStrategy 融合
			List<List<Document>> allResults = new ArrayList<>();
			for (RetrievalStrategy strategy : retrievalStrategies) {
				allResults.add(strategy.retrieve(queryText, options));
			}
			documents = fusionStrategy.fuse(allResults);
		}

		// 将检索到的文档内容拼接为 LLM 的上下文（RAG 的 "Augmentation" 阶段）
		StringBuilder contextBuilder = new StringBuilder();
		for (Document doc : documents) {
			contextBuilder.append(doc.getText()).append("\n");
		}

		// 将上下文 + 用户原始问题发给 LLM 生成最终答案（流式输出）
		Flux<ChatResponse> streamResult = ragAgent.prompt()
			.messages(new UserMessage(contextBuilder.toString()))
			.user(queryText)
			.stream()
			.chatResponse()
			.timeout(Duration.ofSeconds(180))
			.retry(2);

		logger.info("RAG node produced a result.");

		Flux<GraphResponse<StreamingOutput>> generatedContent = FluxConverter.builder()
			.startingNode("rag_llm_stream")
			.startingState(state)
			.mapResult(response -> Map.of("rag_content",
					Objects.requireNonNull(response.getResult().getOutput().getText())))
			.build(streamResult);

		logger.info("RAG node produced a streaming result.");

		Map<String, Object> updated = new HashMap<>();
		updated.put("rag_content", generatedContent);

		return updated;
	}

}
