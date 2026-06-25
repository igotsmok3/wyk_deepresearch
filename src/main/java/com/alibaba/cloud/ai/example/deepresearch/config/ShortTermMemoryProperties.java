package com.alibaba.cloud.ai.example.deepresearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Short-term memory configuration properties
 *
 * @author benym
 */
@ConfigurationProperties(prefix = ShortTermMemoryProperties.PREFIX)
public class ShortTermMemoryProperties {

	public static final String PREFIX = DeepResearchProperties.PREFIX + ".short-term-memory";

	/**
	 * Whether short-term memory is enabled
	 */
	private boolean enabled = true;

	/**
	 * User role memory configuration
	 */
	private UserRoleMemory userRoleMemory;

	/**
	 * Conversation memory configuration
	 */
	private ConversationMemory conversationMemory;

	/**
	 * Type of memory storage
	 */
	private MemoryType memoryType = MemoryType.IN_MEMORY;

	/**
	 * User role memory configuration
	 */
	public static class UserRoleMemory {

		/**
		 * Scope of short-term memory guidance
		 */
		private GuideScope guideScope = GuideScope.EVERY;

		/**
		 * Similarity threshold for updating short-term memory
		 */
		private Double updateSimilarityThreshold = 0.8;

		/**
		 * The number of recent user questions for reference in user role extraction
		 */
		private int historyUserMessagesNum = 10;

		public GuideScope getGuideScope() {
			return guideScope;
		}

		public void setGuideScope(GuideScope guideScope) {
			this.guideScope = guideScope;
		}

		public Double getUpdateSimilarityThreshold() {
			return updateSimilarityThreshold;
		}

		public void setUpdateSimilarityThreshold(Double updateSimilarityThreshold) {
			this.updateSimilarityThreshold = updateSimilarityThreshold;
		}

		public int getHistoryUserMessagesNum() {
			return historyUserMessagesNum;
		}

		public void setHistoryUserMessagesNum(int historyUserMessagesNum) {
			this.historyUserMessagesNum = historyUserMessagesNum;
		}

	}

	/**
	 * Conversation memory configuration
	 */
	public static class ConversationMemory {

		/**
		 * Maximum number of messages stored in conversation memory
		 */
		private Integer maxMessages = 100;

		public Integer getMaxMessages() {
			return maxMessages;
		}

		public void setMaxMessages(Integer maxMessages) {
			this.maxMessages = maxMessages;
		}

	}

	public enum GuideScope {

		/**
		 * No guidance
		 */
		NONE,

		/**
		 * Only in the first round of the guiding model
		 */
		ONCE,

		/**
		 * Each round will guide the model
		 */
		EVERY

	}

	public enum MemoryType {

		/**
		 * In-memory storage
		 */
		IN_MEMORY

	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public MemoryType getMemoryType() {
		return memoryType;
	}

	public void setMemoryType(MemoryType memoryType) {
		this.memoryType = memoryType;
	}

	public UserRoleMemory getUserRoleMemory() {
		return userRoleMemory;
	}

	public void setUserRoleMemory(UserRoleMemory userRoleMemory) {
		this.userRoleMemory = userRoleMemory;
	}

	public ConversationMemory getConversationMemory() {
		return conversationMemory;
	}

	public void setConversationMemory(ConversationMemory conversationMemory) {
		this.conversationMemory = conversationMemory;
	}

}
