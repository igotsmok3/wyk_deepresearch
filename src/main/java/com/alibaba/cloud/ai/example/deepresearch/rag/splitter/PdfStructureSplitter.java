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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.document.DocumentTransformer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF 结构感知切分器。 优先读取书签目录（outline）按章节切分，无书签时启发式识别字体大小较大的行作为标题。 两条路径均失败时降级到
 * TokenTextSplitter。
 *
 * @author deepresearch
 */
public class PdfStructureSplitter implements DocumentTransformer {

	private static final Logger logger = LoggerFactory.getLogger(PdfStructureSplitter.class);

	private final RagProperties ragProperties;

	private final TokenTextSplitter fallbackSplitter;

	public PdfStructureSplitter(RagProperties ragProperties, TokenTextSplitter fallbackSplitter) {
		this.ragProperties = ragProperties;
		this.fallbackSplitter = fallbackSplitter;
	}

	@Override
	public List<Document> apply(List<Document> documents) {
		List<Document> result = new ArrayList<>();
		for (Document doc : documents) {
			result.addAll(splitDocument(doc));
		}
		return result;
	}

	private List<Document> splitDocument(Document doc) {
		try {
			// PDF 内容已由上游 Reader 读入 doc.getText()，但原始文件路径保存在元数据中
			// 需要重新加载原始文件才能访问书签目录（outline）和字体信息
			String sourcePath = resolveSourcePath(doc);
			if (sourcePath == null) {
				logger.warn("PdfStructureSplitter: cannot resolve source path from metadata, falling back");
				return fallbackSplitter.apply(List.of(doc));
			}

			File pdfFile = new File(sourcePath);
			if (!pdfFile.exists() || !pdfFile.isFile()) {
				logger.warn("PdfStructureSplitter: source file not found at {}, falling back", sourcePath);
				return fallbackSplitter.apply(List.of(doc));
			}

			try (PDDocument pdDocument = Loader.loadPDF(pdfFile)) {
				PDDocumentOutline outline = pdDocument.getDocumentCatalog().getDocumentOutline();
				// 优先走书签模式：有书签目录时按章节切分，更准确；无书签时降级到字体大小启发式
				if (outline != null && outline.getFirstChild() != null) {
					return splitByOutline(doc, pdDocument, outline);
				}
				else {
					return splitByFontSize(doc, pdDocument);
				}
			}
		}
		catch (Exception e) {
			logger.warn("PdfStructureSplitter failed for document, falling back to TokenTextSplitter", e);
			return fallbackSplitter.apply(List.of(doc));
		}
	}

	private List<Document> splitByOutline(Document sourceDoc, PDDocument pdDocument, PDDocumentOutline outline)
			throws IOException {
		List<OutlineSegment> segments = new ArrayList<>();
		// DFS 收集书签树中所有条目，每个条目记录标题文本和它所在的起始页码
		collectOutlineSegments(pdDocument, outline.getFirstChild(), segments);

		if (segments.isEmpty()) {
			// 书签树存在但无法解析到有效页码，降级到字体启发式
			return splitByFontSize(sourceDoc, pdDocument);
		}

		// 计算每个 segment 的结束页：当前 segment 结束于下一个 segment 开始页的前一页，
		// 最后一个 segment 延伸至文档末尾
		int totalPages = pdDocument.getNumberOfPages();
		for (int i = 0; i < segments.size(); i++) {
			OutlineSegment seg = segments.get(i);
			seg.endPage = (i + 1 < segments.size()) ? segments.get(i + 1).startPage - 1 : totalPages;
		}

		List<Document> result = new ArrayList<>();
		for (OutlineSegment seg : segments) {
			if (seg.startPage < 1 || seg.startPage > totalPages) {
				continue;
			}
			String text = extractText(pdDocument, seg.startPage, Math.min(seg.endPage, totalPages));
			if (text == null || text.isBlank()) {
				continue;
			}
			result.addAll(buildChunks(sourceDoc, text, seg.title, seg.startPage, seg.endPage, "pdf_structure"));
		}
		return result.isEmpty() ? fallbackSplitter.apply(List.of(sourceDoc)) : result;
	}

