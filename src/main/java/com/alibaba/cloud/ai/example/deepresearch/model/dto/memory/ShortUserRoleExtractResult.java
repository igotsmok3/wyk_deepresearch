package com.alibaba.cloud.ai.example.deepresearch.model.dto.memory;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 短期用户角色提取结果，聚合 LLM 从对话历史中提取的用户画像信息。
 *
 * <p>
 * 项目职责：作为短期记忆节点的核心数据结构，存储用户 ID、会话 ID、角色识别、 对话分析、沟通偏好和用户概述等信息，持久化到记忆存储并在后续节点复用。
 *
 * <p>
 * 被使用情况：{@code ShortUserRoleMemoryNode} 负责提取、合并并持久化该对象； {@code TemplateUtil} 用于构建包含历史记忆的
 * Prompt 消息。
 *
 * @author benym
 */
public class ShortUserRoleExtractResult {

	/**
	 * 用户ID
	 */
	@JsonProperty("userId")
	private String userId;

	/**
	 * 会话ID
	 */
	@JsonProperty("conversationId")
	private String conversationId;

	/**
	 * 用户提问内容
	 */
	@JsonProperty("userQuery")
	private String userQuery;

	/**
	 * 会话分析信息
	 */
	@JsonProperty("conversationAnalysis")
	private ConversationAnalysis conversationAnalysis;

	/**
	 * 角色识别信息
	 */
	@JsonProperty("identifiedRole")
	private IdentifiedRole identifiedRole;

	/**
	 * 交流偏好信息
	 */
	@JsonProperty("communicationPreferences")
	private CommunicationPreferences communicationPreferences;

	/**
	 * 综合上述实体的用户概述信息
	 */
	@JsonProperty("userOverview")
	private String userOverview;

	/**
	 * 创建时间
	 */
	@JsonProperty("creatTime")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private String creatTime;

	/**
	 * 更新时间
	 */
	@JsonProperty("updateTime")
	@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private String updateTime;

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public String getConversationId() {
		return conversationId;
	}

	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}

	public String getUserQuery() {
		return userQuery;
	}

	public void setUserQuery(String userQuery) {
		this.userQuery = userQuery;
	}

	public ConversationAnalysis getConversationAnalysis() {
		return conversationAnalysis;
	}

	public void setConversationAnalysis(ConversationAnalysis conversationAnalysis) {
		this.conversationAnalysis = conversationAnalysis;
	}

	public IdentifiedRole getIdentifiedRole() {
		return identifiedRole;
	}

	public void setIdentifiedRole(IdentifiedRole identifiedRole) {
		this.identifiedRole = identifiedRole;
	}

	public CommunicationPreferences getCommunicationPreferences() {
		return communicationPreferences;
	}

	public void setCommunicationPreferences(CommunicationPreferences communicationPreferences) {
		this.communicationPreferences = communicationPreferences;
	}

	public String getUserOverview() {
		return userOverview;
	}

	public void setUserOverview(String userOverview) {
		this.userOverview = userOverview;
	}

	public String getCreatTime() {
		return creatTime;
	}

	public void setCreatTime(String creatTime) {
		this.creatTime = creatTime;
	}

	public String getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(String updateTime) {
		this.updateTime = updateTime;
	}

}
