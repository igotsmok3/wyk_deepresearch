package com.alibaba.cloud.ai.example.deepresearch.controller;

import com.alibaba.cloud.ai.example.deepresearch.memory.ShortTermMemoryRepository;
import com.alibaba.cloud.ai.example.deepresearch.model.ApiResponse;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 短期记忆控制器
 *
 * @author benym
 */
@RestController
@RequestMapping("/api/user/memory/")
public class ShortUserRoleMemoryController {

	@Resource
	private ShortTermMemoryRepository shortTermMemoryRepository;

	@Autowired(required = false)
	private MessageWindowChatMemory messageWindowChatMemory;

	/**
	 * 获取会话历史消息
	 * @param sessionId 会话Id
	 * @return ResponseEntity<ApiResponse<List<Message>>>
	 */
	@GetMapping("/conversation")
	public ResponseEntity<ApiResponse<List<Message>>> getConversationHistory(
			@RequestParam("session_id") String sessionId) {
		List<Message> messages = messageWindowChatMemory.get(sessionId);
		return ResponseEntity.ok(ApiResponse.success(messages));
	}

	/**
	 * 获取用户角色抽取短期记忆轨迹
	 * @param userId 用户Id
	 * @param sessionId 会话Id
	 * @return ResponseEntity<ApiResponse<List<Message>>>
	 */
	@GetMapping("/track")
	public ResponseEntity<ApiResponse<List<Message>>> getUserShortTermMemoryTrack(
			@RequestParam(value = "user_id", defaultValue = "MOCK_USER_ID") String userId,
			@RequestParam("session_id") String sessionId) {
		List<Message> messageTrack = shortTermMemoryRepository.findMessageTrack(userId, sessionId);
		return ResponseEntity.ok(ApiResponse.success(messageTrack));
	}

	/**
	 * 获取用户最近一条角色抽取短期记忆
	 * @param userId 用户Id
	 * @param sessionId 会话Id
	 * @return ResponseEntity<ApiResponse<Message>>
	 */
	@GetMapping("/latest")
	public ResponseEntity<ApiResponse<Message>> getLatestUserShortTermMemory(
			@RequestParam(value = "user_id", defaultValue = "MOCK_USER_ID") String userId,
			@RequestParam("session_id") String sessionId) {
		Message message = shortTermMemoryRepository.findLatestExtractMessage(userId, sessionId);
		return ResponseEntity.ok(ApiResponse.success(message));
	}

	/**
	 * 删除用户角色抽取短期记忆
	 * @param userId 用户Id
	 * @param sessionId 会话Id
	 * @return ResponseEntity<ApiResponse<String>>
	 */
	@PostMapping("/delete")
	public ResponseEntity<ApiResponse<String>> deleteUserShortTermMemory(
			@RequestParam(value = "user_id", defaultValue = "MOCK_USER_ID") String userId,
			@RequestParam("session_id") String sessionId) {
		shortTermMemoryRepository.deleteBy(userId, sessionId);
		return ResponseEntity.ok(ApiResponse.success("User short-term memory deleted successfully"));
	}

}
