package com.alibaba.cloud.ai.example.deepresearch.model.enums;

/**
 * 回复详细程度枚举，表示用户期望回复内容的详略偏好。
 *
 * <p>项目职责：作为枚举值嵌入 {@code CommunicationPreferences}，
 * 描述用户偏好简洁、平衡还是全面详尽的回复风格。
 *
 * <p>被使用情况：{@code CommunicationPreferences} 持有该枚举字段 {@code detailLevel}。
 *
 * @author benym
 */
public enum DetailLevel {

	/**
	 * 简洁
	 */
	CONCISE,

	/**
	 * 平衡
	 */
	BALANCE,

	/**
	 * 详细
	 */
	COMPREHENSIVE

}
