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

package com.alibaba.cloud.ai.example.deepresearch.controller;

import com.alibaba.cloud.ai.example.deepresearch.model.ApiResponse;
import com.alibaba.cloud.ai.example.deepresearch.rag.SourceTypeEnum;
import com.alibaba.cloud.ai.example.deepresearch.rag.core.HybridRagProcessor;
import com.alibaba.cloud.ai.example.deepresearch.service.VectorStoreDataIngestionService;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 知识库数据管理控制器，提供文档上传与向量化入库的 REST 接口。
 *
 * <p>
 * 项目职责：controller 层的数据管理入口，对外暴露单文件上传、批量用户文件上传以及 专业知识库文件导入等接口，委托
 * {@link com.alibaba.cloud.ai.example.deepresearch.service.VectorStoreDataIngestionService}
 * 完成文档解析、分块和向量存储。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理，无其他 Java 类直接引用。
 */
@RestController
@RequestMapping("/api/rag/")
public class RagDataController {

	private final VectorStoreDataIngestionService ingestionService;

	private final HybridRagProcessor hybridRagProcessor;

	public RagDataController(VectorStoreDataIngestionService ingestionService,
			ObjectProvider<HybridRagProcessor> hybridRagProcessorProvider) {
		this.ingestionService = ingestionService;
		this.hybridRagProcessor = hybridRagProcessorProvider.getIfAvailable();
	}

	@PostMapping("/upload")
	public ResponseEntity<Map<String, String>> handleFileUpload(@RequestParam("file") MultipartFile file) {
		if (file.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("message", "File is empty."));
		}

