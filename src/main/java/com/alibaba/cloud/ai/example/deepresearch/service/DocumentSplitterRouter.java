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

package com.alibaba.cloud.ai.example.deepresearch.service;

import com.alibaba.cloud.ai.example.deepresearch.config.rag.RagProperties;
import com.alibaba.cloud.ai.example.deepresearch.rag.splitter.MarkdownStructureSplitter;
import com.alibaba.cloud.ai.example.deepresearch.rag.splitter.PdfStructureSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按文件扩展名路由到合适的切分器。 Markdown → MarkdownStructureSplitter（可降级） PDF →
 * PdfStructureSplitter（可降级） 其他 → TokenTextSplitter 保持原始文档顺序，本身不包含任何切分逻辑。
 *
 * @author deepresearch
 */
public class DocumentSplitterRouter implements DocumentTransformer {

	private static final Set<String> MD_EXTENSIONS = Set.of(".md", ".markdown");

	private static final Set<String> PDF_EXTENSIONS = Set.of(".pdf");

	private final MarkdownStructureSplitter markdownSplitter;

	private final PdfStructureSplitter pdfSplitter;

	private final TokenTextSplitter tokenSplitter;

	private final RagProperties ragProperties;

	public DocumentSplitterRouter(MarkdownStructureSplitter markdownSplitter, PdfStructureSplitter pdfSplitter,
			TokenTextSplitter tokenSplitter, RagProperties ragProperties) {
		this.markdownSplitter = markdownSplitter;
		this.pdfSplitter = pdfSplitter;
		this.tokenSplitter = tokenSplitter;
		this.ragProperties = ragProperties;
	}

	@Override
	public List<Document> apply(List<Document> documents) {
		// preserve original document order via LinkedHashMap keyed by index
		Map<Integer, List<Document>> resultMap = new LinkedHashMap<>();

		for (int i = 0; i < documents.size(); i++) {
			Document doc = documents.get(i);
			String ext = resolveExtension(doc);
			DocumentTransformer splitter = chooseSplitter(ext);
			resultMap.put(i, splitter.apply(List.of(doc)));
		}

		List<Document> result = new ArrayList<>();
		resultMap.values().forEach(result::addAll);
		return result;
	}

	private DocumentTransformer chooseSplitter(String ext) {
		if (MD_EXTENSIONS.contains(ext)) {
			return ragProperties.getMarkdownSplitter().isEnabled() ? markdownSplitter : tokenSplitter;
		}
		if (PDF_EXTENSIONS.contains(ext)) {
			return ragProperties.getPdfSplitter().isEnabled() ? pdfSplitter : tokenSplitter;
		}
		return tokenSplitter;
	}

	private String resolveExtension(Document doc) {
		String filename = null;
		Object originalFilename = doc.getMetadata().get("original_filename");
		if (originalFilename != null) {
			filename = originalFilename.toString();
		}
		if (filename == null) {
			Object source = doc.getMetadata().get("source");
			if (source != null) {
				filename = source.toString();
			}
		}
		if (filename == null) {
			return "";
		}
		int dot = filename.lastIndexOf('.');
		return dot >= 0 ? filename.substring(dot).toLowerCase() : "";
	}

}
