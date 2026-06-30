package com.alibaba.cloud.ai.example.deepresearch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 短期记忆模块的配置属性类，绑定 {@code spring.ai.alibaba.deepresearch.short-term-memory.*} 前缀的配置项。
 *
 * <p>
 * 项目职责：属于配置层，控制两套独立的记忆机制： {@code userRoleMemory}（用户角色画像提取，由
 * {@code ShortUserRoleMemoryNode} 使用）和 {@code conversationMemory}（对话历史滑动窗口，由
 * {@code MessageWindowChatMemory} 使用）。
 * 同时提供记忆存储类型枚举（{@code MemoryType}）和画像注入范围枚举（{@code GuideScope}）。
 *
 * <p>
 * 被使用情况：被 {@code DeepResearchConfiguration}、{@code MemoryConfig}、
 * {@code ShortUserRoleMemoryNode}、{@code RewriteAndMultiQueryNode}、{@code ReporterNode}
 * 等多处注入， 用于控制短期记忆的行为与参数。
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

	/**
	 * 控制用户角色画像何时被注入到后续节点（coordinator/planner 等）的提示词中。 NONE：只提取不注入，画像仅存库，不影响 LLM 行为
	 * ONCE：只在第一轮注入，后续轮次清空 short_user_role_memory 字段（节省 token） EVERY：每轮都注入，持续让 LLM
	 * 感知用户背景（默认值）
	 */
	public enum GuideScope {

		NONE,

		ONCE,

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
