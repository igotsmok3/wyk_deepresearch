package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.alibaba.cloud.ai.example.deepresearch.model.enums.ConfidenceLevel;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 用户角色识别结果数据模型，描述从对话中推断出的用户身份及其置信度。
 *
 * <p>
 * 项目职责：作为短期记忆提取结果的子结构，存储可能的身份列表、角色特征、 证据摘要和置信度等级，供后续个性化处理使用。
 *
 * <p>
 * 被使用情况：{@code ShortUserRoleExtractResult} 持有该对象作为字段 {@code identifiedRole}。
 *
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
