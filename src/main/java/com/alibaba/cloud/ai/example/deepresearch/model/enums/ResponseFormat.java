package com.alibaba.cloud.ai.example.deepresearch.model.enums;

/**
 * 响应格式枚举，表示用户期望 AI 回复内容的结构化程度偏好。
 *
 * <p>
 * 项目职责：作为枚举值嵌入 {@code CommunicationPreferences}， 描述用户偏好简洁、详细还是带示例的结构化回复格式。
 *
 * <p>
 * 被使用情况：{@code CommunicationPreferences} 持有该枚举字段 {@code responseFormat}。
 *
 * @author benym
 */
public enum ResponseFormat {

	/**
	 * 简洁的
	 */
	CONCISE,

	/**
	 * 详细的
	 */
	DETAILED,

	/**
	 * 结构化的
	 */
	STRUCTURED_WITH_EXAMPLES

}
