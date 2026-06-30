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
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MinerUApiClientTest {

	static MockWebServer mockServer;

	MinerUApiClient client;

	@BeforeEach
	void setUp() throws IOException {
		mockServer = new MockWebServer();
		mockServer.start();
		RagProperties.MinerU props = new RagProperties.MinerU();
		props.setApiBaseUrl(mockServer.url("/").toString());
		props.setApiToken("test-token");
		props.setConnectTimeoutMs(1000);
		props.setReadTimeoutMs(5000);
		client = new MinerUApiClient(props);
	}

	@AfterEach
	void tearDown() throws IOException {
		mockServer.shutdown();
	}

	@Test
	void requestUploadUrls_returnsCorrectBatchIdAndUrls() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200)
			.setBody("{\"code\":0,\"data\":{\"batch_id\":\"batch-001\","
					+ "\"file_urls\":[\"https://cdn.example.com/upload/abc\"]}}")
			.addHeader("Content-Type", "application/json"));

		var result = client.requestUploadUrls(List.of(new MinerUApiClient.FileUploadRequest("test.pdf", "data-001")));

		assertThat(result.batchId()).isEqualTo("batch-001");
		assertThat(result.fileUrls()).hasSize(1).contains("https://cdn.example.com/upload/abc");

		RecordedRequest req = mockServer.takeRequest();
		assertThat(req.getPath()).isEqualTo("/api/v4/file-urls/batch");
		assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-token");
	}

	@Test
	void requestUploadUrls_throwsOnApiError() {
		mockServer.enqueue(new MockResponse().setResponseCode(401));

		assertThatThrownBy(
				() -> client.requestUploadUrls(List.of(new MinerUApiClient.FileUploadRequest("a.pdf", null))))
			.isInstanceOf(MinerUApiException.class);
	}

	@Test
	void uploadFile_putsFileWithoutContentType() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200));

		client.uploadFile(mockServer.url("/upload/abc").toString(), "pdf-bytes".getBytes());

		RecordedRequest req = mockServer.takeRequest();
		assertThat(req.getMethod()).isEqualTo("PUT");
		assertThat(req.getHeader("Content-Type")).isNull();
		assertThat(req.getBody().readUtf8()).isEqualTo("pdf-bytes");
	}

	@Test
	void pollBatchResult_returnsRunningState() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200)
			.setBody("{\"code\":0,\"data\":{\"state\":\"running\",\"full_zip_url\":null}}")
			.addHeader("Content-Type", "application/json"));

		var result = client.pollBatchResult("batch-001");

		assertThat(result.state()).isEqualTo("running");
		assertThat(result.fullZipUrl()).isNull();
	}

	@Test
	void pollBatchResult_returnsDoneState() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200)
			.setBody("{\"code\":0,\"data\":{\"state\":\"done\","
					+ "\"full_zip_url\":\"https://cdn.example.com/result.zip\"}}")
			.addHeader("Content-Type", "application/json"));

		var result = client.pollBatchResult("batch-001");

		assertThat(result.state()).isEqualTo("done");
		assertThat(result.fullZipUrl()).isEqualTo("https://cdn.example.com/result.zip");
	}

	@Test
	void downloadAndExtractMarkdown_returnsMarkdownContent() throws Exception {
		byte[] zipBytes = buildZipWithMarkdown("# Hello\n\nWorld");
		mockServer.enqueue(new MockResponse().setResponseCode(200)
			.setBody(new Buffer().write(zipBytes))
			.addHeader("Content-Type", "application/zip"));

		String markdown = client.downloadAndExtractMarkdown(mockServer.url("/result.zip").toString());

		assertThat(markdown).isEqualTo("# Hello\n\nWorld");
	}

	private byte[] buildZipWithMarkdown(String content) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ZipOutputStream zos = new ZipOutputStream(bos)) {
			ZipEntry entry = new ZipEntry("full.md");
			zos.putNextEntry(entry);
			zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return bos.toByteArray();
	}

}
