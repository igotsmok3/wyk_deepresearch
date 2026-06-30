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

package com.alibaba.cloud.ai.example.deepresearch.rag.reader;

import com.alibaba.cloud.ai.example.deepresearch.config.rag.RagProperties;
import com.alibaba.cloud.ai.example.deepresearch.service.MinerUApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 MinerU 精准解析 API 将 PDF 转换为 Markdown Document。 流程：申请预签名 URL → PUT 上传 → 轮询 → 下载 ZIP 提取
 * full.md → 构造 Document。 original_filename 元数据替换为 .md 后缀，使 DocumentSplitterRouter 路由到
 * MarkdownStructureSplitter。
 */
public class MinerUDocumentReader {

	private static final Logger logger = LoggerFactory.getLogger(MinerUDocumentReader.class);

	private final MinerUApiClient apiClient;

	private final RagProperties.MinerU props;

	public MinerUDocumentReader(MinerUApiClient apiClient, RagProperties.MinerU props) {
		this.apiClient = apiClient;
		this.props = props;
	}

	public List<Document> read(String filename, byte[] content, Map<String, Object> baseMetadata) {
		// Step 1: 申请预签名上传 URL
		var uploadResp = apiClient.requestUploadUrls(List.of(new MinerUApiClient.FileUploadRequest(filename, null)));

		// Step 2: PUT 上传（不带 Content-Type）
		apiClient.uploadFile(uploadResp.fileUrls().get(0), content);

		// Step 3: 轮询直到解析完成
		String zipUrl = pollUntilDone(uploadResp.batchId());

		// Step 4: 下载 ZIP 并提取 full.md
		String markdown = apiClient.downloadAndExtractMarkdown(zipUrl);
		if (markdown == null || markdown.isBlank()) {
			throw new MinerUParseException("MinerU returned empty markdown for file: " + filename);
		}

		// Step 5: 构造 Document，original_filename 改为 .md 后缀
		Map<String, Object> metadata = new HashMap<>(baseMetadata);
		metadata.put("original_filename", toMdFilename(filename));
		metadata.put("parsed_by", "mineru");

		logger.info("MinerU parsed {} successfully, markdown length={}", filename, markdown.length());
		return List.of(new Document(markdown, metadata));
	}

	private String pollUntilDone(String batchId) {
		for (int i = 0; i < props.getMaxPollingAttempts(); i++) {
			var result = apiClient.pollBatchResult(batchId);
			String state = result.state();
			if ("done".equals(state)) {
				return result.fullZipUrl();
			}
			if ("failed".equals(state)) {
				throw new MinerUParseException("MinerU parse failed for batchId=" + batchId + ": " + result.errMsg());
			}
			logger.debug("MinerU batchId={} state={}, attempt {}/{}", batchId, state, i + 1,
					props.getMaxPollingAttempts());
			if (props.getPollingIntervalMs() > 0) {
				try {
					Thread.sleep(props.getPollingIntervalMs());
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new MinerUParseException("MinerU polling interrupted for batchId: " + batchId);
				}
			}
		}
		throw new MinerUParseException("MinerU polling timeout for batchId=" + batchId + " after "
				+ props.getMaxPollingAttempts() + " attempts");
	}

	private String toMdFilename(String filename) {
		if (filename == null) {
			return "document.md";
		}
		int dot = filename.lastIndexOf('.');
		String base = dot >= 0 ? filename.substring(0, dot) : filename;
		return base + ".md";
	}

}
