package com.alibaba.cloud.ai.example.deepresearch.rag.transformer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.util.PromptAssert;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * HyDE（Hypothetical Document Embeddings）查询转换器，用 LLM 生成假设性文档替换原始查询以提升向量检索精度。
 *
 * <p>项目职责：RAG 查询前处理阶段的增强组件，实现 Spring AI 的 QueryTransformer 接口；
 * 核心思路是让 LLM 针对用户问题生成一段假设性答案，其向量与真实文档更接近，从而提高召回率。
 * 生成失败时降级返回原始查询，保证管道不中断。
 *
 * <p>被使用情况：在 DefaultHybridRagProcessor 构造时，若 hypotheticalDocumentEmbeddingEnabled=true
 * 则通过 HyDeTransformer.builder() 创建实例，在 preProcess 阶段对每条查询执行 HyDE 转换。
 *
 * @author benym
 */
public class HyDeTransformer implements QueryTransformer {

	private static final Logger logger = LoggerFactory.getLogger(HyDeTransformer.class);

	private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
			Given a user question, write a comprehensive and informative passage that directly answers the question.

			The passage should be factual, well-structured, and contain specific details.

			Question: {query}

			Passage:
			""");

	private final ChatClient chatClient;

	private final PromptTemplate promptTemplate;

	public HyDeTransformer(ChatClient.Builder chatClientBuilder, PromptTemplate promptTemplate) {
		Assert.notNull(chatClientBuilder, "chatClientBuilder cannot be null");
		this.chatClient = chatClientBuilder.build();
		this.promptTemplate = promptTemplate != null ? promptTemplate : DEFAULT_PROMPT_TEMPLATE;
		PromptAssert.templateHasRequiredPlaceholders(this.promptTemplate, "query");
	}

	@Override
	public Query transform(Query query) {
		Assert.notNull(query, "query cannot be null");
		// 让 LLM 生成假设性文档，用于替换原始查询文本进行向量检索
		var hyDeQueryText = this.chatClient.prompt()
			.user(user -> user.text(this.promptTemplate.getTemplate()).param("query", query.text()))
			.call()
			.content();
		if (!StringUtils.hasText(hyDeQueryText)) {
			// 生成失败时降级返回原始查询，保证管道不断流
			logger.warn("Query generate hyDe document result is null/empty. Returning the input query unchanged.");
			return query;
		}
		// query.mutate() 不可变模式：只替换 text，保留 history 等其他字段
		return query.mutate().text(hyDeQueryText).build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private ChatClient.Builder chatClientBuilder;

		@Nullable
		private PromptTemplate promptTemplate;

		private Builder() {
		}

		public Builder chatClientBuilder(ChatClient.Builder chatClientBuilder) {
			this.chatClientBuilder = chatClientBuilder;
			return this;
		}

		public Builder promptTemplate(PromptTemplate promptTemplate) {
			this.promptTemplate = promptTemplate;
			return this;
		}

		public HyDeTransformer build() {
			return new HyDeTransformer(this.chatClientBuilder, this.promptTemplate);
		}

	}

}
