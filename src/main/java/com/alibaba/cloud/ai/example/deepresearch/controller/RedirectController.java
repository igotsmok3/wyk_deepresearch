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
package com.alibaba.cloud.ai.example.deepresearch.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 前端路由重定向控制器，将根路径及 Vue 应用路由统一转发到前端入口页面。
 *
 * <p>项目职责：controller 层的 SPA 兼容层，负责将 {@code /}、{@code /ui}、
 * {@code /ui/chat/**} 等路径映射到 {@code /ui/index.html}，使前端 Vue Router 的
 * History 模式在页面刷新时不会返回 404。
 *
 * <p>被使用情况：由 Spring 容器直接管理，无其他 Java 类直接引用。
 */
@Controller
public class RedirectController {

	@RequestMapping({ "/", "/ui", "/ui/", "/ui/chat", "/ui/chat/**" })
	public String frontend() {
		return "/ui/index.html";
	}

}
