[中文](FULL_CONFIG-zh.md) | [English](FULL_CONFIG.md)
## Configuration

### API KEY

- DashScope API: `${AI_DASHSCOPE_API_KEY}`

  DashScope API key
- TavilySearch API: `${TAVILY_API_KEY}`

  TavilySearch API key
- Report export path: `${AI_DEEPRESEARCH_EXPORT_PATH}`

  TIP: if omitted, files will be stored in the project root

### Optional

#### **Search Services (default: Tavily)**

- Jina API: `${JINA_API_KEY}`
- aliyunaisearch:
    - api-key: `${ALIYUN_AI_SEARCH_API_KEY}`
    - base-url: `${ALIYUN_AI_SEARCH_BASE_URL}`

#### **Storage Options (default: in-memory)**

- redis: `${REDIS-PASSWORD}`

  Redis password; TIP: defaults to localhost:6379

#### **Coding Node (programming capability for LLM)**

- The Python executor of the Coder node runs inside a Docker container and requires Docker configuration.
    - Set `spring.ai.alibaba.deepresearch.python-coder.docker-host` in the config file; default is `unix:///var/run/docker.sock`.
      The project uses the `python:3-slim` image to create ephemeral containers. You can customize an image that includes common third-party libraries. Install them under `/app/dependency` inside the image, and set `spring.ai.alibaba.deepresearch.python-coder.image-name` to the image name in the config file.

#### **RAG**

- ElasticSearch:
    - In `application.yml`, set `spring.ai.alibaba.deepresearch.rag.enabled: true`
    - In `application.yml`, set `spring.ai.alibaba.deepresearch.rag.vector-store-type: elasticsearch`
    - In `application.yml`, configure `spring.ai.alibaba.deepresearch.rag.elasticsearch` with ES connection details
    - Start ES middleware from the project root with the command below
        ```shell
        docker compose -f docker-compose-middleware.yml up -d
        ```
    - In the Knowledge Base page, create a new knowledge base and upload documents to ES

#### **MCP Services (WIP)**

- AMap MCP

```json
{
    "researchAgent": {
        "mcp-servers": [
            {
                "url": "https://mcp.amap.com?key=${AI_DASHSCOPE_API_KEY}",
                "sse-endpoint": null,
                "description": "This is an AMap service",
                "enabled": false
            }
        ]
    }
} 
```

#### **Short Term Memory**

In `application.yml` 
set spring.ai.alibaba.deepresearch.short-term-memory.enabled: true, enable short-term memory
- Conversation Memory:
    - In `application.yml` set spring.ai.alibaba.deepresearch.conversation-memory, configuration for conversation memory
- User Role Memory:
    - In `application.yml` set spring.ai.alibaba.deepresearch.user-role-memory, configuration for user role memory

#### **Observability**

Langfuse Configuration

Using Langfuse Cloud

1. Sign up at [https://cloud.langfuse.com](https://cloud.langfuse.com)
2. Create a new project
3. Go to **Settings** → **API Keys**
4. Generate a new API key pair (public and secret)
5. Encode the credentials to Base64:
   ```bash
   echo -n "public_key:secret_key" | base64
   ```
   ```Windows PowerShell
   [System.Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("public_key:secret_key"))
   ```
6. In your yml, select the endpoint and set the encoded string as env `YOUR_BASE64_ENCODED_CREDENTIALS`

Reference: https://langfuse.com/docs/opentelemetry/get-started