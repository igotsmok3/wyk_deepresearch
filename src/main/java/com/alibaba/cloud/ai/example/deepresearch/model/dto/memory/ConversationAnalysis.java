package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
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
