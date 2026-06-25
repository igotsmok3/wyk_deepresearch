package com.alibaba.cloud.ai.example.deepresearch.dispatcher;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.EdgeAction;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * Short-term user role memory dispatcher
 *
 * @author benym
 */
public class ShortUserRoleMemoryDispatcher implements EdgeAction {

	@Override
	public String apply(OverAllState state) throws Exception {
		return (String) state.value("short_user_role_next_node", END);
	}

}
