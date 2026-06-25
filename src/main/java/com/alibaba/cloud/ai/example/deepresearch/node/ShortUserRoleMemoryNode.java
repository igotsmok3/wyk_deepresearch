package com.alibaba.cloud.ai.example.deepresearch.node;

import com.alibaba.cloud.ai.example.deepresearch.config.ShortTermMemoryProperties;
import com.alibaba.cloud.ai.example.deepresearch.memory.ShortTermMemoryRepository;
import com.alibaba.cloud.ai.example.deepresearch.model.dto.memory.ShortUserRoleExtractResult;
import com.alibaba.cloud.ai.example.deepresearch.util.JsonUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.StateUtil;
import com.alibaba.cloud.ai.example.deepresearch.util.TemplateUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Short-term user role memory node
 *
 * @author benym
 */
public class ShortUserRoleMemoryNode implements NodeAction {

	private static final Logger logger = LoggerFactory.getLogger(ShortUserRoleMemoryNode.class);

	private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final String ZONE_ASIA_SHANGHAI = "Asia/Shanghai";

	private static final String USER_ID = "MOCK_USER_ID";

	private final ChatClient shortMemoryAgent;

	private final ShortTermMemoryProperties shortTermMemoryProperties;

	private final ShortTermMemoryRepository shortTermMemoryRepository;

	private final BeanOutputConverter<ShortUserRoleExtractResult> converter;

	public ShortUserRoleMemoryNode(ChatClient shortMemoryAgent, ShortTermMemoryProperties shortTermMemoryProperties,
			ShortTermMemoryRepository shortTermMemoryRepository) {
		this.shortMemoryAgent = shortMemoryAgent;
		this.shortTermMemoryProperties = shortTermMemoryProperties;
		this.shortTermMemoryRepository = shortTermMemoryRepository;
		this.converter = new BeanOutputConverter<>(new ParameterizedTypeReference<>() {
		});
	}

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {
		Map<String, Object> updated = new HashMap<>();
		if (!shortTermMemoryProperties.isEnabled()) {
			updated.put("short_user_role_next_node", "coordinator");
			return updated;
		}
		logger.info("short_user_role_memory node is running.");
		ShortTermMemoryProperties.GuideScope guideScope = shortTermMemoryProperties.getUserRoleMemory().getGuideScope();
		try {
			// 1. 获取最近n轮用户提问
			String historyUserMessages = buildHistoryUserMessages(state);
			// 2. 添加extract prompt消息
			ShortUserRoleExtractResult currentResult = extractShortTermMemory(state, historyUserMessages);
			// 3. 保存或更新短期记忆
			ShortUserRoleExtractResult mergeResult = saveOrUpdateShortTermMemory(state, currentResult);
			logger.info("generated short user role memory: {}", JsonUtil.toJson(mergeResult));
			if (guideScope.equals(ShortTermMemoryProperties.GuideScope.NONE)) {
				updated.put("short_user_role_next_node", "coordinator");
				return updated;
			}
			if (StringUtils.hasText(historyUserMessages)
					&& guideScope.equals(ShortTermMemoryProperties.GuideScope.ONCE)) {
				updated.put("short_user_role_memory", "");
				updated.put("short_user_role_next_node", "coordinator");
				return updated;
			}
			updated.put("short_user_role_memory", JsonUtil.toJson(mergeResult));
			updated.put("short_user_role_next_node", "coordinator");
		}
		catch (Exception e) {
			logger.error("short user role memory extraction failed, conversationId: {}", StateUtil.getSessionId(state),
					e);
			updated.put("short_user_role_next_node", "coordinator");
		}
		return updated;
	}

	/**
	 * 构建历史用户消息
	 * @param state state
	 * @return String
	 */
	private String buildHistoryUserMessages(OverAllState state) {
		List<String> recentUserQueries = shortTermMemoryRepository.getRecentUserQueries(StateUtil.getSessionId(state),
				shortTermMemoryProperties.getUserRoleMemory().getHistoryUserMessagesNum());
		if (CollectionUtils.isEmpty(recentUserQueries)) {
			saveUserQuery(state);
			return "";
		}
		StringBuilder historyUserMessages = new StringBuilder();
		for (int i = 0; i < recentUserQueries.size(); i++) {
			String userMessage = String.format("第%s轮, 用户消息:%s\n", i + 1, recentUserQueries.get(i));
			historyUserMessages.append(userMessage);
		}
		saveUserQuery(state);
		return historyUserMessages.toString();
	}

