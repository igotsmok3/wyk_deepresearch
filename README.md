# DeepResearch

[中文](README_zh.md) | [English](README.md) 

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-blueviolet.svg)](https://spring.io/projects/spring-ai)

## 📖 Introduction

**DeepResearch** is an intelligent research Agent built on **Spring AI Alibaba Graph**, designed to tackle complex research tasks. It adopts a **Multi-Agent** collaborative pattern, supporting dynamic task planning and execution. The system integrates multi-source online search and Hybrid RAG technology, combined with Secure Sandbox for Python code execution, enabling efficient data analysis. Through Reflection, HITL, and Self-evolution Memory, the Agent can continuously self-optimize, ultimately outputting high-quality research reports with deep insights.

## ✨ Technical Features

- 📋**Plan and Execute**: Dynamic planning and automatic execution for complex problems
- 🤖**Multi Agent**: Multi-agent collaboration (e.g., Researcher, Coder)
- 🌐**Online Search**: Integrated multi-source search services including Tavily, Jina, Aliyun AI Search
- 📖**Hybrid RAG**: Combines vector and keyword retrieval for comprehensive information acquisition
- 🔄**Reflection**: Agent self-reflection for continuous output quality optimization
- 🚶‍♂️**HITL**: Human-in-the-loop feedback for enhanced controllability
- 🧬**Self-evolution Memory**: Self-evolving memory structure and user role memory based on interaction feedback
- 🖇️**MCP Allocation**: Support for MCP allocation in multi-agent scenarios
- 🔒**Secure Sandbox**: Secure Python code execution in Docker sandbox environment
- 📊**Report Generation**: Supports HTML report preview, Markdown, PDF and other report formats

## 🎋 Project Architecture

```
DeepResearch/
├──  ├── src/
│    ├── agents                          # Multi-Agent initialization, MCP allocation, observability initialization
│    ├── config                          # Graph construction, project Config classes
│    ├── controller                      # HTTP endpoints
│    ├── dispatcher                      # Graph EdgeAction
│    ├── model                           # Base project entities
│    ├── node                            # Graph key node definitions
│    ├── rag                             # RAG core implementation
│    ├── repository                      # Model configuration loading
│    ├── serializer                      # Message serialization implementation
│    ├── service                         # Business logic implementation
│    ├── tool                            # Agent Tool definitions
│    ├── util                            # Project utilities
│    └── DeepResearchApplication         # Application entry point
├──  ├── resource/                  
│    ├── prompts                         # Core prompts
│	 ├── mcp-config.json                 # Agent MCP configuration
│    ├── model-config.json               # Multi-Agent model configuration
├──  └── website-weight-config.json      # Search engine weight configuration
```

## 🧩 System Architecture

![](imgs/deepresearch-architecture.gif)

[More illustrations](docs/ARCHITECTURE.md)

## 🔍 Running Example

[Demo Video](https://yingziimage.oss-cn-beijing.aliyuncs.com/video/deep_research.mov)

![](https://yingziimage.oss-cn-beijing.aliyuncs.com/img/image-20251001121713795.png)

![](./imgs/deepresearch-system.png)

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+
- DashScope API Key

### 1. Clone and Build

```bash
git clone https://github.com/spring-ai-alibaba/deepresearch.git
cd deepresearch
mvn clean install -DskipTests
```

### 2. Configure API Key
```bash
export AI_DASHSCOPE_API_KEY=your-api-key-here
```

### 3. Start Application

#### Start from Project
**Backend:**

```bash
cd deepresearch
mvn spring-boot:run
```
**Frontend:**

```bash
cd ui-vue3
pnpm install
npm run dev
```

#### Docker Startup
- Build the Docker image from the project directory. This may take ~5 minutes depending on network speed.
```shell
cd deepresearch
docker build -t alibaba-deepresearch:v1.0 .
```
- After building, run the container and set environment variables:
```shell
docker run -d \
  --name alibaba-deepresearch \
  -e AI_DASHSCOPE_API_KEY="your_key_here" \
  -e TAVILY_API_KEY="your_key_here" \
#  -e JINA_API_KEY="your_key_here" \ optional
  -p 8080:8080 \
  alibaba-deepresearch:v1.0
```
- Alternatively, use docker-compose to start Redis, ElasticSearch, and the app:
```shell
  docker-compose up
```
> 💡**Note**:
> - Set API keys in the `.env` file
> - Config files are under `dockerConfig`; you can also set keys and related configs there

### 4. Configuration

- [API KEY](docs/FULL_CONFIG.md#api-key)
- [Search Services](docs/FULL_CONFIG.md#search-services-default-tavily)
- [Storage Options](docs/FULL_CONFIG.md#storage-options-default-in-memory)
- [Coding Node](docs/FULL_CONFIG.md#coding-node-programming-capability-for-llm)
- [RAG](docs/FULL_CONFIG.md#rag)
- [MCP Service (WIP)](docs/FULL_CONFIG.md#mcp-services-wip)
- [Short Term Memory](docs/FULL_CONFIG.md#short-term-memory)

### 5. Debug and Observability

Supports integration with Langfuse observability system. See [documentation](docs/FULL_CONFIG.md#observability) for configuration details.

### Related API Documentation

- [DashScope (Alibaba Bailian)](https://bailian.console.aliyun.com)
- [Tavily API Docs](https://docs.tavily.com/documentation/api-reference/endpoint/search)
- [Jina API Docs](https://jina.ai/reader)
- [AMap MCP Docs](https://lbs.amap.com/api/mcp-server/gettingstarted#t1)

## Test Cases

See [DeepResearch.http](DeepResearch.http) for sample requests.

```curl
curl --location 'http://localhost:8080/chat/stream' \
--header 'Content-Type: application/json' \
--data '{
    "thread_id": "__default_",
    "enable_deepresearch": false,
    "query": "Please analyze the reasons for the explosive popularity of Pop Mart",
    "max_step_num": 2,
    "auto_accepted_plan": true
}'
```

## 📚 Reference Documentation

- [Full Configuration Reference](docs/FULL_CONFIG.md#configuration)
- [Spring AI Alibaba Documentation](https://github.com/alibaba/spring-ai-alibaba)

## 🤝 Join Community & Contributing

Contributions are welcome! Please refer to [CONTRIBUTING](CONTRIBUTING.md) for guidelines.

<div align="center">
    <img src="./imgs/qrcode.png" alt="community"/>
</div>

## 📄 License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Contributors

Thanks to the following contributors for improving this project (unordered):

[yingzi](https://github.com/GTyingzi)、[zhouyou](https://github.com/zhouyou9505)、[NOBODY](https://github.com/SCMRCORE)、[xiaohai-78](https://github.com/xiaohai-78)、[VLSMB](https://github.com/VLSMB)、[disaster1-tesk](https://github.com/disaster1-tesk)、[Allen Hu](https://github.com/big-mouth-cn)、[Makoto](https://github.com/zxuexingzhijie)、[sixiyida](https://github.com/sixiyida)、[Gfangxin](https://github.com/Gfangxin)、[AliciaHu](https://github.com/AliciaHu)、[swl](https://github.com/hbsjz-swl)、[huangzhen](https://github.com/james-huangzhen)、[Tfh-Yqf](https://github.com/Tfh-Yqf)、[anyin-xyz](https://github.com/anyin-xyz)、[zhou youkang](https://github.com/mengnankkkk)、[supermonkeyguys](https://github.com/supermonkeyguys)、[yuluo-yx](https://github.com/yuluo-yx)、[Ken Liu](https://github.com/chickenlj)、[co63ox](https://github.com/co63oc)、[benym](https://github.com/benym)

---

<div align="center">
    Made with ❤️ by Spring AI Alibaba DeepResearch Team
</div>