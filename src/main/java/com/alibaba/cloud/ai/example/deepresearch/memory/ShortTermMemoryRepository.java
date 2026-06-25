package com.alibaba.cloud.ai.example.deepresearch.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * @author benym
 */
public interface ShortTermMemoryRepository {

	/**
	 * 获取用户最近的提问记忆
	 * @param conversationId 会话Id
	 * @param limit 限制条数
	 * @return List<String>
	 */
	List<Message> getRecentUserMessages(String conversationId, Integer limit);

	/**
	 * 获取用户最近的提问记忆
	 * @param conversationId 会话Id
	 * @param limit 限制条数
	 * @return List<String>
	 */
	List<String> getRecentUserQueries(String conversationId, Integer limit);

	/**
	 * 保存用户查询记忆
	 * @param conversationId 会话Id
	 * @param messages 用户查询记忆
	 */
	void saveUserQuery(String conversationId, List<UserMessage> messages);

	/**
	 * 根据用户Id和会话Id查询用户短期记忆轨迹
	 * @param userId 用户Id
	 * @param conversationId 会话Id
	 * @return List<Message>
	 */
	List<Message> findMessageTrack(String userId, String conversationId);

	/**
	 * 根据用户Id和会话Id查询用户最近一条记忆
	 * @param userId 用户Id
	 * @param conversationId 会话Id
	 * @return Message
	 */
	Message findLatestExtractMessage(String userId, String conversationId);

	/**
	 * 保存或更新用户短期记忆
	 * @param userId 用户Id
	 * @param conversationId 会话Id
	 * @param messages 用户短期记忆
	 */
	void saveOrUpdate(String userId, String conversationId, List<Message> messages);

	/**
	 * 根据会话Id删除用户短期记忆
	 * @param userId 用户Id
	 * @param conversationId 会话Id
	 */
	void deleteBy(String userId, String conversationId);

}
