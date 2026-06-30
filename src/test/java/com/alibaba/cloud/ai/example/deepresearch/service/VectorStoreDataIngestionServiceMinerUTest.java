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
import com.alibaba.cloud.ai.example.deepresearch.rag.reader.MinerUDocumentReader;
import com.alibaba.cloud.ai.example.deepresearch.rag.reader.MinerUParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;

@ExtendWith(MockitoExtension.class)
class VectorStoreDataIngestionServiceMinerUTest {

	@Mock
	VectorStore vectorStore;

	@Mock
	DocumentSplitterRouter splitterRouter;

	@Mock
	MinerUDocumentReader minerUReader;

	RagProperties ragProperties;

	VectorStoreDataIngestionService service;

	@BeforeEach
	void setUp() {
		ragProperties = new RagProperties();
		ragProperties.getMinerU().setEnabled(true);

		service = new VectorStoreDataIngestionService(vectorStore, ragProperties, splitterRouter,
				Optional.of(minerUReader));
	}

	@Test
	void batchProcessAndStore_usesMinerUForPdf() throws Exception {
		MultipartFile pdfFile = new MockMultipartFile("file", "report.pdf", "application/pdf", "fake-pdf".getBytes());

		List<Document> minerUDocs = List.of(new Document("# Markdown", Map.of("original_filename", "report.md")));
		List<Document> chunks = List.of(new Document("chunk", Map.of()));

		given(minerUReader.read(eq("report.pdf"), any(), any())).willReturn(minerUDocs);
		given(splitterRouter.apply(minerUDocs)).willReturn(chunks);
		willDoNothing().given(vectorStore).add(anyList());

		service.batchProcessAndStore(List.of(pdfFile), "session-1", "user-1");

		then(vectorStore).should().add(anyList());
		then(minerUReader).should().read(eq("report.pdf"), any(), any());
	}

	@Test
	void batchProcessAndStore_fallsBackToTikaOnMinerUFailure() throws Exception {
		MultipartFile pdfFile = new MockMultipartFile("file", "report.pdf", "application/pdf", "fake-pdf".getBytes());

		willThrow(new MinerUParseException("API error")).given(minerUReader).read(eq("report.pdf"), any(), any());
		willDoNothing().given(vectorStore).add(anyList());
		given(splitterRouter.apply(any())).willReturn(List.of(new Document("fallback chunk", Map.of())));

		assertThatCode(() -> service.batchProcessAndStore(List.of(pdfFile), "session-1", "user-1"))
			.doesNotThrowAnyException();

		then(vectorStore).should().add(anyList());
	}

	@Test
	void batchProcessAndStore_nonPdfSkipsMinerU() {
		MultipartFile txtFile = new MockMultipartFile("file", "notes.txt", "text/plain", "hello world".getBytes());

		given(splitterRouter.apply(any())).willReturn(List.of(new Document("chunk", Map.of())));
		willDoNothing().given(vectorStore).add(anyList());

		service.batchProcessAndStore(List.of(txtFile), "session-1", "user-1");

		then(minerUReader).shouldHaveNoInteractions();
		then(vectorStore).should().add(anyList());
	}

	@Test
	void batchProcessAndStore_whenMinerUDisabled_usesOnlyTika() {
		ragProperties.getMinerU().setEnabled(false);
		service = new VectorStoreDataIngestionService(vectorStore, ragProperties, splitterRouter, Optional.empty());

		MultipartFile pdfFile = new MockMultipartFile("file", "report.pdf", "application/pdf", "fake-pdf".getBytes());

		given(splitterRouter.apply(any())).willReturn(List.of(new Document("chunk", Map.of())));
		willDoNothing().given(vectorStore).add(anyList());

		service.batchProcessAndStore(List.of(pdfFile), "session-1", "user-1");

		then(minerUReader).shouldHaveNoInteractions();
		then(vectorStore).should().add(anyList());
	}

}
