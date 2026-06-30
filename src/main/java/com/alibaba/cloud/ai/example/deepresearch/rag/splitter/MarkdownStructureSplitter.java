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

package com.alibaba.cloud.ai.example.deepresearch.rag.splitter;

import com.alibaba.cloud.ai.example.deepresearch.config.rag.RagProperties;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.document.DocumentTransformer;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 commonmark AST 的 Markdown 结构感知切分器。 按标题层级（splitLevel）划定 chunk 边界，代码块和表格整体保留不跨块拆断。
 * 切分失败时自动降级到 TokenTextSplitter。
 *
 * @author deepresearch
 */
public class MarkdownStructureSplitter implements DocumentTransformer {

	private static final Logger logger = LoggerFactory.getLogger(MarkdownStructureSplitter.class);

	private final RagProperties ragProperties;

	private final TokenTextSplitter fallbackSplitter;

	private final Parser parser;

	private final TextContentRenderer renderer;

	public MarkdownStructureSplitter(RagProperties ragProperties, TokenTextSplitter fallbackSplitter) {
		this.ragProperties = ragProperties;
		this.fallbackSplitter = fallbackSplitter;

		List<org.commonmark.Extension> extensions = List.of(TablesExtension.create());
		this.parser = Parser.builder().extensions(extensions).build();
		this.renderer = TextContentRenderer.builder().extensions(extensions).build();
	}

	@Override
	public List<Document> apply(List<Document> documents) {
		try {
			List<Document> result = new ArrayList<>();
			for (Document doc : documents) {
				result.addAll(splitDocument(doc));
			}
			return result;
		}
		catch (Exception e) {
			// 任何解析异常都降级，保证不丢文档
			logger.warn("md文档的解析出现异常！已降级为 tokenTextSplitter！", e);
			return fallbackSplitter.apply(documents);
		}
	}

	private List<Document> splitDocument(Document doc) {
		String text = doc.getText();
		if (text == null || text.isBlank()) {
			return List.of(doc);
		}
		// 第一步：按标题结构切出若干 ChunkBuilder（原始内容片段）
		List<ChunkBuilder> chunks = splitByHeadings(text);
		List<Document> result = new ArrayList<>();
		RagProperties.MarkdownSplitter config = ragProperties.getMarkdownSplitter();
		int maxTokens = resolveMaxChunkSize(config);

		for (ChunkBuilder chunk : chunks) {
			String content = chunk.content.toString().strip();
			if (content.isEmpty()) {
				continue;
			}
			// 继承原文档元数据，再附加本 chunk 自己的结构信息
			Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
			if (config.isAppendHeadingPath()) {
				// heading_path 记录该 chunk 在文档结构中的位置，如 "第一章 > 1.2 小节"
				metadata.put("heading_path", chunk.headingPath);
			}
			metadata.put("content_type", chunk.contentType);
			metadata.put("splitter_type", "markdown_structure");

			Document chunkDoc = new Document(content, metadata);

			// 单个 chunk 仍然过大时，用 TokenTextSplitter 二次切分，保底不丢内容
			if (maxTokens > 0 && estimateTokens(content) > maxTokens) {
				result.addAll(fallbackSplitter.apply(List.of(chunkDoc)));
			}
			else {
				result.add(chunkDoc);
			}
		}
		// 如果所有 chunk 都是空的（极端情况），返回原文档避免内容丢失
		return result.isEmpty() ? List.of(doc) : result;
	}

