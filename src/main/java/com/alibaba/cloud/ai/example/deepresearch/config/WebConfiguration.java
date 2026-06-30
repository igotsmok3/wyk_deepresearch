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

package com.alibaba.cloud.ai.example.deepresearch.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Web 全局配置类，负责注册跨域过滤器以允许前端应用跨域访问后端接口。
 *
 * <p>
 * 项目职责：属于配置层，配置 CORS（跨域资源共享）过滤器， 允许所有来源、所有请求头和所有 HTTP 方法，解决前端 Vue 应用（默认运行在不同端口）与后端 API
 * 之间的跨域问题。
 *
 * <p>
 * 被使用情况：由 Spring 容器直接管理，无其他类直接引用，由 Spring MVC 框架自动应用到所有请求路径。
 */
@Configuration
public class WebConfiguration {

	@Bean
	public FilterRegistrationBean<CorsFilter> corsFilter() {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowCredentials(false);
		configuration.addAllowedOrigin("*");
		configuration.addAllowedHeader("*");
		configuration.addAllowedMethod("*");

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return new FilterRegistrationBean<>(new CorsFilter(source));
	}

}
