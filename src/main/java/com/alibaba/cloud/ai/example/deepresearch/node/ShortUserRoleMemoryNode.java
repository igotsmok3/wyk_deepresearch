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
 * 用户角色短期记忆节点：整个图的入口节点，负责提取并维护用户画像，为后续节点的提示词注入个性化上下文。
 *
 * <p>
 * 项目职责：位于 START 之后，coordinator 之前（{@code START → short_user_role_memory → coordinator}）。
 * 每次请求时依次执行：
 * <ol>
 * <li>从 {@code ShortTermMemoryRepository} 取最近 N 条历史提问，构建 LLM 上下文</li>
 * <li>调用 shortMemoryAgent 提取当前提问中的用户职业/偏好/置信度等结构化画像
 * （{@code ShortUserRoleExtractResult}）</li>
 * <li>与历史画像对比置信度：新置信度更高时调用 LLM 合并两次画像；否则保留历史画像仅更新计数</li>
 * </ol>
 * 写入 OverAllState：
 * <ul>
 * <li>{@code short_user_role_memory}：画像 JSON（根据 guideScope 配置决定是否写入）</li>
 * <li>{@code short_user_role_next_node}：路由键，固定为 coordinator</li>
 * </ul>
 *
 * <p>
 * 被使用情况：由 {@code DeepResearchConfiguration} 以节点名 {@code short_user_role_memory} 注册到图中；
 * {@code ShortUserRoleMemoryDispatcher} 读取 {@code short_user_role_next_node} 进行边路由；
 * {@code TemplateUtil#addShortUserRoleMemory} 从 OverAllState 读取画像 JSON 并注入后续节点的提示词；
 * {@code ShortTermMemoryProperties} 控制功能开关和 guideScope 模式。
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
			// 功能未开启，直接跳转到 coordinator
			updated.put("short_user_role_next_node", "coordinator");
			return updated;
		}
		logger.info("short_user_role_memory node is running.");
		// guideScope 控制画像是否注入后续节点的提示词：NONE=不注入, ONCE=仅第一轮注入, EVERY=每轮注入
		ShortTermMemoryProperties.GuideScope guideScope = shortTermMemoryProperties.getUserRoleMemory().getGuideScope();
		try {
			// 步骤1：取最近 N 条历史提问（同时把本轮提问保存进 userQueryMemory）
			String historyUserMessages = buildHistoryUserMessages(state);
			// 步骤2：调用 LLM 提取当前画像（职业、偏好、置信度等）
			ShortUserRoleExtractResult currentResult = extractShortTermMemory(state, historyUserMessages);
			// 步骤3：与历史画像对比置信度，决定是否合并，然后保存
			ShortUserRoleExtractResult mergeResult = saveOrUpdateShortTermMemory(state, currentResult);
			logger.info("generated short user role memory: {}", JsonUtil.toJson(mergeResult));

			if (guideScope.equals(ShortTermMemoryProperties.GuideScope.NONE)) {
				// 提取但不注入提示词，画像只存库不传给下游节点
				updated.put("short_user_role_next_node", "coordinator");
				return updated;
			}
			if (StringUtils.hasText(historyUserMessages)
					&& guideScope.equals(ShortTermMemoryProperties.GuideScope.ONCE)) {
				// ONCE 模式：有历史消息说明不是第一轮，清空注入内容，跳过画像注入
				updated.put("short_user_role_memory", "");
				updated.put("short_user_role_next_node", "coordinator");
				return updated;
			}
			// EVERY 模式（或 ONCE 的第一轮）：将画像 JSON 写入 OverAllState，后续节点从 state 读取并注入提示词
			updated.put("short_user_role_memory", JsonUtil.toJson(mergeResult));
			updated.put("short_user_role_next_node", "coordinator");
		}
		catch (Exception e) {
			logger.error("short user role memory extraction failed, conversationId: {}", StateUtil.getSessionId(state),
					e);
			// 失败时不中断图流程，直接跳到 coordinator
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
	/**
	 * 核心置信度比较逻辑：决定是"合并更新"还是"只更新计数"。
	 * <p>
	 * 设计意图：防止用户偶尔一句角色扮演的话覆盖掉积累的真实画像。 置信度由 LLM 在提取时打分，反映这条信息能多确定地代表用户真实身份。 只有新提取的置信度 >=
	 * 历史置信度时，才触发 LLM 合并，否则保留历史画像原样。
	 */
	private ShortUserRoleExtractResult saveOrUpdateShortTermMemory(OverAllState state,
			ShortUserRoleExtractResult currentResult) throws IOException {
		Message historyShortResult = shortTermMemoryRepository.findLatestExtractMessage(USER_ID,
				StateUtil.getSessionId(state));
		// 第一次请求，没有历史画像，直接保存
		if (historyShortResult == null) {
			SystemMessage newShortMemory = new SystemMessage(JsonUtil.toJson(currentResult));
			shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
					new ArrayList<>(Collections.singleton(newShortMemory)));
			return currentResult;
		}
		ShortUserRoleExtractResult latestExtract = converter.convert(historyShortResult.getText());
		Double latestConfidence = Objects.requireNonNull(latestExtract).getConversationAnalysis().getConfidenceScore();
		Double currentConfidence = currentResult.getConversationAnalysis().getConfidenceScore();
		// 当前置信度更高（信息更确定）→ 交给 LLM 合并两次画像
		// 例：用户一直在问 Java 技术问题（高置信），偶尔说"我来扮演一个农民"（低置信），不会覆盖
		if (currentConfidence >= latestConfidence) {
			return mergeAndUpdateShortTermMemory(state, currentResult, latestExtract);
		}
		// 当前置信度较低 → 保留历史画像，只更新交互次数和时间戳
		latestExtract.getConversationAnalysis()
			.setInteractionCount(latestExtract.getConversationAnalysis().getInteractionCount() + 1);
		latestExtract.setUpdateTime(LocalDateTime.now(ZoneId.of(ZONE_ASIA_SHANGHAI)).format(DATE_TIME_FORMATTER));
		SystemMessage newShortMemory = new SystemMessage(JsonUtil.toJson(latestExtract));
		shortTermMemoryRepository.saveOrUpdate(USER_ID, StateUtil.getSessionId(state),
				new ArrayList<>(Collections.singleton(newShortMemory)));
		// 注意：返回本轮提取结果而非历史结果，下游节点用的是最新一句话的意图
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
