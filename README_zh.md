# DeepResearch

[中文](README_zh.md) | [English](README.md) 

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0-blueviolet.svg)](https://spring.io/projects/spring-ai)

## 📖 介绍

**DeepResearch**是基于**Spring AI Alibaba Graph**构建的智能研究Agent，旨在攻克复杂研究任务。它采用**Multi-Agent**协作模式，支持动态任务规划与执行。系统集成了多源在线搜索与Hybrid RAG技术，配合Secure Sandbox执行Python代码，实现高效的数据分析。通过Reflection、HITL及Self-evolution Memory，Agent能持续自我优化，最终输出高质量的研究报告，提供深度洞察。

## ✨ Technical Features

- 📋**Plan and Execute**: 复杂问题的动态规划与自动执行
- 🤖**Multi Agent**: 多智能体（如Researcher, Coder）协同作业
- 🌐**Online Search**: 集成Tavily、Jina、阿里云 AI Search等多源搜索服务
- 📖**Hybrid RAG**: 结合向量与关键词检索，实现全面信息获取
- 🔄**Reflection**: 智能体自主反思，持续优化输出质量
- 🚶‍♂️**HITL**: 支持人机交互反馈，增强可控性
- 🧬**Self-evolution Memory**: 基于交互反馈的记忆结构与内容自进化用户角色记忆
- 🖇️**MCP Allocation**: 支持多智能体场景下的MCP分配
- 🔒**Secure Sandbox**: Docker沙箱环境下的安全Python代码执行
- 📊**Report Generation**: 支持HTML报告预览，Markdown、PDF等多种格式的报告生成

## 🎋 项目架构

```
DeepResearch/
├──  ├── src/
│    ├── agents                          # 多Agent初始化，MCP分配，可观测初始化
│    ├── config                          # Graph图构建，项目Config配置类
│    ├── controller                      # Http接口端点
│    ├── dispatcher                      # Graph EdgeAction
│    ├── model                           # 基础项目实体
│    ├── node                            # Graph关键node定义
│    ├── rag                             # RAG核心实现
│    ├── repository                      # 模型配置加载
│    ├── serializer                      # 消息序列化实现
│    ├── service                         # 业务代码实现
│    ├── tool                            # Agent Tool定义
│    ├── util                            # 项目util
│    └── DeepResearchApplication         # 启动类
├──  ├── resource/                  
│    ├── prompts                         # 核心prompt
│	 ├── mcp-config.json                 # Agent Mcp配置
│    ├── model-config.json               # 多Agent模型配置
├──  └── website-weight-config.json      # 搜索引擎权重配置
```

## 🧩 系统架构

![](imgs/deepresearch-architecture-zh.gif)

[更多图例](docs/ARCHITECTURE-zh.md)

## 🔍 运行示例

[演示视频](https://yingziimage.oss-cn-beijing.aliyuncs.com/video/deep_research.mov)

![](https://yingziimage.oss-cn-beijing.aliyuncs.com/img/image-20251001121713795.png)

![](./imgs/deepresearch-system.png)

## 🚀 快速开始

### Prerequisites

- Java 17+
- Maven 3.6+
- DashScope API Key

### 1. 克隆并构建

```bash
git clone https://github.com/spring-ai-alibaba/deepresearch.git
cd deepresearch
mvn clean install -DskipTests
```

### 2. 配置API Key
```bash
export AI_DASHSCOPE_API_KEY=your-api-key-here
```

### 3. 启动应用

#### 从项目启动
**后端:**

```bash
cd deepresearch
mvn spring-boot:run
```
**前端**:

```bash
cd ui-vue3
pnpm install
npm run dev
```
#### Docker版启动
- 在deepresearch项目工程目录下执行构建命令，构建docker镜像大约要花费5分钟左右
```shell
cd deepresearch
docker build -t alibaba-deepresearch:v1.0
```
- 构建完成后，执行docker run命令启动镜像，设置环境变量
```shell
docker run -d \
  --name alibaba-deepresearch \
  -e AI_DASHSCOPE_API_KEY="your_key_here" \
  -e TAVILY_API_KEY="your_key_here" \
#  -e JINA_API_KEY="your_key_here" \ 选填
  -p 8080:8080 \
  alibaba-deepresearch:v1.0
```
- 或者使用docker-compose up命令启动,当前容器包括Redis，ElasticSearch, DeepResearch App.
```shell
  docker-compose up
```
> 💡**注意**：
> - .env文件中设置api-key信息
> - dockerConfig目录下有对应应用的配置文件，也可在配置文件中设置key及相关配置信息

### 4. 配置项

- [API KEY](docs/FULL_CONFIG-zh.md#api-key)
- [Search Services](docs/FULL_CONFIG-zh.md#搜索服务默认tavily)
- [Storage Options](docs/FULL_CONFIG-zh.md#存储选配默认内存)
- [Coding Node](docs/FULL_CONFIG-zh.md#编程节点给大模型提供编程能力)
- [RAG](docs/FULL_CONFIG-zh.md#rag)
- [MCP Service (WIP)](docs/FULL_CONFIG-zh.md#mcp服务待完善)
- [Short Term Memory](docs/FULL_CONFIG-zh.md#短期记忆)

### 5. Debug和可观测

支持集成Langfuse体系可观测，相关配置项可[查看文档](docs/FULL_CONFIG-zh.md#可观测)

### 相关API文档

- [DashScope (Alibaba Bailian)](https://bailian.console.aliyun.com)
- [Tavily API Docs](https://docs.tavily.com/documentation/api-reference/endpoint/search)
- [Jina API Docs](https://jina.ai/reader)
- [AMap MCP Docs](https://lbs.amap.com/api/mcp-server/gettingstarted#t1)

## 测试用例

相关请求可见：[DeepResearch.http](DeepResearch.http)

```curl
curl --location 'http://localhost:8080/chat/stream' \
--header 'Content-Type: application/json' \
--data '{
    "thread_id": "__default_",
    "enable_deepresearch": false,
    "query": "请为我分析泡泡玛特现象级爆火的原因",
    "max_step_num": 2,
    "auto_accepted_plan": true
}'
```

## 📚 参考文档

- [完整配置参考](docs/FULL_CONFIG-zh.md#配置)
- [Spring AI Alibaba 文档](https://github.com/alibaba/spring-ai-alibaba)

## 🤝 加入社区 & 贡献

欢迎贡献！请参阅 [CONTRIBUTING](CONTRIBUTING-zh.md) 了解指南。
<div align="center">
    <img src="./imgs/qrcode.png" alt="社区"/>
</div>

## 📄 许可证

本项目采用 Apache License 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件。

## Contributors

感谢如下贡献人员的付出，逐渐完善本项目，其 Github 账号如下（排名顺序不分先后）：

[yingzi](https://github.com/GTyingzi)、[zhouyou](https://github.com/zhouyou9505)、[NOBODY](https://github.com/SCMRCORE)、[xiaohai-78](https://github.com/xiaohai-78)、[VLSMB](https://github.com/VLSMB)、[disaster1-tesk](https://github.com/disaster1-tesk)、[Allen Hu](https://github.com/big-mouth-cn)、[Makoto](https://github.com/zxuexingzhijie)、[sixiyida](https://github.com/sixiyida)、[Gfangxin](https://github.com/Gfangxin)、[AliciaHu](https://github.com/AliciaHu)、[swl](https://github.com/hbsjz-swl)、[huangzhen](https://github.com/james-huangzhen)、[Tfh-Yqf](https://github.com/Tfh-Yqf)、[anyin-xyz](https://github.com/anyin-xyz)、[zhou youkang](https://github.com/mengnankkkk)、[supermonkeyguys](https://github.com/supermonkeyguys)、[yuluo-yx](https://github.com/yuluo-yx)、[Ken Liu](https://github.com/chickenlj)、[co63ox](https://github.com/co63oc)、[benym](https://github.com/benym)

---

<div align="center">
    Made with ❤️ by Spring AI Alibaba DeepResearch Team
</div>