package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.alibaba.cloud.ai.example.deepresearch.model.enums.ContentDepth;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.DetailLevel;
import com.alibaba.cloud.ai.example.deepresearch.model.enums.ResponseFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
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
