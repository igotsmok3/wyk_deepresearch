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

package com.alibaba.cloud.ai.example.deepresearch.config.rag;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = { "spring.ai.alibaba.deepresearch.rag.mineru.enabled=true",
		"spring.ai.alibaba.deepresearch.rag.mineru.api-token=test-token",
		"spring.ai.alibaba.deepresearch.rag.mineru.model-version=vlm",
		"spring.ai.alibaba.deepresearch.rag.mineru.polling-interval-ms=3000",
		"spring.ai.alibaba.deepresearch.rag.mineru.max-polling-attempts=10" })
class MinerUPropertiesTest {

	@Autowired
	RagProperties ragProperties;

	@Test
	void minerUPropertiesBindCorrectly() {
		var mineru = ragProperties.getMinerU();
		assertThat(mineru.isEnabled()).isTrue();
		assertThat(mineru.getApiToken()).isEqualTo("test-token");
		assertThat(mineru.getModelVersion()).isEqualTo("vlm");
		assertThat(mineru.getPollingIntervalMs()).isEqualTo(3000L);
		assertThat(mineru.getMaxPollingAttempts()).isEqualTo(10);
	}

	@Test
	void defaultValues() {
		var mineru = new RagProperties.MinerU();
		assertThat(mineru.isEnabled()).isFalse();
		assertThat(mineru.getApiBaseUrl()).isEqualTo("https://mineru.net");
		assertThat(mineru.getModelVersion()).isEqualTo("pipeline");
		assertThat(mineru.isEnableFormula()).isTrue();
		assertThat(mineru.isEnableTable()).isTrue();
		assertThat(mineru.getLanguage()).isEqualTo("ch");
		assertThat(mineru.getPollingIntervalMs()).isEqualTo(5000L);
		assertThat(mineru.getMaxPollingAttempts()).isEqualTo(72);
	}

}
