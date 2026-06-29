package com.alibaba.cloud.ai.example.deepresearch.model.enums;

/**
 * 置信度等级枚举，表示对用户角色识别结果的把握程度。
 *
 * <p>项目职责：作为枚举值嵌入 {@code IdentifiedRole}，由 LLM 在角色识别时输出，
 * 用于评估角色推断的可靠性。
 *
 * <p>被使用情况：{@code IdentifiedRole} 持有该枚举字段 {@code confidenceLevel}。
 *
 * @author benym
 */
public enum ConfidenceLevel {

	/**
	 * low confidence
	 */
	LOW,

	/**
	 * medium confidence
	 */
	MEDIUM,

	/**
	 * medium high confidence
	 */
	MEDIUM_HIGH,

	/**
	 * high confidence
	 */
	HIGH;

}
