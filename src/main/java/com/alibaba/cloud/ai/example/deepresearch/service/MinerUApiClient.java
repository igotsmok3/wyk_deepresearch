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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MinerU HTTP API 客户端。封装预签名 URL 申请、文件上传、轮询和 ZIP 下载逻辑。
 */
public class MinerUApiClient {

	public record FileUploadRequest(String name, String dataId) {
	}

	public record BatchUploadResponse(String batchId, List<String> fileUrls) {
	}

	public record BatchResultResponse(String state, String fullZipUrl, String errMsg) {
	}

	private final RagProperties.MinerU props;

	private final RestClient restClient;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	public MinerUApiClient(RagProperties.MinerU props) {
		this.props = props;
		this.objectMapper = new ObjectMapper();

		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(props.getConnectTimeoutMs());
		factory.setReadTimeout(props.getReadTimeoutMs());

		String baseUrl = props.getApiBaseUrl();
		if (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}

		this.restClient = RestClient.builder()
			.baseUrl(baseUrl)
			.requestFactory(factory)
			.defaultHeader("Authorization", "Bearer " + props.getApiToken())
			.build();

		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
			.build();
	}

	public BatchUploadResponse requestUploadUrls(List<FileUploadRequest> files) {
		try {
			String body = buildRequestBody(files);
			String response = restClient.post()
				.uri("/api/v4/file-urls/batch")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body)
				.retrieve()
				.onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
					throw new MinerUApiException(
							"MinerU requestUploadUrls failed with status: " + resp.getStatusCode());
				})
				.body(String.class);

			JsonNode root = objectMapper.readTree(response);
			checkApiCode(root);
			JsonNode data = root.path("data");
			String batchId = data.path("batch_id").asText();
			List<String> fileUrls = new ArrayList<>();
			data.path("file_urls").forEach(n -> fileUrls.add(n.asText()));
			return new BatchUploadResponse(batchId, fileUrls);
		}
		catch (MinerUApiException e) {
			throw e;
		}
		catch (Exception e) {
			throw new MinerUApiException("Failed to request upload URLs", e);
		}
	}

	public void uploadFile(String presignedUrl, byte[] content) {
		try {
			// PUT 上传不能带 Content-Type，使用 java.net.http.HttpClient 以精确控制 headers
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(presignedUrl))
				.PUT(HttpRequest.BodyPublishers.ofByteArray(content))
				.build();

			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new MinerUApiException("MinerU uploadFile failed with status: " + response.statusCode());
			}
		}
		catch (MinerUApiException e) {
			throw e;
		}
		catch (Exception e) {
			throw new MinerUApiException("Failed to upload file to presigned URL", e);
		}
	}

	public BatchResultResponse pollBatchResult(String batchId) {
		try {
			String response = restClient.get()
				.uri("/api/v4/extract-results/batch/" + batchId)
				.retrieve()
				.onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
					throw new MinerUApiException("MinerU pollBatchResult failed with status: " + resp.getStatusCode());
				})
				.body(String.class);

			JsonNode root = objectMapper.readTree(response);
			checkApiCode(root);
			JsonNode data = root.path("data");
			String state = data.path("state").asText();
			String fullZipUrl = data.path("full_zip_url").isNull() ? null : data.path("full_zip_url").asText(null);
			String errMsg = data.path("err_msg").asText(null);
			return new BatchResultResponse(state, fullZipUrl, errMsg);
		}
		catch (MinerUApiException e) {
			throw e;
		}
		catch (Exception e) {
			throw new MinerUApiException("Failed to poll batch result", e);
		}
	}

	public String downloadAndExtractMarkdown(String zipUrl) {
		try {
			byte[] zipBytes = restClient.get()
				.uri(URI.create(zipUrl))
				.retrieve()
				.onStatus(status -> !status.is2xxSuccessful(), (req, resp) -> {
					throw new MinerUApiException("MinerU downloadZip failed with status: " + resp.getStatusCode());
				})
				.body(byte[].class);

			return extractFullMdFromZip(zipBytes);
		}
		catch (MinerUApiException e) {
			throw e;
		}
		catch (Exception e) {
			throw new MinerUApiException("Failed to download and extract markdown from ZIP", e);
		}
	}

	private String extractFullMdFromZip(byte[] zipBytes) throws IOException {
		try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				String name = entry.getName();
				if (name.endsWith("full.md") || name.equals("full.md")) {
					byte[] content = zis.readAllBytes();
					return new String(content, StandardCharsets.UTF_8);
				}
				zis.closeEntry();
			}
		}
		throw new MinerUApiException("full.md not found in MinerU result ZIP");
	}

	private void checkApiCode(JsonNode root) {
		int code = root.path("code").asInt(-1);
		if (code != 0) {
			String msg = root.path("msg").asText("unknown error");
			throw new MinerUApiException("MinerU API error code " + code + ": " + msg);
		}
	}

	private String buildRequestBody(List<FileUploadRequest> files) throws Exception {
		StringBuilder sb = new StringBuilder("{\"files\":[");
		for (int i = 0; i < files.size(); i++) {
			FileUploadRequest f = files.get(i);
			sb.append("{\"name\":").append(objectMapper.writeValueAsString(f.name()));
			if (f.dataId() != null) {
				sb.append(",\"data_id\":").append(objectMapper.writeValueAsString(f.dataId()));
			}
			sb.append("}");
			if (i < files.size() - 1) {
				sb.append(",");
			}
		}
		sb.append("]}");
		return sb.toString();
	}

}
