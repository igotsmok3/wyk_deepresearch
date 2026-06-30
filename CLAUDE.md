# Project Instructions

## Overview
- DeepResearch is a Spring Boot + Vue 3 application for AI-assisted research workflows built on Spring AI Alibaba Graph.
- The backend orchestrates graph nodes that plan, search, iterate, and report; the frontend is a separate Vite app mounted under `/ui`.

## Tech Stack
- Backend: Java 17, Spring Boot 3.4.8, Spring AI 1.0.0, Spring AI Alibaba 1.0.0.4
- Frontend: Vue 3, TypeScript 5, Vite 5, Pinia, Vue Router, Ant Design Vue
- Optional integrations: Redis, Elasticsearch RAG, OpenTelemetry, MCP clients, multiple search providers

## Build And Run
- Backend dev: `mvn spring-boot:run`
- Backend build: `mvn clean install -DskipTests`
- Backend tests: `mvn test -DskipTests=false`
- Backend style: `mvn checkstyle:check`
- Backend cleanup: `mvn spotless:apply`
- Frontend install: `cd ui-vue3 && npm install`
- Frontend dev: `cd ui-vue3 && npm run dev`
- Frontend build: `cd ui-vue3 && npm run build`
- Frontend unit tests: `cd ui-vue3 && npm run test:unit`
- Frontend lint: `cd ui-vue3 && npm run lint`

## Required Environment
- Required API key: `AI_DASHSCOPE_API_KEY`
- Common search key: `TAVILY_API_KEY`
- Optional keys: `JINA_API_KEY`, `SERPAPI_KEY`, `ALIYUN_AI_SEARCH_API_KEY`, `ALIYUN_AI_SEARCH_BASE_URL`
- Optional export path: `AI_DEEPRESEARCH_EXPORT_PATH`
- Redis password if Redis is enabled: `REDIS-PASSWORD`

## Project Structure
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/DeepResearchApplication.java`: Spring Boot entry point
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/config/DeepResearchConfiguration.java`: graph assembly and node wiring
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/controller/`: HTTP and SSE endpoints
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/node/`: graph node implementations (`NodeAction`)
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/dispatcher/`: graph edge routing (`EdgeAction`)
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/service/`: business services and integration logic
- `src/main/java/com/alibaba/cloud/ai/example/deepresearch/rag/`: RAG retrieval, KB, and post-processing
- `src/main/resources/prompts/`: agent prompt templates
- `ui-vue3/src/main.ts`: Vue app bootstrap
- `ui-vue3/src/router/`: routes and `/ui` history base
- `ui-vue3/src/store/`: Pinia stores
- `ui-vue3/src/views/`: route-level pages
- `ui-vue3/src/services/api/`: frontend API access layer

## Backend Architecture
- The core runtime is a compiled `StateGraph` bean named `deepResearch`.
- `ChatController` compiles the graph with an in-memory checkpoint saver and interrupts before `human_feedback`.
- Nodes write state into `OverAllState`; dispatchers read `*_next_node` keys to choose the next edge.
- Parallel work is configured in `DeepResearchConfiguration` by adding `researcher_N` and `coder_N` nodes dynamically.
- Feature flags in `application.yml` enable or disable major subsystems with `@ConditionalOnProperty`.

## Request Lifecycle
- `POST /chat/stream` enters through `ChatController`.
- `ChatRequestProcess` normalizes defaults and `SearchBeanUtil` validates the selected search engine.
- `GraphProcess` creates a graph/thread ID and starts `compiledGraph.fluxStream(...)`.
- The graph flows through nodes such as `short_user_role_memory`, `coordinator`, `background_investigator`, `planner`, `information`, `parallel_executor`, `research_team`, and `reporter`.
- Results stream back to the client as SSE events.
- Follow-up report retrieval and export use `ReportController`; file ingestion for RAG uses `RagDataController`.

## Frontend Architecture
- The frontend boots from `ui-vue3/src/main.ts`, installs Pinia persistence, Ant Design Vue, i18n, router, and auth guards.
- Routing is declared in `ui-vue3/src/router/defaultRoutes.ts`; major views are chat, knowledge, config, SSE demo, login, and not-found.
- Navigation uses `createWebHistory('/ui')`, so deployments need the `/ui` base path.

## Conventions
- Java uses 4-space indentation, `UpperCamelCase` classes, `lowerCamelCase` methods/fields, and lowercase package names.
- Backend design favors Spring DI with constructor injection in many classes, plus `@Service` and `@RestController` stereotypes.
- Graph-related behavior belongs in `node/` and `dispatcher/`, not in controllers.
- Vue component files use `PascalCase`; stores use `*Store.ts`; route views live under `views/`.
- TypeScript uses ESLint + Prettier; `no-explicit-any` is currently allowed and unused vars are warnings.
- Backend tests follow `*Test.java`; frontend unit tests use Vitest with `jsdom`; Cypress is present for e2e scaffolding.

## Feature Toggles
- `spring.ai.alibaba.deepresearch.smart-agents.enabled`
- `spring.ai.alibaba.deepresearch.mcp.enabled`
- `spring.ai.alibaba.deepresearch.rag.enabled`
- `spring.ai.alibaba.deepresearch.rag.vector-store-type`: `simple` (default) | `elasticsearch` | `milvus` | `milvus-es` (Milvus 向量 + ES BM25 双路并行，应用层 RRF 融合)
- `spring.ai.alibaba.deepresearch.rag.markdown-splitter.enabled`: Markdown 结构感知切分，默认 `true`，关闭后降级到 TokenTextSplitter
- `spring.ai.alibaba.deepresearch.rag.pdf-splitter.enabled`: PDF 结构感知切分（书签模式/启发式模式），默认 `true`，关闭后降级到 TokenTextSplitter
- `spring.data.redis.enabled`
- `spring.ai.alibaba.deepresearch.short-term-memory.enabled`
- `spring.ai.alibaba.deepresearch.reflection.enabled`

## Where To Look
- Add or change API/SSE behavior: `controller/` plus request/response models
- Change graph flow: `config/DeepResearchConfiguration.java`, `node/`, `dispatcher/`
- Adjust prompts: `src/main/resources/prompts/`
- Change RAG ingestion/retrieval: `rag/` and `service/VectorStoreDataIngestionService.java`
- Change chat UI: `ui-vue3/src/views/chat/` and `ui-vue3/src/components/`
- Change app navigation or auth redirect rules: `ui-vue3/src/router/` and `ui-vue3/src/store/`

## Git Conventions
- Recent commits use short conventional messages such as `docs: update` and `fix: add missing frontend dependencies`.
- Merge commits are present; do not assume a squash-only workflow from local history alone.