	private void collectOutlineSegments(PDDocument doc, PDOutlineItem item, List<OutlineSegment> segments)
			throws IOException {
		// 书签树是 n 叉树：while 循环遍历兄弟节点，递归调用处理子节点（DFS 前序遍历）
		while (item != null) {
			try {
				org.apache.pdfbox.pdmodel.PDPage page = item.findDestinationPage(doc);
				if (page != null) {
					// PDFBox 的页码是 0-based index，转换为用户可见的 1-based 页码
					int pageIndex = doc.getPages().indexOf(page) + 1;
					segments.add(new OutlineSegment(item.getTitle(), pageIndex));
				}
			}
			catch (Exception e) {
				// 单个书签解析失败不影响整体，继续处理其余条目
				logger.debug("Could not resolve page for outline item: {}", item.getTitle());
			}

			// 先深入子节点，再处理下一个兄弟，保证 segments 列表与文档页码顺序一致
			if (item.getFirstChild() != null) {
				collectOutlineSegments(doc, item.getFirstChild(), segments);
			}
			item = item.getNextSibling();
		}
	}

	private List<Document> splitByFontSize(Document sourceDoc, PDDocument pdDocument) throws IOException {
		// FontSizeStripper 扩展了 PDFTextStripper，在提取文本的同时记录每行的字体大小和页码
		FontSizeStripper stripper = new FontSizeStripper();
		stripper.setSortByPosition(true);
		stripper.getText(pdDocument); // 触发文本提取，结果缓存在 stripper.lines 中

		List<FontSizeStripper.LineInfo> lines = stripper.getLines();
		if (lines.isEmpty()) {
			return fallbackSplitter.apply(List.of(sourceDoc));
		}

		// 用中位数而非平均数作为正文基准：避免被少量超大字体（封面、图注）拉偏
		double[] sizes = lines.stream().mapToDouble(l -> l.fontSize).sorted().toArray();
		double median = sizes[sizes.length / 2];
		float ratio = ragProperties.getPdfSplitter().getHeadingFontSizeRatio();
		// headingThreshold 默认 = median * 1.2，即字体比正文大 20% 以上的行视为标题
		double headingThreshold = median * ratio;

		List<Integer> headingLines = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			FontSizeStripper.LineInfo line = lines.get(i);
			// 字体够大 + 行文本较短（<80 字符）才认定为标题；长行即使字体大也可能是正文大字
			if (line.fontSize > headingThreshold && line.text.strip().length() < 80) {
				headingLines.add(i);
			}
		}

		if (headingLines.isEmpty()) {
			logger.debug("PdfStructureSplitter: no headings detected by font size, falling back");
			return fallbackSplitter.apply(List.of(sourceDoc));
		}

