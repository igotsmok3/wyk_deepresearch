package com.alibaba.cloud.ai.example.deepresearch.config;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话窗口记忆配置类，仅在 {@code spring.ai.alibaba.deepresearch.short-term-memory.enabled=true} 时生效。
 *
 * <p>项目职责：属于配置层，创建 {@code MessageWindowChatMemory} Bean，
 * 底层使用 {@code InMemoryChatMemoryRepository} 按 sessionId 存储 User/Assistant 消息轮次，
 * 超出 {@code maxMessages} 限制时自动丢弃最旧消息（滑动窗口），为多轮对话提供短期记忆能力。
 *
 * <p>被使用情况：产出的 {@code MessageWindowChatMemory} Bean 被 {@code CoordinatorNode} 和
 * {@code ReporterNode} 注入，用于在节点执行时维护并查询多轮对话历史。
 *
 * @author benym
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.alibaba.deepresearch.short-term-memory.enabled", havingValue = "true")
public class MemoryConfig {

	private static final Logger logger = LoggerFactory.getLogger(MemoryConfig.class);

	@Resource
	private ShortTermMemoryProperties shortTermMemoryProperties;

	@Bean
	public MessageWindowChatMemory messageWindowChatMemory() {
		int maxMessages = shortTermMemoryProperties.getConversationMemory().getMaxMessages();
		logger.info("Initializing InMemory MessageWindowChatMemory with max messages: {}", maxMessages);
		InMemoryChatMemoryRepository inMemoryChatMemoryRepository = new InMemoryChatMemoryRepository();
		return MessageWindowChatMemory.builder()
			.chatMemoryRepository(inMemoryChatMemoryRepository)
			.maxMessages(maxMessages)
			.build();
	}

}
