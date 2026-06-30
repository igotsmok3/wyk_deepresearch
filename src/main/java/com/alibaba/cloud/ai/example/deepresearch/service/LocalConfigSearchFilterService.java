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

package com.alibaba.cloud.ai.example.deepresearch.service;

import com.alibaba.cloud.ai.example.deepresearch.util.SearchBeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于本地 JSON 配置文件的搜索结果过滤服务，从 {@code website-weight-config.json} 中加载站点权重。
 *
 * <p>
 * 项目职责：继承 {@link SearchFilterService} 抽象类，实现 {@code loadWebsiteWeight()} 方法， 从 classpath
 * 下的 {@code website-weight-config.json} 解析域名→权重映射表，并做边界值校验（权重 超出 [-1.0, 1.0]
 * 范围时截断并告警）。权重正值表示信任，负值表示不信任，0 为中性。
 *
 * <p>
 * 被使用情况：作为 {@code SearchFilterService} 的默认实现注册为 Spring Bean， 被
 * {@code ResearcherNode}、{@code BackgroundInvestigationNode} 以及 {@code SearchFilterTool}
 * 注入，用于搜索结果的排序与过滤；亦被 {@code SearchInfoService} 间接使用。
 *
 * @author vlsmb
 * @since 2025/7/10
 */
@Service
public class LocalConfigSearchFilterService extends SearchFilterService {

	private static final Logger log = LoggerFactory.getLogger(LocalConfigSearchFilterService.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	public LocalConfigSearchFilterService(SearchBeanUtil searchBeanUtil) {
		super(searchBeanUtil);
	}

	@Override
	protected Map<String, Double> loadWebsiteWeight() {
		ClassPathResource resource = new ClassPathResource("website-weight-config.json");
		Map<String, Double> map = new HashMap<>();
		try (InputStream stream = resource.getInputStream()) {
			List<WebsiteConfig> configs = objectMapper.readValue(stream,
					objectMapper.getTypeFactory().constructCollectionType(List.class, WebsiteConfig.class));
			configs.forEach(config -> {
				if (config.weight() > 1.0) {
					log.warn("The weight field value for host '{}' is {}, exceeding the maximum weight limit.",
							config.host(), config.weight());
					map.put(config.host(), 1.0);
				}
				else if (config.weight() < -1.0) {
					log.warn("The weight field value for host '{}' is {}, exceeding the minimum weight limit.",
							config.host(), config.weight());
					map.put(config.host(), -1.0);
				}
				else {
					map.put(config.host(), config.weight());
				}
			});
		}
		catch (Exception e) {
			log.warn("Failed to read website weight configuration file: {}", e.getMessage());
			return Map.of();
		}
		return map;
	}

	private record WebsiteConfig(String host, Double weight) {

	}

}
