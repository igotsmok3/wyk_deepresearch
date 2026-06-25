package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.alibaba.cloud.ai.example.deepresearch.model.enums.ConfidenceLevel;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * @author benym
 */
public class IdentifiedRole {

	/**
	 * 可能的身份
	 */
	@JsonProperty("possibleIdentities")
	private List<String> possibleIdentities;

	/**
	 * 角色特征
	 */
	@JsonProperty("primaryCharacteristics")
	private List<String> primaryCharacteristics;

	/**
	 * 角色特征证据摘要
	 */
	@JsonProperty("evidenceSummary")
	private List<String> evidenceSummary;

	/**
	 * 置信度等级
	 */
	@JsonProperty("confidenceLevel")
	private ConfidenceLevel confidenceLevel;

	public List<String> getPossibleIdentities() {
		return possibleIdentities;
	}

	public void setPossibleIdentities(List<String> possibleIdentities) {
		this.possibleIdentities = possibleIdentities;
	}

	public ConfidenceLevel getConfidenceLevel() {
		return confidenceLevel;
	}

	public void setConfidenceLevel(ConfidenceLevel confidenceLevel) {
		this.confidenceLevel = confidenceLevel;
	}

	public List<String> getEvidenceSummary() {
		return evidenceSummary;
	}

	public void setEvidenceSummary(List<String> evidenceSummary) {
		this.evidenceSummary = evidenceSummary;
	}

	public List<String> getPrimaryCharacteristics() {
		return primaryCharacteristics;
	}

	public void setPrimaryCharacteristics(List<String> primaryCharacteristics) {
		this.primaryCharacteristics = primaryCharacteristics;
	}

}
