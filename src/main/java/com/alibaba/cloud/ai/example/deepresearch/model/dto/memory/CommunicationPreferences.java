package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.alibaba.cloud.ai.example.deepresearch.model.enums.ContentDepth;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.DetailLevel;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.ResponseFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 用户沟通偏好数据模型，记录用户偏好的回复详细程度、内容深度和响应格式。
 *
 * <p>
 * 项目职责：作为短期记忆提取结果的子结构，由 LLM 从对话历史中推断并填充， 用于后续节点个性化调整回复风格。
 *
 * <p>
 * 被使用情况：{@code ShortUserRoleExtractResult} 持有该对象作为字段 {@code communicationPreferences}。
 *
 * @author benym
 */
public class CommunicationPreferences {

	/**
	 * 详细程度
	 */
	@JsonProperty("detailLevel")
	private DetailLevel detailLevel;

	/**
	 * 内容深度
	 */
	@JsonProperty("contentDepth")
	private ContentDepth contentDepth;

	/**
	 * 响应格式
	 */
	@JsonProperty("responseFormat")
	private ResponseFormat responseFormat;

	public DetailLevel getDetailLevel() {
		return detailLevel;
	}

	public void setDetailLevel(DetailLevel detailLevel) {
		this.detailLevel = detailLevel;
	}

	public ContentDepth getContentDepth() {
		return contentDepth;
	}

	public void setContentDepth(ContentDepth contentDepth) {
		this.contentDepth = contentDepth;
	}

	public ResponseFormat getResponseFormat() {
		return responseFormat;
	}

	public void setResponseFormat(ResponseFormat responseFormat) {
		this.responseFormat = responseFormat;
	}

}
