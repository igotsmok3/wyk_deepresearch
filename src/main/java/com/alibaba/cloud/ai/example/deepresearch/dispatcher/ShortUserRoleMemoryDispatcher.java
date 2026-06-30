package com.alibaba.cloud.ai.example.deepresearch.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * {@code short_user_role_memory} 节点的条件边路由器，根据用户记忆加载结果决定下一跳节点。
 *
 * <p>
 * 项目职责：dispatcher 层的边路由实现，读取图入口节点 {@code ShortUserRoleMemoryNode} （负责加载用户短期记忆和角色信息）写入的
 * {@code short_user_role_next_node} 键： 正常情况路由至 {@code "coordinator"}
 * 处理用户请求，异常时终止（{@code END}）。
 *
 * <p>
 * 被使用情况：由
 * {@link com.alibaba.cloud.ai.example.deepresearch.config.DeepResearchConfiguration} 通过
 * {@code addConditionalEdges("short_user_role_memory", ...)} 注册到图配置中。
 */
public class ShortUserRoleMemoryDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		// 读取 ShortUserRoleMemoryNode 写入的路由决策，缺省终止图执行
		return (String) state.value("short_user_role_next_node", END);
	}

}
