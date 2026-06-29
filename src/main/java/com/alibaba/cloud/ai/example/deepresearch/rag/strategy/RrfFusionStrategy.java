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
package com.alibaba.cloud.ai.example.deepresearch.rag.strategy;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于 RRF（Reciprocal Rank Fusion）算法的融合与重排策略，公式为 score(doc) = Σ 1/(k + rank_i)。
 *
 * <p>项目职责：同时实现 FusionStrategy 和 DocumentPostProcessor 两个接口：
 * 作为 FusionStrategy 时将多路检索结果列表融合为单一排序列表；
 * 作为 DocumentPostProcessor 时按 source_type 分组后对单次检索结果执行 rerank，
 * 两者均支持 topK 截断和相似度阈值过滤。
 *
 * <p>被使用情况：DefaultHybridRagProcessor 在 rerankEnabled=true 时注入并在 postProcess 阶段调用；
 * RagNodeService 将本类作为 FusionStrategy 注入到 RagNode 中用于多策略结果融合。
 *
 * @author hupei
 */
@Component
@ConditionalOnProperty(prefix = "spring.ai.alibaba.deepresearch.rag", name = "enabled", havingValue = "true")
public class RrfFusionStrategy implements FusionStrategy, DocumentPostProcessor {

	private final int k;

	private final int defaultTopK;

	private final double defaultThreshold;

	public RrfFusionStrategy(@Value("${rag.fusion.rrf.k-constant:60}") int k,
			@Value("${rag.pipeline.rerankTopK:10}") int defaultTopK,
			@Value("${rag.pipeline.rerankThreshold:0.1}") double defaultThreshold) {
		this.k = k;
		this.defaultTopK = defaultTopK;
		this.defaultThreshold = defaultThreshold;
	}

	@Override
	public String getStrategyName() {
		return "rrf";
	}

	@Override
	public List<Document> fuse(List<List<Document>> results) {
		if (results == null || results.isEmpty()) {
			return List.of();
		}
		if (results.size() == 1) {
			return results.get(0); // 如果只有一个结果列表，无需融合
		}

		return fuseInternal(results, defaultTopK, defaultThreshold);
	}

	@Override
	public List<Document> process(Query query, List<Document> documents) {
		// 将单个文档列表视为多个来源的结果进行rerank
		// 按source_type分组，然后使用RRF进行融合
		Map<String, List<Document>> documentsBySource = groupDocumentsBySource(documents);

		// 转换为List<List<Document>>格式
		List<List<Document>> results = new ArrayList<>(documentsBySource.values());

		// 使用RRF融合并应用topK和threshold限制
		return fuseInternal(results, defaultTopK, defaultThreshold);
	}

	/**
	 * 内部融合核心逻辑：
	 * 1. 遍历每个结果列表，按排名计算每个文档的 RRF 累加分
	 * 2. 按分数降序排列，过滤低于 threshold 的文档，截取 topK 条返回
	 */
	private List<Document> fuseInternal(List<List<Document>> results, int topK, double threshold) {
		if (results == null || results.isEmpty()) {
			return List.of();
		}
		if (results.size() == 1) {
			// 只有一个来源不需要融合，直接 topK 截断
			List<Document> singleResult = results.get(0);
			return singleResult.stream().limit(topK).collect(Collectors.toList());
		}

		Map<String, Double> rrfScores = new HashMap<>();
		Map<String, Document> documentMap = new HashMap<>();

		for (List<Document> resultList : results) {
			for (int i = 0; i < resultList.size(); i++) {
				Document doc = resultList.get(i);
				int rank = i + 1; // 排名从1开始
				String docId = getDocumentId(doc);

				// RRF 公式：score += 1/(k + rank)，同一文档在多个列表中出现时累加
				rrfScores.merge(docId, 1.0 / (k + rank), Double::sum);
				documentMap.putIfAbsent(docId, doc);
			}
		}

		return rrfScores.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed())
			.filter(entry -> entry.getValue() >= threshold) // 过滤相关性过低的文档
			.limit(topK)
			.map(entry -> documentMap.get(entry.getKey()))
			.collect(Collectors.toList());
	}

	/**
	 * 按来源分组文档
	 */
	private Map<String, List<Document>> groupDocumentsBySource(List<Document> documents) {
		Map<String, List<Document>> groups = new LinkedHashMap<>();

		for (Document doc : documents) {
			String source = getDocumentSource(doc);
			groups.computeIfAbsent(source, k -> new ArrayList<>()).add(doc);
		}

		return groups;
	}

	/**
	 * 获取文档ID，优先使用文档自身ID，否则使用内容hash
	 */
	private String getDocumentId(Document document) {
		if (document.getId() != null && !document.getId().isEmpty()) {
			return document.getId();
		}

		// 使用内容hash作为ID
		return String.valueOf(document.getText().hashCode());
	}

	/**
	 * 获取文档来源，从元数据中读取source_type
	 */
	private String getDocumentSource(Document document) {
		Object sourceType = document.getMetadata().get("source_type");
		if (sourceType != null) {
			return sourceType.toString();
		}

		// 默认来源
		return "default";
	}

}
