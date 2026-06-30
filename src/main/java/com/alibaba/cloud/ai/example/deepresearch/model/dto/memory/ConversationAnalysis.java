package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对话分析数据模型，记录当前会话的置信度分数和交互次数。
 *
 * <p>
 * 项目职责：作为短期记忆提取结果的子结构，由 LLM 评估对话信息质量后填充， 用于控制记忆合并策略（高置信度优先）。
 *
 * <p>
 * 被使用情况：{@code ShortUserRoleExtractResult} 持有该对象； {@code ShortUserRoleMemoryNode}
 * 读取置信度分数决定是否更新已存储的记忆，并累加交互次数。
 *
 * @author benym
 */
public class ConversationAnalysis {

	/**
	 * 置信度分数
	 */
	@JsonProperty("confidenceScore")
	private Double confidenceScore;

	/**
	 * 交互次数
	 */
	@JsonProperty("interactionCount")
	private Integer interactionCount;

	public Double getConfidenceScore() {
		return confidenceScore;
	}

	public void setConfidenceScore(Double confidenceScore) {
		this.confidenceScore = confidenceScore;
	}

	public Integer getInteractionCount() {
		return interactionCount;
	}

	public void setInteractionCount(Integer interactionCount) {
		this.interactionCount = interactionCount;
	}

}
