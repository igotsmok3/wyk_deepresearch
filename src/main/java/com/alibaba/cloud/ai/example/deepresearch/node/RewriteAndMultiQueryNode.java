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

import com.alibaba.cloud.ai.example.deepresearch.config.ShortTermMemoryProperties;
import com.alibaba.cloud.ai.example.deepresearch.memory.ShortTermMemoryRepository;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 查询优化节点：对用户原始提问执行三步优化，将结果存入 OverAllState 供后续检索节点使用。
 *
 * <p>项目职责：位于 coordinator（触发深度研究）之后，background_investigator / user_file_rag 之前。
 * 依次执行：
 * <ol>
 *   <li>{@code CompressionQueryTransformer}（可选）：消解多轮对话中的代词引用，将相对表达转为完整问题</li>
 *   <li>{@code RewriteQueryTransformer}：语义重写，去除口语化噪音，提升精准度</li>
 *   <li>{@code MultiQueryExpander}：扩展为 N 条语义变体（含原始查询），并行检索后提升召回率</li>
 * </ol>
 * 写入 OverAllState：
 * <ul>
 *   <li>{@code optimize_queries}：优化后的多条查询字符串列表</li>
 *   <li>{@code rewrite_multi_query_next_node}：路由键，用户上传文件时为 user_file_rag，否则为 background_investigator</li>
 * </ul>
 *
 * <p>被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code rewrite_multi_query} 注册到图中；
 * {@code RewriteAndMultiQueryDispatcher} 读取 {@code rewrite_multi_query_next_node} 进行边路由；
 * {@code CoordinatorNode} 触发工具调用时路由到本节点。
 *
 * @author yingzi
 * @since 2025/5/18 16:54
 */
public class RewriteAndMultiQueryNode implements NodeAction {

	private static final Integer MaxOptimizeQueryNum = 5;

	private static final Integer MinOptimizeQueryNum = 0;

	private static final Logger logger = LoggerFactory.getLogger(RewriteAndMultiQueryNode.class);

	private final QueryTransformer queryTransformer;

	private final ShortTermMemoryRepository shortTermMemoryRepository;

	private final ShortTermMemoryProperties shortTermMemoryProperties;

	ChatClient.Builder rewriteAndMultiQueryAgentBuilder;

	public RewriteAndMultiQueryNode(ChatClient.Builder rewriteAndMultiQueryAgentBuilder,
			ShortTermMemoryRepository shortTermMemoryRepository, ShortTermMemoryProperties shortTermMemoryProperties) {
		this.rewriteAndMultiQueryAgentBuilder = rewriteAndMultiQueryAgentBuilder;
		// 查询重写
		this.queryTransformer = RewriteQueryTransformer.builder()
			.chatClientBuilder(rewriteAndMultiQueryAgentBuilder)
			.build();
		this.shortTermMemoryRepository = shortTermMemoryRepository;
		this.shortTermMemoryProperties = shortTermMemoryProperties;
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		logger.info("rewrite_multiquery node is running.");
		Map<String, Object> updated = new HashMap<>();
		String nextStep = END;

		String queryText = StateUtil.getQuery(state);
		assert queryText != null;
		Query query = Query.builder().text(queryText).build();

		// 步骤1：若短期记忆启用且存在历史消息，先用 CompressionQueryTransformer 消解多轮引用。
		// 例如："上面提到的那个框架怎么配置？" → "Spring AI Alibaba Graph 如何配置？"
		// 这一步必须在 RewriteQueryTransformer 之前，否则代词引用无法解析。
		if (shortTermMemoryProperties.isEnabled()) {
			List<Message> recentUserMessages = shortTermMemoryRepository
				.getRecentUserMessages(StateUtil.getSessionId(state), null);
			if (!CollectionUtils.isEmpty(recentUserMessages)) {
				CompressionQueryTransformer compressionQueryTransformer = CompressionQueryTransformer.builder()
					.chatClientBuilder(rewriteAndMultiQueryAgentBuilder)
					.build();
				// 将历史消息附加到 Query.history，供 CompressionQueryTransformer 消解上下文引用
				Query queryWithHistory = Query.builder().text(queryText).history(recentUserMessages).build();
				query = compressionQueryTransformer.transform(queryWithHistory);
			}
		}

		// 步骤2：查询重写，去除口语化表达和噪音，提升语义精准度
		Query rewriteQuery = queryTransformer.transform(query);

		// 步骤3：查询扩展，生成 N 条语义相关变体（含原始查询），并行检索后合并提升召回率。
		// optimizeQueryNum 由请求参数传入，clamp 在 [0, 5] 范围内防止过多 LLM 调用。
		int optimizeQueryNum = StateUtil.getOptimizeQueryNum(state);
		optimizeQueryNum = Math.max(MinOptimizeQueryNum, Math.min(MaxOptimizeQueryNum, optimizeQueryNum));
		QueryExpander queryExpander = MultiQueryExpander.builder()
			.chatClientBuilder(rewriteAndMultiQueryAgentBuilder)
			.includeOriginal(true)      // 扩展结果包含原始（重写后的）查询，保证原意不丢失
			.numberOfQueries(optimizeQueryNum)
			.build();

		List<Query> multiQueries = queryExpander.expand(rewriteQuery);
		List<String> newQueries = multiQueries.stream().map(Query::text).collect(Collectors.toList());
		// 将优化后的多条查询写入 OverAllState，供后续 background_investigator 并行检索使用
		updated.put("optimize_queries", newQueries);

		// 路由决策：用户上传了文件 → 优先走 user_file_rag 检索用户文件；否则走常规背景调查
		if (state.value("user_upload_file", false)) {
			nextStep = "user_file_rag";
		}
		else {
			nextStep = "background_investigator";
		}
		updated.put("rewrite_multi_query_next_node", nextStep);
		return updated;
	}

}