	/**
	 * 保存用户提问到短期记忆库
	 * @param state state
	 */
	private void saveUserQuery(OverAllState state) {
		Map<String, Object> metaData = new HashMap<>();
		metaData.put("create_time", LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)));
		UserMessage userMessage = UserMessage.builder().text(StateUtil.getQuery(state)).metadata(metaData).build();
		shortTermMemoryRepository.saveUserQuery(StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singletonList(userMessage)));
	}

	/**
	 * 提取用户角色短期记忆
	 * @param state state
	 * @param historyUserMessages 历史用户提问
	 * @return ShortUserRoleExtractResult
	 * @throws IOException IOException
	 */
	private ShortUserRoleExtractResult extractShortTermMemory(OverAllState state, String historyUserMessages)
			throws IOException {
		List<Message> messages = Collections
			.singletonList(TemplateUtil.getShortMemoryExtractMessage(StateUtil.getQuery(state), historyUserMessages));
		logger.debug("extract messages: {}", messages);
		ChatResponse chatResponse = callShortMemoryAgent(messages);
		String text = chatResponse.getResult().getOutput().getText();
		assert text != null;
		ShortUserRoleExtractResult result = converter.convert(text);
		assert result != null;
		fillResult(state, result);
		return result;
	}

	/**
	 * 调用短期记忆Agent
	 * @param messages 系统消息列表
	 * @return ChatResponse
	 */
	private ChatResponse callShortMemoryAgent(List<Message> messages) {
		return shortMemoryAgent.prompt(converter.getFormat()).messages(messages).call().chatResponse();
	}

	/**
	 * 填充结果对象
	 * @param state state
	 * @param result 抽取结果对象
	 */
	private void fillResult(OverAllState state, ShortUserRoleExtractResult result) {
		result.setUserId(USER_ID);
		result.setUserQuery(StateUtil.getQuery(state));
		result.setConversationId(StateUtil.getSessionId(state));
		result.setCreatTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
	}

	/**
	 * 保存或更新短期记忆
	 * @param state state
	 * @param currentResult 当前提取结果
	 * @return 融合后结果
	 * @throws IOException IOException
	 */
	private ShortUserRoleExtractResult saveOrUpdateShortTermMemory(OverAllState state,
			ShortUserRoleExtractResult currentResult) throws IOException {
		Message historyShortResult = shortTermMemoryRepository.findLatestExtractMessage(USER_ID,
				StateUtil.getSessionId(state));
		// 如果没有历史用户角色记忆，直接保存当前结果
		if (historyShortResult == null) {
			SystemMessage newShortMemory = new SystemMessage(JsonUtil.toJson(currentResult));
			shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
					new ArrayList<>(Collections.singleton(newShortMemory)));
			return currentResult;
		}
		ShortUserRoleExtractResult latestExtract = converter.convert(historyShortResult.getText());
		Double latestConfidence = Objects.requireNonNull(latestExtract).getConversationAnalysis().getConfidenceScore();
		Double currentConfidence = currentResult.getConversationAnalysis().getConfidenceScore();
		// 如果当前结果的置信度>=，融合历史用户角色信息后更新短期记忆，是否真正融合需要由LLM结合历史判定
		// 如当前轮用户进行角色扮演，但结合历史看，这不是用户的核心角色信息
		if (currentConfidence >= latestConfidence) {
			return mergeAndUpdateShortTermMemory(state, currentResult, latestExtract);
		}
		// 否则，保持历史短期记忆不变，仅更新交互次数和更新时间
		latestExtract.getConversationAnalysis()
			.setInteractionCount(latestExtract.getConversationAnalysis().getInteractionCount() + 1);
		latestExtract.setUpdateTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
		SystemMessage newShortMemory = new SystemMessage(JsonUtil.toJson(latestExtract));
		shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singleton(newShortMemory)));
		// 返回当前语句的提取结果，指令跟随用户最新的输入
		return currentResult;
	}

	/**
	 * 合并并更新短期记忆
	 * @param state state
	 * @param current 当前提取接过
	 * @param latest 最近一次提取结果
	 * @return ShortUserRoleExtractResult
	 * @throws IOException IOException
	 */
	private ShortUserRoleExtractResult mergeAndUpdateShortTermMemory(OverAllState state,
			ShortUserRoleExtractResult current, ShortUserRoleExtractResult latest) throws IOException {
		List<Message> messageTrack = shortTermMemoryRepository.findMessageTrack(USER_ID, StateUtil.getSessionId(state));
		List<ShortUserRoleExtractResult> historyTracks = new ArrayList<>();
		if (!CollectionUtils.isEmpty(messageTrack)) {
			messageTrack.stream().map(message -> converter.convert(message.getText())).forEach(historyTracks::add);
		}
		// 组装update prompt消息
		List<Message> updateMessages = Collections.singletonList(TemplateUtil.getShortMemoryUpdateMessage(current,
				latest, historyTracks, shortTermMemoryProperties.getUserRoleMemory().getUpdateSimilarityThreshold()));
		ChatResponse updateResponse = callShortMemoryAgent(updateMessages);
		String updateText = updateResponse.getResult().getOutput().getText();
		assert updateText != null;
		ShortUserRoleExtractResult mergedResult = converter.convert(updateText);
		assert mergedResult != null;
		mergedResult.setUserQuery(StateUtil.getQuery(state));
		mergedResult.setUpdateTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
		SystemMessage mergedMemory = new SystemMessage(JsonUtil.toJson(mergedResult));
		shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singleton(mergedMemory)));
		return mergedResult;
	}

}
