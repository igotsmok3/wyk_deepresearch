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

package com.alibaba.cloud.ai.example.deepresearch.config.rag;

import com.alibaba.cloud.ai.example.deepresearch.rag.splitter.MarkdownStructureSplitter;
import com.alibaba.cloud.ai.example.deepresearch.rag.splitter.PdfStructureSplitter;
import com.alibaba.cloud.ai.example.deepresearch.service.DocumentSplitterRouter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 切分器 Bean 注册配置，仅在 RAG 启用时生效。
 */
@Configuration
@ConditionalOnProperty(prefix = RagProperties.RAG_PREFIX, name = "enabled", havingValue = "true")
public class RagSplitterConfiguration {

	@Bean
	public TokenTextSplitter tokenTextSplitter(RagProperties ragProperties) {
		RagProperties.TextSplitter cfg = ragProperties.getTextSplitter();
		return new TokenTextSplitter(cfg.getDefaultChunkSize(), cfg.getOverlap(), cfg.getMinChunkSizeToSplit(),
				cfg.getMaxChunkSize(), cfg.isKeepSeparator());
	}

	@Bean
	public MarkdownStructureSplitter markdownStructureSplitter(RagProperties ragProperties,
			TokenTextSplitter tokenTextSplitter) {
		return new MarkdownStructureSplitter(ragProperties, tokenTextSplitter);
	}

	@Bean
	public PdfStructureSplitter pdfStructureSplitter(RagProperties ragProperties, TokenTextSplitter tokenTextSplitter) {
		return new PdfStructureSplitter(ragProperties, tokenTextSplitter);
	}

	@Bean
	public DocumentSplitterRouter documentSplitterRouter(MarkdownStructureSplitter markdownStructureSplitter,
			PdfStructureSplitter pdfStructureSplitter, TokenTextSplitter tokenTextSplitter,
			RagProperties ragProperties) {
		return new DocumentSplitterRouter(markdownStructureSplitter, pdfStructureSplitter, tokenTextSplitter,
				ragProperties);
	}

}
