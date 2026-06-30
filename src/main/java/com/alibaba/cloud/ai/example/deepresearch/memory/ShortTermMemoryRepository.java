package com.alibaba.cloud.ai.example.deepresearch.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 短期用户角色记忆仓储接口，定义用户提问历史和 LLM 抽取的角色画像的存储与查询契约。
 *
 * <p>
 * 项目职责：抽象短期记忆的读写操作，包括用户查询历史的保存与分页读取、 角色画像轨迹的追加与最新记录查询，以及按会话清理记忆； 实现类负责具体的存储策略（内存、Redis
 * 等）。
 *
 * <p>
 * 被使用情况：{@code ShortUserRoleMemoryNode} 通过本接口读写用户查询历史和角色画像；
 * {@code RewriteAndMultiQueryNode} 读取历史提问用于 query 改写；
 * {@code ShortUserRoleMemoryController} 提供清理记忆的 HTTP 接口；
 * {@code DeepResearchConfiguration} 在条件注入时引用本接口。
 *
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
