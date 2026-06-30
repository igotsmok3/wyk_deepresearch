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
 * 用户短期记忆管理控制器，提供对话历史和角色抽取记忆的查询与删除接口。
 *
 * <p>
 * 项目职责：controller 层的记忆管理入口，暴露 {@code /api/user/memory/} 下的会话历史
 * ({@code /conversation})、记忆轨迹 ({@code /track})、最新记忆 ({@code /latest}) 和 删除
 * ({@code /delete}) 四个端点，分别由 {@code MessageWindowChatMemory} 和
 * {@code ShortTermMemoryRepository} 提供数据支撑。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理，无其他 Java 类直接引用。
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
