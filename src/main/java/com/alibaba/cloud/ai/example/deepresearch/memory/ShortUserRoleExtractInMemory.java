package com.alibaba.cloud.ai.example.deepresearch.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * {@link ShortTermMemoryRepository} 的纯内存实现，使用三个并发 Map 分层管理用户提问历史和角色画像。
 *
 * <p>
 * 项目职责：维护三个独立的 ConcurrentHashMap： (1)
 * {@code userQueryMemory}（key=conversationId）存每次用户提问原文； (2)
 * {@code shortTermMemoryTrack}（key=userId:conversationId）存 LLM 每次抽取的完整画像历史； (3)
 * {@code shortTermMemory}（key=userId:conversationId）只保留最新一次画像，供快速比对置信度。 默认实现，当 Redis
 * 未启用时由 Spring 注入各依赖方。
 *
 * <p>
 * 被使用情况：作为 {@code ShortTermMemoryRepository} 的默认 Bean，被 {@code ShortUserRoleMemoryNode}、
 * {@code RewriteAndMultiQueryNode} 和 {@code ShortUserRoleMemoryController} 通过接口注入使用。
 *
 * @author benym
 */
@Component
public class ShortUserRoleExtractInMemory implements ShortTermMemoryRepository {

	// key=conversationId，存用户每轮提问的原文，带 create_time 元数据用于按时间排序
	Map<String, List<UserMessage>> userQueryMemory = new ConcurrentHashMap<>();

	// key=userId:conversationId，存每次 LLM 提取的角色画像（JSON 序列化为 SystemMessage），是完整历史轨迹
	Map<String, List<Message>> shortTermMemoryTrack = new ConcurrentHashMap<>();

	// key=userId:conversationId，只保留最新一次角色画像，供 ShortUserRoleMemoryNode 快速比对置信度
	Map<String, Message> shortTermMemory = new ConcurrentHashMap<>();

	// userId + conversationId 组合成唯一 key，隔离不同用户的不同会话
	private String buildKey(String userId, String conversationId) {
		return userId + ":" + conversationId;
	}

	@Override
	public List<Message> getRecentUserMessages(String conversationId, Integer limit) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		List<UserMessage> messages = userQueryMemory.get(conversationId);
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		List<UserMessage> sortedMessages = new ArrayList<>(messages);
		sortedMessages.sort(Comparator.comparing(this::resolveCreateTime).reversed());
		if (limit == null) {
			return new ArrayList<>(sortedMessages);
		}
		return sortedMessages.stream().limit(limit).collect(Collectors.toList());
	}

	@Override
	public List<String> getRecentUserQueries(String conversationId, Integer limit) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		List<UserMessage> messages = userQueryMemory.get(conversationId);
		if (messages == null || messages.isEmpty()) {
			return List.of();
		}
		List<UserMessage> sortedMessages = new ArrayList<>(messages);
		sortedMessages.sort(Comparator.comparing(this::resolveCreateTime).reversed());
		if (limit > 0) {
			return sortedMessages.stream().limit(limit).map(UserMessage::getText).collect(Collectors.toList());
		}
		return sortedMessages.stream().map(UserMessage::getText).collect(Collectors.toList());
	}

	@Override
	public void saveUserQuery(String conversationId, List<UserMessage> messages) {
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		Assert.notNull(messages, "messages cannot be null");
		List<UserMessage> userMessages = userQueryMemory.get(conversationId);
		if (!CollectionUtils.isEmpty(userMessages)) {
			userMessages.addAll(messages);
			userQueryMemory.put(conversationId, userMessages);
		}
		else {
			userQueryMemory.put(conversationId, messages);
		}
	}

	@Override
	public List<Message> findMessageTrack(String userId, String conversationId) {
		Assert.hasText(userId, "userId cannot be null or empty");
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		List<Message> messages = shortTermMemoryTrack.get(buildKey(userId, conversationId));
		return messages != null ? messages : List.of();
	}

	@Override
	public Message findLatestExtractMessage(String userId, String conversationId) {
		Assert.hasText(userId, "userId cannot be null or empty");
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		return shortTermMemory.get(buildKey(userId, conversationId));
	}

	@Override
	public void saveOrUpdate(String userId, String conversationId, List<Message> messages) {
		Assert.hasText(userId, "userId cannot be null or empty");
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		Assert.notNull(messages, "messages cannot be null");
		List<Message> trackMessages = shortTermMemoryTrack.get(buildKey(userId, conversationId));
		// Track 追加新记录（完整历史），shortTermMemory 始终覆盖为最新一条（快速查询用）
		if (!CollectionUtils.isEmpty(trackMessages)) {
			trackMessages.addAll(messages);
			shortTermMemoryTrack.put(buildKey(userId, conversationId), trackMessages);
		}
		else {
			shortTermMemoryTrack.put(buildKey(userId, conversationId), messages);
		}
		shortTermMemory.put(buildKey(userId, conversationId), messages.get(0));
	}

	@Override
	public void deleteBy(String userId, String conversationId) {
		Assert.hasText(userId, "userId cannot be null or empty");
		Assert.hasText(conversationId, "conversationId cannot be null or empty");
		shortTermMemoryTrack.remove(buildKey(userId, conversationId));
		shortTermMemory.remove(buildKey(userId, conversationId));
	}

	private LocalDateTime resolveCreateTime(UserMessage message) {
		Map<String, Object> metadata = message.getMetadata();
		Object value = metadata.get("create_time");
		return value instanceof LocalDateTime ? (LocalDateTime) value : LocalDateTime.MIN;
	}

}
