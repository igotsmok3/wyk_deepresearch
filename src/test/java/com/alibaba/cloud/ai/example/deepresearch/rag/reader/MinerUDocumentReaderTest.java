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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class MinerUDocumentReaderTest {

	@Mock
	MinerUApiClient apiClient;

	MinerUDocumentReader reader;

	RagProperties.MinerU props;

	@BeforeEach
	void setUp() {
		props = new RagProperties.MinerU();
		props.setPollingIntervalMs(0);
		props.setMaxPollingAttempts(3);
		reader = new MinerUDocumentReader(apiClient, props);
	}

	@Test
	void read_successfulParse_returnsDocumentWithMarkdown() {
		byte[] pdfBytes = "fake-pdf".getBytes();
		given(apiClient.requestUploadUrls(any()))
			.willReturn(new MinerUApiClient.BatchUploadResponse("batch-1", List.of("https://upload.example.com/1")));
		willDoNothing().given(apiClient).uploadFile(any(), any());
		given(apiClient.pollBatchResult("batch-1"))
			.willReturn(new MinerUApiClient.BatchResultResponse("done", "https://cdn.example.com/result.zip", null));
		given(apiClient.downloadAndExtractMarkdown("https://cdn.example.com/result.zip"))
			.willReturn("# Title\n\nContent");

		List<Document> docs = reader.read("test.pdf", pdfBytes, Map.of("session_id", "s1"));

		assertThat(docs).hasSize(1);
		assertThat(docs.get(0).getText()).isEqualTo("# Title\n\nContent");
		assertThat(docs.get(0).getMetadata().get("original_filename")).isEqualTo("test.md");
		assertThat(docs.get(0).getMetadata().get("session_id")).isEqualTo("s1");
		assertThat(docs.get(0).getMetadata().get("parsed_by")).isEqualTo("mineru");
	}

	@Test
	void read_pollsUntilDone() {
		byte[] pdfBytes = "fake-pdf".getBytes();
		given(apiClient.requestUploadUrls(any()))
			.willReturn(new MinerUApiClient.BatchUploadResponse("batch-2", List.of("https://upload.example.com/2")));
		willDoNothing().given(apiClient).uploadFile(any(), any());
		given(apiClient.pollBatchResult("batch-2"))
			.willReturn(new MinerUApiClient.BatchResultResponse("running", null, null))
			.willReturn(new MinerUApiClient.BatchResultResponse("done", "https://cdn.example.com/r2.zip", null));
		given(apiClient.downloadAndExtractMarkdown(any())).willReturn("# Done");

		List<Document> docs = reader.read("test.pdf", pdfBytes, Map.of());

		assertThat(docs).hasSize(1);
		then(apiClient).should(times(2)).pollBatchResult("batch-2");
	}

	@Test
	void read_throwsWhenMaxAttemptsExceeded() {
		byte[] pdfBytes = "fake-pdf".getBytes();
		given(apiClient.requestUploadUrls(any()))
			.willReturn(new MinerUApiClient.BatchUploadResponse("batch-3", List.of("https://upload.example.com/3")));
		willDoNothing().given(apiClient).uploadFile(any(), any());
		given(apiClient.pollBatchResult(any()))
			.willReturn(new MinerUApiClient.BatchResultResponse("running", null, null));

		assertThatThrownBy(() -> reader.read("test.pdf", pdfBytes, Map.of())).isInstanceOf(MinerUParseException.class)
			.hasMessageContaining("timeout");
	}

	@Test
	void read_throwsWhenStateFailed() {
		byte[] pdfBytes = "fake-pdf".getBytes();
		given(apiClient.requestUploadUrls(any()))
			.willReturn(new MinerUApiClient.BatchUploadResponse("batch-4", List.of("https://upload.example.com/4")));
		willDoNothing().given(apiClient).uploadFile(any(), any());
		given(apiClient.pollBatchResult(any()))
			.willReturn(new MinerUApiClient.BatchResultResponse("failed", null, "parse error"));

		assertThatThrownBy(() -> reader.read("test.pdf", pdfBytes, Map.of())).isInstanceOf(MinerUParseException.class)
			.hasMessageContaining("parse error");
	}

	@Test
	void read_throwsWhenMarkdownIsEmpty() {
		byte[] pdfBytes = "fake-pdf".getBytes();
		given(apiClient.requestUploadUrls(any()))
			.willReturn(new MinerUApiClient.BatchUploadResponse("batch-5", List.of("https://upload.example.com/5")));
		willDoNothing().given(apiClient).uploadFile(any(), any());
		given(apiClient.pollBatchResult(any()))
			.willReturn(new MinerUApiClient.BatchResultResponse("done", "https://cdn.example.com/r5.zip", null));
		given(apiClient.downloadAndExtractMarkdown(any())).willReturn("");

		assertThatThrownBy(() -> reader.read("test.pdf", pdfBytes, Map.of())).isInstanceOf(MinerUParseException.class)
			.hasMessageContaining("empty");
	}

}
