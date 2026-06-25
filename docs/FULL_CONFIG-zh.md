[中文](FULL_CONFIG-zh.md) | [English](FULL_CONFIG.md)
## 配置

### API KEY

- DashScope API: `${AI_DASHSCOPE_API_KEY}`
- TavilySearch API: `${TAVILY_API_KEY}`
- 报告导出路径: `${AI_DEEPRESEARCH_EXPORT_PATH}`
  TIP：不填会存储在项目根路径下

### 选配

#### **搜索服务(默认tavily)**

- Jina API: `${JINA_API_KEY}`
- aliyunaisearch:
    - api-key: `${ALIYUN_AI_SEARCH_API_KEY}`
    - base-url: `${ALIYUN_AI_SEARCH_BASE_URL}`

#### **存储选配(默认内存)**

- redis:`${REDIS-PASSWORD}`
  TIP：默认localhost:6379

#### **编程节点(给大模型提供编程能力)**

- Coder节点的Python执行器跑在Docker容器中，需要额外为其配置Docker信息
    - 在配置文件的`spring.ai.alibaba.deepresearch.python-coder.docker-host`字段中设置DockerHost，默认为`unix:///var/run/docker.sock`。
      本项目需要使用`python:3-slim`镜像创建临时容器，也可以自己定制包含一些常用的第三方库的镜像，第三方库需要安装在镜像的`/app/dependency`文件夹里，在配置文件中设置`spring.ai.alibaba.deepresearch.python-coder.image-name`的值指定镜像名称。

#### **RAG**

- ElasticSearch:
    - `application.yml`配置 spring.ai.alibaba.deepresearch.rag.enabled: true
    - `application.yml`配置 spring.ai.alibaba.deepresearch.rag.vector-store-type: elasticsearch
    - `application.yml`配置 spring.ai.alibaba.deepresearch.rag.elasticsearch 配置 ES相关信息
    - 启动ES中间件 ， 在spring-ai-alibaba-deepresearch目录下执行以下命令
        ```shell
        docker compose -f docker-compose-middleware.yml up -d
        ```
    - 在【知识库管理】页面新增知识库，并且上传对应的文档到 ES

#### **MCP服务(待完善)**

- 高德地图MCP

```json
{
    "researchAgent": {
        "mcp-servers": [
            {
                "url": "https://mcp.amap.com?key=${AI_DASHSCOPE_API_KEY}",
                "sse-endpoint": null,
                "description": "这是一个高德地图服务",
                "enabled": false
            }
        ]
    }
} 
```

#### **短期记忆**

`application.yml`

配置 spring.ai.alibaba.deepresearch.short-term-memory.enabled: true 开启短期记忆功能

- 会话记忆:
    - `application.yml`配置 spring.ai.alibaba.deepresearch.conversation-memory 配置会话记忆相关信息
- 用户角色记忆:
    - `application.yml`配置 spring.ai.alibaba.deepresearch.user-role-memory 配置用户角色记忆相关信息

#### **可观测**

Langfuse 配置

使用 Langfuse 云端服务
1. 在 [https://cloud.langfuse.com](https://cloud.langfuse.com) 注册账户
2. 创建新项目
3. 导航到 **Settings** → **API Keys**
4. 生成新的 API 密钥对（公钥和私钥）
5. 将凭据编码为 Base64：
   ```bash
   echo -n "public_key:secret_key" | base64
   ```
   ```Windows PowerShell
   [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("public_key:secret_key"))
   ```
6. yml文件中选择对应的endpoint，将编码后的字符串作为环境变量 `YOUR_BASE64_ENCODED_CREDENTIALS`

参考： https://langfuse.com/docs/opentelemetry/get-started