		// 将相邻两个标题行之间的所有行合并为一个 segment
		List<Document> result = new ArrayList<>();
		for (int h = 0; h < headingLines.size(); h++) {
			int start = headingLines.get(h);
			// segment 的结束行 = 下一个标题行的前一行；最后一个 segment 到文件末尾
			int end = (h + 1 < headingLines.size()) ? headingLines.get(h + 1) : lines.size();

			StringBuilder sb = new StringBuilder();
			for (int i = start; i < end; i++) {
				sb.append(lines.get(i).text).append("\n");
			}
			String text = sb.toString().strip();
			if (text.isBlank()) {
				continue;
			}
			String headingText = lines.get(start).text.strip();
			int pageNum = lines.get(start).page;
			result.addAll(buildChunks(sourceDoc, text, headingText, pageNum, pageNum, "pdf_structure"));
		}
		return result.isEmpty() ? fallbackSplitter.apply(List.of(sourceDoc)) : result;
	}

	private String extractText(PDDocument doc, int startPage, int endPage) throws IOException {
		PDFTextStripper stripper = new PDFTextStripper();
		stripper.setSortByPosition(true); // 按坐标排序，避免多栏布局文字顺序混乱
		stripper.setStartPage(startPage);
		stripper.setEndPage(endPage);
		return stripper.getText(doc);
	}

	private List<Document> buildChunks(Document sourceDoc, String text, String headingPath, int startPage, int endPage,
			String splitterType) {
		Map<String, Object> metadata = new HashMap<>(sourceDoc.getMetadata());
		metadata.put("heading_path", headingPath);
		metadata.put("content_type", "text");
		metadata.put("page_range", startPage + "-" + endPage);
		metadata.put("splitter_type", splitterType);

		Document chunkDoc = new Document(text, metadata);

		// chunk 超过 token 上限时降级二次切分，与 MarkdownStructureSplitter 保持一致
		int maxTokens = resolveMaxChunkSize();
		if (maxTokens > 0 && estimateTokens(text) > maxTokens) {
			return fallbackSplitter.apply(List.of(chunkDoc));
		}
		return List.of(chunkDoc);
	}

	private String resolveSourcePath(Document doc) {
		// Spring AI 的 PDF Reader 将文件路径写入 "source" 或 "original_filename" 元数据字段
		Object source = doc.getMetadata().get("source");
		if (source != null) {
			return source.toString();
		}
		Object filename = doc.getMetadata().get("original_filename");
		return filename != null ? filename.toString() : null;
	}

	private int resolveMaxChunkSize() {
		int v = ragProperties.getPdfSplitter().getMaxChunkSize();
		// PDF 专项配置未设置时，回退到通用 textSplitter 的最大 chunk 大小
		return v <= 0 ? ragProperties.getTextSplitter().getMaxChunkSize() : v;
	}

	private int estimateTokens(String text) {
		// 中文约 1.5 字符/token，英文约 4 字符/token，除以 3 作为偏保守的统一估算
		return text.length() / 3;
	}

	private static class OutlineSegment {

		final String title;

		final int startPage;

		int endPage;

		OutlineSegment(String title, int startPage) {
			this.title = title;
			this.startPage = startPage;
		}

	}

	/**
	 * PDFTextStripper 子类，在提取文本的同时捕获每行的字体大小和页码，供启发式标题识别使用。
	 */
	private static class FontSizeStripper extends PDFTextStripper {

		private final List<LineInfo> lines = new ArrayList<>();

		// 当前行的文字缓冲区，每次 writeLineSeparator 时刷新
		private final StringBuilder lineBuffer = new StringBuilder();

		// 当前行的字体大小，取该行第一个字符的字体尺寸作为代表值
		private float currentFontSize = 0f;

		private int currentPage = 1;

		FontSizeStripper() throws IOException {
		}

		@Override
		protected void startPage(org.apache.pdfbox.pdmodel.PDPage page) throws IOException {
			// PDFTextStripper 每翻到新页时调用此方法，更新当前页码
			currentPage = getCurrentPageNo();
			super.startPage(page);
		}

		@Override
		protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
			// textPositions 包含每个字符的详细渲染信息；取第一个字符的字体大小作为本段文字的代表字号
			if (!textPositions.isEmpty()) {
				currentFontSize = textPositions.get(0).getFontSizeInPt();
			}
			lineBuffer.append(text);
			super.writeString(text, textPositions);
		}

		@Override
		protected void writeLineSeparator() throws IOException {
			// PDF 每遇到换行符时触发此回调；此时将 lineBuffer 中积累的一行文字连同字号、页码一起存档
			String lineText = lineBuffer.toString();
			if (!lineText.isBlank()) {
				lines.add(new LineInfo(lineText, currentFontSize, currentPage));
			}
			// 重置行缓冲区和字号，准备接收下一行
			lineBuffer.setLength(0);
			currentFontSize = 0f;
			super.writeLineSeparator();
		}

		@Override
		protected void writeString(String string) throws IOException {
			// 单参数版本由父类内部调用，我们已在双参数版本中处理，此处置为空操作避免重复追加
		}

		List<LineInfo> getLines() {
			return lines;
		}

		static class LineInfo {

			final String text;

			final float fontSize;

			final int page;

			LineInfo(String text, float fontSize, int page) {
				this.text = text;
				this.fontSize = fontSize;
				this.page = page;
			}

		}

	}

}
