package com.alibaba.cloud.ai.example.deepresearch.model.enums;

/**
 * 内容深度枚举，表示用户期望回复内容的知识深度偏好。
 *
 * <p>项目职责：作为枚举值嵌入 {@code CommunicationPreferences}，
 * 描述用户对回复内容是偏好概要、实践还是概念层面的讲解。
 *
 * <p>被使用情况：{@code CommunicationPreferences} 持有该枚举字段 {@code contentDepth}。
 *
 * @author benym
 */
public enum ContentDepth {

	/**
	 * 概要
	 */
	OVERVIEW,

	/**
	 * 实践
	 */
	PRACTICAL,

	/**
	 * 概念
	 */
	CONCEPTUAL,

}