	private List<ChunkBuilder> splitByHeadings(String markdownText) {
		RagProperties.MarkdownSplitter config = ragProperties.getMarkdownSplitter();
		// splitLevel 默认 2，即在 H2 处切断；H3 及更深的标题留在同一 chunk 内
		int splitLevel = config.getSplitLevel();

		// 用 commonmark 将 Markdown 解析为 AST，顶层 document 节点的子节点即为段落、标题、代码块等
		Node document = parser.parse(markdownText);

		List<ChunkBuilder> chunks = new ArrayList<>();
		// headingStack 记录当前所处的标题层级路径，用来生成 heading_path 元数据
		// 使用 Deque 作为栈：push 最新标题，descendingIterator 可得到从根到叶的顺序
		Deque<String> headingStack = new ArrayDeque<>();
		// current 代表正在累积内容的当前 chunk；文档开头（无标题前）也需要一个 chunk 来承接内容
		ChunkBuilder current = new ChunkBuilder(buildHeadingPath(headingStack), "text");

		// 遍历 AST 顶层节点（每个节点是一个块级元素：标题、段落、代码块、表格等）
		for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
			if (node instanceof Heading heading) {
				int level = heading.getLevel();
				String headingText = renderer.render(heading).strip();

				if (level <= splitLevel) {
					// 遇到切分级别（≤H2）的标题：将已积累的 current 存档，开启新 chunk
					chunks.add(current);
					// 清空栈：新的顶级标题覆盖之前所有层级，简化处理（不需要精确还原多层路径）
					while (!headingStack.isEmpty()) {
						headingStack.pop();
					}
					headingStack.push(headingText);
					current = new ChunkBuilder(buildHeadingPath(headingStack), "text");
					// 将标题文本写入新 chunk 的开头（统一用 H1 格式，便于后续处理）
					current.content.append("# ").append(headingText).append("\n\n");
				}
				else {
					// 深层标题（如 H3/H4）：不切断，只更新路径栈并追加到当前 chunk
					headingStack.push(headingText);
					current.content.append(renderer.render(heading)).append("\n\n");
				}
			}
			else if (node instanceof FencedCodeBlock && config.isKeepCodeBlockIntact()) {
				// 代码块：先存档 current，然后将代码块单独作为一个 chunk，再重新开始新的 current
				// 这样保证代码块不会与周围文字混在一起，也不会被 TokenTextSplitter 从中截断
				chunks.add(current);
				current = new ChunkBuilder(buildHeadingPath(headingStack), "text");

				String codeContent = renderer.render(node).strip();
				ChunkBuilder codeChunk = new ChunkBuilder(buildHeadingPath(headingStack), "code");
				codeChunk.content.append(codeContent);
				chunks.add(codeChunk);
			}
			else if (node instanceof TableBlock && config.isKeepTableIntact()) {
				// 表格：同代码块处理逻辑，整体保留为独立 chunk 防止行列被拆散
				chunks.add(current);
				current = new ChunkBuilder(buildHeadingPath(headingStack), "text");

				String tableContent = renderer.render(node).strip();
				ChunkBuilder tableChunk = new ChunkBuilder(buildHeadingPath(headingStack), "table");
				tableChunk.content.append(tableContent);
				chunks.add(tableChunk);
			}
			else {
				// 普通段落、引用块等：直接追加到当前 chunk
				current.content.append(renderer.render(node)).append("\n");
			}
		}
		// 最后一个 chunk 不会被后续标题触发存档，需要手动收尾
		chunks.add(current);
		return chunks;
	}

	private String buildHeadingPath(Deque<String> stack) {
		if (stack.isEmpty()) {
			return "";
		}
		// ArrayDeque 作为栈是 LIFO（最新 push 的在队头），descendingIterator 从队尾到队头迭代，
		// 即按 push 的先后顺序输出，得到"根标题 > 子标题"的层级路径
		List<String> parts = new ArrayList<>();
		stack.descendingIterator().forEachRemaining(parts::add);
		return String.join(" > ", parts);
	}

	private int resolveMaxChunkSize(RagProperties.MarkdownSplitter config) {
		int v = config.getMaxChunkSize();
		// markdown 专项配置未设置时，回退到通用 textSplitter 的最大 chunk 大小
		return v <= 0 ? ragProperties.getTextSplitter().getMaxChunkSize() : v;
	}

	private int estimateTokens(String text) {
		// 中文约 1.5 字符/token，英文约 4 字符/token，除以 3 作为偏保守的统一估算
		return text.length() / 3;
	}

	private static class ChunkBuilder {

		final String headingPath;

		final String contentType;

		final StringBuilder content = new StringBuilder();

		ChunkBuilder(String headingPath, String contentType) {
			this.headingPath = headingPath;
			this.contentType = contentType;
		}

	}

}