		try {
			// MultipartFile.getResource() is a convenient way to pass it to the service
			ingestionService.ingest(file.getResource());
			String message = "File '" + file.getOriginalFilename() + "' uploaded and ingested successfully.";
			return ResponseEntity.ok(Map.of("message", message));
		}
		catch (Exception e) {
			String message = "Failed to upload and ingest '" + file.getOriginalFilename() + "'.";
			return ResponseEntity.internalServerError().body(Map.of("message", message, "error", e.getMessage()));
		}
	}

	@PostMapping(value = "/upload", consumes = "multipart/form-data")
	public ResponseEntity<Map<String, Object>> handleFileUpload(@RequestParam("file") MultipartFile file,
			@RequestParam("session_id") String sessionId,
			@RequestParam(value = "user_id", required = false) String userId) {

		if (file.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "File cannot be empty."));
		}

		try {
			ingestionService.processAndStore(file, sessionId, userId);
			Map<String, Object> response = new HashMap<>();
			response.put("message", "File processed successfully for session: " + sessionId);
			response.put("filename", file.getOriginalFilename());
			response.put("session_id", sessionId);
			response.put("user_id", userId);
			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("error", "Failed to process file: " + file.getOriginalFilename());
			errorResponse.put("message", e.getMessage());
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}

	/**
	 * 批量上传用户文件接口
	 */
	@PostMapping(value = "/user/batch-upload", consumes = "multipart/form-data")
	public ResponseEntity<ApiResponse<Map<String, Object>>> handleBatchUserFileUpload(
			@RequestParam("files") List<MultipartFile> files, @RequestParam("session_id") String sessionId,
			@RequestParam(value = "user_id", required = false) String userId) {

		if (files == null || files.isEmpty()) {
			return ResponseEntity.ok(ApiResponse.error("No files provided"));
		}

		try {
			int totalChunks = ingestionService.batchProcessAndStore(files, sessionId, userId);

			Map<String, Object> response = new HashMap<>();
			response.put("message", "Batch upload completed successfully");
			response.put("session_id", sessionId);
			response.put("user_id", userId);
			response.put("file_count", files.size());
			response.put("total_chunks", totalChunks);
			response.put("filenames", files.stream().map(MultipartFile::getOriginalFilename).toList());

			return ResponseEntity.ok(ApiResponse.success(response));
		}
		catch (Exception e) {
			return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
		}
	}

	/**
	 * 上传文件到专业知识库ES接口
	 */
	@PostMapping(value = "/professional-kb/upload", consumes = "multipart/form-data")
	public ResponseEntity<Map<String, Object>> handleProfessionalKbUpload(@RequestParam("file") MultipartFile file,
			@RequestParam("kb_id") String kbId, @RequestParam("kb_name") String kbName,
			@RequestParam("kb_description") String kbDescription,
			@RequestParam(value = "category", required = false) String category) {

		if (file.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "File cannot be empty"));
		}

		try {
			int chunks = ingestionService.uploadToProfessionalKbEs(file, kbId, kbName, kbDescription, category);

			Map<String, Object> response = new HashMap<>();
			response.put("message", "Professional KB file uploaded successfully");
			response.put("kb_id", kbId);
			response.put("kb_name", kbName);
			response.put("filename", file.getOriginalFilename());
			response.put("chunks_created", chunks);
			response.put("category", category);

			return ResponseEntity.ok(response);
		}
		catch (Exception e) {
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("error", "Failed to upload file to professional KB");
			errorResponse.put("message", e.getMessage());
			errorResponse.put("kb_id", kbId);
			errorResponse.put("filename", file.getOriginalFilename());
			return ResponseEntity.internalServerError().body(errorResponse);
		}
	}

	/**
	 * 批量上传文件到专业知识库ES接口
	 */
	@PostMapping(value = "/professional-kb/batch-upload", consumes = "multipart/form-data")
	public ResponseEntity<ApiResponse<Map<String, Object>>> handleBatchProfessionalKbUpload(
			@RequestParam("files") List<MultipartFile> files, @RequestParam("kb_id") String kbId,
			@RequestParam("kb_name") String kbName, @RequestParam("kb_description") String kbDescription,
			@RequestParam(value = "category", required = false) String category) {

		if (files == null || files.isEmpty()) {
			return ResponseEntity.badRequest().body(ApiResponse.error("No files provided"));
		}

		try {
			int totalChunks = ingestionService.batchUploadToProfessionalKbEs(files, kbId, kbName, kbDescription,
					category);

			Map<String, Object> response = new HashMap<>();
			response.put("message", "Professional KB batch upload completed successfully");
			response.put("kb_id", kbId);
			response.put("kb_name", kbName);
			response.put("kb_description", kbDescription);
			response.put("category", category);
			response.put("file_count", files.size());
			response.put("total_chunks", totalChunks);
			response.put("filenames", files.stream().map(MultipartFile::getOriginalFilename).toList());

			return ResponseEntity.ok(ApiResponse.success(response));
		}
		catch (Exception e) {
			Map<String, Object> errorResponse = new HashMap<>();
			errorResponse.put("error", "Failed to process professional KB batch upload");
			errorResponse.put("message", e.getMessage());
			errorResponse.put("kb_id", kbId);
			errorResponse.put("file_count", files.size());
			return ResponseEntity.internalServerError()
				.body(ApiResponse.error("Failed to process professional KB batch upload", errorResponse));
		}
	}

	/**
	 * 检索测试端点（仅用于基准测试），直接调用 RAG 处理器，跳过 LLM 生成阶段。 请求体：{"query": "...", "kb_id": "optional",
	 * "source_type": "optional"}
	 */
	@PostMapping("/search")
	public ResponseEntity<ApiResponse<List<Map<String, Object>>>> search(@RequestBody Map<String, Object> requestBody) {
		if (hybridRagProcessor == null) {
			return ResponseEntity.ok(ApiResponse.error("RAG is not enabled"));
		}
		String queryText = (String) requestBody.getOrDefault("query", "");
		if (queryText.isBlank()) {
			return ResponseEntity.badRequest().body(ApiResponse.error("query is required"));
		}

		Map<String, Object> options = new HashMap<>();
		options.put("source_type",
				requestBody.getOrDefault("source_type", SourceTypeEnum.PROFESSIONAL_KB_ES.getValue()));
		options.put("session_id", "professional_kb_es");
		if (requestBody.containsKey("kb_id")) {
			options.put("kb_id", requestBody.get("kb_id"));
		}
		// 将查询文本传入 options 供后处理 RRF 使用
		options.put("query", queryText);

		List<Document> docs = hybridRagProcessor.process(new Query(queryText), options);

		List<Map<String, Object>> result = docs.stream().map(doc -> {
			Map<String, Object> item = new HashMap<>();
			item.put("id", doc.getId());
			item.put("text", doc.getText().substring(0, Math.min(200, doc.getText().length())));
			item.put("metadata", doc.getMetadata());
			return item;
		}).collect(Collectors.toList());

		return ResponseEntity.ok(ApiResponse.success(result));
	}

}
