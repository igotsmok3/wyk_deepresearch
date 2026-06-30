/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.deepresearch.model.req;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * 图任务唯一标识，由会话 ID 和线程 ID 联合组成，用于在并发场景中定位特定的图执行实例。
 *
 * <p>
 * 项目职责：作为图任务的复合键，在 {@code GraphProcess} 的任务 Map 中作索引， 同时在各节点、会话历史存储中传递，关联一次完整的研究会话与线程。
 *
 * <p>
 * 被使用情况：{@code GraphProcess} 以该记录为 Map 键管理任务 Future； {@code ChatController} 创建并传递
 * GraphId；{@code ReporterNode} 构建 GraphId 并存储会话历史； {@code SessionHistory} 持有 GraphId
 * 标识所属会话；测试类 {@code GraphProcessExceptionHandlingTest} 使用该记录验证图任务异常处理逻辑。
 *
 * @author vlsmb
 * @since 2025/8/6
 */
public record GraphId(@JsonProperty("session_id") String sessionId,
		@JsonProperty("thread_id") String threadId) implements Serializable {
}
