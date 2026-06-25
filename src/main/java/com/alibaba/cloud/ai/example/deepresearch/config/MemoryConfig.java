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
