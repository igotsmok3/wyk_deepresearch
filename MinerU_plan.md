# MinerU 集成 TDD 开发计划

> 参考设计文档：`MinerU_design.md`  
> 开发原则：先写测试，再写实现；每个 Task 均以"红→绿→重构"为节奏。

---

## Task 1：MinerU 配置属性类

### 目标
新增 `MinerUProperties` 配置类，并在 `RagProperties` 中嵌入。

### 1.1 先写测试

文件：`src/test/java/.../config/rag/MinerUPropertiesTest.java`

```java
@SpringBootTest(properties = {
    "spring.ai.alibaba.deepresearch.rag.mineru.enabled=true",
    "spring.ai.alibaba.deepresearch.rag.mineru.api-token=test-token",
    "spring.ai.alibaba.deepresearch.rag.mineru.model-version=vlm",
    "spring.ai.alibaba.deepresearch.rag.mineru.polling-interval-ms=3000",
    "spring.ai.alibaba.deepresearch.rag.mineru.max-polling-attempts=10"
})
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
        // 在未配置时的默认值测试（使用另一个 test properties 或直接 new）
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
```

### 1.2 实现

**文件：`config/rag/RagProperties.java`**

在 `RagProperties` 末尾添加 `MinerU` 静态内部类，并在 `RagProperties` 中添加字段：

```java
private final MinerU minerU = new MinerU();

public MinerU getMinerU() { return minerU; }

public static class MinerU {
    private boolean enabled = false;
    private String apiBaseUrl = "https://mineru.net";
    private String apiToken;
    private String modelVersion = "pipeline";
    private boolean enableFormula = true;
    private boolean enableTable = true;
    private String language = "ch";
    private long pollingIntervalMs = 5000L;
    private int maxPollingAttempts = 72;
    private int connectTimeoutMs = 10000;
    private int readTimeoutMs = 60000;
    // getters & setters ...
}
```

### 1.3 验收标准
- [ ] `MinerUPropertiesTest` 全部通过
- [ ] 默认值正确，不影响现有配置绑定

---

## Task 2：MinerUApiClient — 请求上传 URL

### 目标
实现 `MinerUApiClient.requestUploadUrls()`，与 MinerU API 交互。

### 2.1 先写测试

文件：`src/test/java/.../service/MinerUApiClientTest.java`

```java
@ExtendWith(MockitoExtension.class)
class MinerUApiClientTest {

    // 使用 MockWebServer（OkHttp）或 WireMock 模拟 HTTP 端点
    static MockWebServer mockServer;
    MinerUApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        MinerUProperties props = new MinerUProperties();
        props.setApiBaseUrl(mockServer.url("/").toString());
        props.setApiToken("test-token");
        props.setConnectTimeoutMs(1000);
        props.setReadTimeoutMs(5000);
        client = new MinerUApiClient(props);
    }

    @AfterEach
    void tearDown() throws IOException { mockServer.shutdown(); }

    @Test
    void requestUploadUrls_returnsCorrectBatchIdAndUrls() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("""
                {"code":0,"data":{"batch_id":"batch-001",
                "file_urls":["https://cdn.example.com/upload/abc"]}}
                """)
            .addHeader("Content-Type", "application/json"));

        var result = client.requestUploadUrls(
            List.of(new MinerUApiClient.FileUploadRequest("test.pdf", "data-001")));

        assertThat(result.batchId()).isEqualTo("batch-001");
        assertThat(result.fileUrls()).hasSize(1).contains("https://cdn.example.com/upload/abc");

        RecordedRequest req = mockServer.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v4/file-urls/batch");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-token");
    }

    @Test
    void requestUploadUrls_throwsOnApiError() {
        mockServer.enqueue(new MockResponse().setResponseCode(401));

        assertThatThrownBy(() ->
            client.requestUploadUrls(List.of(new MinerUApiClient.FileUploadRequest("a.pdf", null)))
        ).isInstanceOf(MinerUApiException.class);
    }

    @Test
    void uploadFile_putsFileWithoutContentType() throws Exception {
        mockServer.enqueue(new MockResponse().setResponseCode(200));

        client.uploadFile(mockServer.url("/upload/abc").toString(), "pdf-bytes".getBytes());

        RecordedRequest req = mockServer.takeRequest();
        assertThat(req.getMethod()).isEqualTo("PUT");
        assertThat(req.getHeader("Content-Type")).isNull();
        assertThat(req.getBody().readUtf8()).isEqualTo("pdf-bytes");
    }

    @Test
    void pollBatchResult_returnsRunningState() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("""
                {"code":0,"data":{"state":"running","full_zip_url":null}}
                """)
            .addHeader("Content-Type", "application/json"));

        var result = client.pollBatchResult("batch-001");

        assertThat(result.state()).isEqualTo("running");
        assertThat(result.fullZipUrl()).isNull();
    }

    @Test
    void pollBatchResult_returnsDoneState() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody("""
                {"code":0,"data":{"state":"done",
                "full_zip_url":"https://cdn.example.com/result.zip"}}
                """)
            .addHeader("Content-Type", "application/json"));

        var result = client.pollBatchResult("batch-001");

        assertThat(result.state()).isEqualTo("done");
        assertThat(result.fullZipUrl()).isEqualTo("https://cdn.example.com/result.zip");
    }

    @Test
    void downloadAndExtractMarkdown_returnsMarkdownContent() throws Exception {
        // 构造一个包含 full.md 的 ZIP 字节
        byte[] zipBytes = buildZipWithMarkdown("# Hello\n\nWorld");
        mockServer.enqueue(new MockResponse()
            .setResponseCode(200)
            .setBody(new Buffer().write(zipBytes))
            .addHeader("Content-Type", "application/zip"));

        String markdown = client.downloadAndExtractMarkdown(mockServer.url("/result.zip").toString());

        assertThat(markdown).isEqualTo("# Hello\n\nWorld");
    }
}
```

### 2.2 实现

**文件：`service/MinerUApiClient.java`**

```java
@Service
public class MinerUApiClient {

    public record FileUploadRequest(String name, String dataId) {}
    public record BatchUploadResponse(String batchId, List<String> fileUrls) {}
    public record BatchResultResponse(String state, String fullZipUrl, String errMsg) {}

    private final MinerUProperties props;
    private final RestClient restClient;

    // RestClient 配置连接/读取超时，注入 props.apiToken 到 defaultHeader

    public BatchUploadResponse requestUploadUrls(List<FileUploadRequest> files) { ... }
    public void uploadFile(String presignedUrl, byte[] content) { ... }
    public BatchResultResponse pollBatchResult(String batchId) { ... }
    public String downloadAndExtractMarkdown(String zipUrl) { ... }
}
```

关键实现要点：
- `RestClient` 通过 `RestClient.builder()` 构建，设置 `baseUrl`、`defaultHeader("Authorization", "Bearer " + token)`
- `uploadFile` 使用 `PUT` 且**不设置 Content-Type**
- `downloadAndExtractMarkdown` 下载 ZIP 后用 `ZipInputStream` 查找 `full.md` 条目

### 2.3 验收标准
- [ ] 所有 `MinerUApiClientTest` 通过
- [ ] Authorization header 正确注入
- [ ] PUT 上传不带 Content-Type
- [ ] ZIP 解析能正确提取 `full.md`

---

## Task 3：MinerUDocumentReader — 完整解析流程

### 目标
编排 API 调用 + 轮询，返回携带 Markdown 的 `Document` 列表。

### 3.1 先写测试

文件：`src/test/java/.../rag/reader/MinerUDocumentReaderTest.java`

```java
@ExtendWith(MockitoExtension.class)
class MinerUDocumentReaderTest {

    @Mock MinerUApiClient apiClient;
    MinerUDocumentReader reader;
    MinerUProperties props;

    @BeforeEach
    void setUp() {
        props = new MinerUProperties();
        props.setPollingIntervalMs(0); // 测试时不等待
        props.setMaxPollingAttempts(3);
        reader = new MinerUDocumentReader(apiClient, props);
    }

    @Test
    void read_successfulParse_returnsDocumentWithMarkdown() throws Exception {
        byte[] pdfBytes = "fake-pdf".getBytes();
        given(apiClient.requestUploadUrls(any())).willReturn(
            new BatchUploadResponse("batch-1", List.of("https://upload.example.com/1")));
        willDoNothing().given(apiClient).uploadFile(any(), any());
        given(apiClient.pollBatchResult("batch-1"))
            .willReturn(new BatchResultResponse("done", "https://cdn.example.com/result.zip", null));
        given(apiClient.downloadAndExtractMarkdown("https://cdn.example.com/result.zip"))
            .willReturn("# Title\n\nContent");

        List<Document> docs = reader.read("test.pdf", pdfBytes, Map.of("session_id", "s1"));

        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).getText()).isEqualTo("# Title\n\nContent");
        assertThat(docs.get(0).getMetadata().get("original_filename")).isEqualTo("test.md");
        assertThat(docs.get(0).getMetadata().get("session_id")).isEqualTo("s1");
        assertThat(docs.get(0).getMetadata().get("parsed_by")).isEqualTo("mineru");
    }

    @Test
    void read_pollsUntilDone() throws Exception {
        byte[] pdfBytes = "fake-pdf".getBytes();
        given(apiClient.requestUploadUrls(any())).willReturn(
            new BatchUploadResponse("batch-2", List.of("https://upload.example.com/2")));
        willDoNothing().given(apiClient).uploadFile(any(), any());
        // 先返回 running，再返回 done
        given(apiClient.pollBatchResult("batch-2"))
            .willReturn(new BatchResultResponse("running", null, null))
            .willReturn(new BatchResultResponse("done", "https://cdn.example.com/r2.zip", null));
        given(apiClient.downloadAndExtractMarkdown(any())).willReturn("# Done");

        List<Document> docs = reader.read("test.pdf", pdfBytes, Map.of());

        assertThat(docs).hasSize(1);
        // pollBatchResult 应被调用 2 次
        then(apiClient).should(times(2)).pollBatchResult("batch-2");
    }

    @Test
    void read_throwsWhenMaxAttemptsExceeded() {
        byte[] pdfBytes = "fake-pdf".getBytes();
        given(apiClient.requestUploadUrls(any())).willReturn(
            new BatchUploadResponse("batch-3", List.of("https://upload.example.com/3")));
        willDoNothing().given(apiClient).uploadFile(any(), any());
        // 始终返回 running（超过 maxPollingAttempts=3）
        given(apiClient.pollBatchResult(any()))
            .willReturn(new BatchResultResponse("running", null, null));

        assertThatThrownBy(() -> reader.read("test.pdf", pdfBytes, Map.of()))
            .isInstanceOf(MinerUParseException.class)
            .hasMessageContaining("timeout");
    }

    @Test
    void read_throwsWhenStateFailed() {
        byte[] pdfBytes = "fake-pdf".getBytes();
        given(apiClient.requestUploadUrls(any())).willReturn(
            new BatchUploadResponse("batch-4", List.of("https://upload.example.com/4")));
        willDoNothing().given(apiClient).uploadFile(any(), any());
        given(apiClient.pollBatchResult(any()))
            .willReturn(new BatchResultResponse("failed", null, "parse error"));

        assertThatThrownBy(() -> reader.read("test.pdf", pdfBytes, Map.of()))
            .isInstanceOf(MinerUParseException.class)
            .hasMessageContaining("parse error");
    }

    @Test
    void read_throwsWhenMarkdownIsEmpty() throws Exception {
        byte[] pdfBytes = "fake-pdf".getBytes();
        given(apiClient.requestUploadUrls(any())).willReturn(
            new BatchUploadResponse("batch-5", List.of("https://upload.example.com/5")));
        willDoNothing().given(apiClient).uploadFile(any(), any());
        given(apiClient.pollBatchResult(any()))
            .willReturn(new BatchResultResponse("done", "https://cdn.example.com/r5.zip", null));
        given(apiClient.downloadAndExtractMarkdown(any())).willReturn("");

        assertThatThrownBy(() -> reader.read("test.pdf", pdfBytes, Map.of()))
            .isInstanceOf(MinerUParseException.class)
            .hasMessageContaining("empty");
    }
}
```

### 3.2 实现

**文件：`rag/reader/MinerUDocumentReader.java`**

```java
public class MinerUDocumentReader {

    public List<Document> read(String filename, byte[] content, Map<String, Object> baseMetadata)
            throws MinerUParseException {
        // 1. 请求预签名 URL
        var uploadResp = apiClient.requestUploadUrls(
            List.of(new FileUploadRequest(filename, null)));
        // 2. PUT 上传文件
        apiClient.uploadFile(uploadResp.fileUrls().get(0), content);
        // 3. 轮询
        String zipUrl = pollUntilDone(uploadResp.batchId());
        // 4. 下载 ZIP，解压 full.md
        String markdown = apiClient.downloadAndExtractMarkdown(zipUrl);
        if (markdown == null || markdown.isBlank()) {
            throw new MinerUParseException("empty markdown for file: " + filename);
        }
        // 5. 构造 Document，original_filename 改为 .md 后缀
        Map<String, Object> metadata = new HashMap<>(baseMetadata);
        metadata.put("original_filename", toMdFilename(filename));
        metadata.put("parsed_by", "mineru");
        return List.of(new Document(markdown, metadata));
    }

    private String pollUntilDone(String batchId) {
        for (int i = 0; i < props.getMaxPollingAttempts(); i++) {
            var result = apiClient.pollBatchResult(batchId);
            if ("done".equals(result.state())) return result.fullZipUrl();
            if ("failed".equals(result.state()))
                throw new MinerUParseException("MinerU parse failed: " + result.errMsg());
            sleep(props.getPollingIntervalMs());
        }
        throw new MinerUParseException("MinerU polling timeout for batchId: " + batchId);
    }
}
```

**文件：`rag/reader/MinerUParseException.java`**（运行时异常）

### 3.3 验收标准
- [ ] 所有 `MinerUDocumentReaderTest` 通过
- [ ] `original_filename` 后缀正确替换为 `.md`
- [ ] metadata 中包含 `parsed_by=mineru`
- [ ] 超时、失败、空内容场景均抛出 `MinerUParseException`

---

## Task 4：集成到 VectorStoreDataIngestionService

### 目标
在摄入主流程中，对 PDF 文件优先使用 MinerU reader，失败时降级到 Tika。

### 4.1 先写测试

文件：`src/test/java/.../service/VectorStoreDataIngestionServiceMinerUTest.java`

```java
@ExtendWith(MockitoExtension.class)
class VectorStoreDataIngestionServiceMinerUTest {

    @Mock VectorStore vectorStore;
    @Mock DocumentSplitterRouter splitterRouter;
    @Mock MinerUDocumentReader minerUReader;
    @Mock RagProperties ragProperties;
    VectorStoreDataIngestionService service;

    @BeforeEach
    void setUp() {
        // 构建 RagProperties 使 MinerU enabled
        var minerUProps = new RagProperties.MinerU();
        minerUProps.setEnabled(true);
        given(ragProperties.getMinerU()).willReturn(minerUProps);
        given(ragProperties.getTextSplitter()).willReturn(new RagProperties.TextSplitter());
        // ... 其他 props

        service = new VectorStoreDataIngestionService(
            vectorStore, ragProperties, splitterRouter,
            Optional.of(minerUReader));  // ObjectProvider 或 Optional 注入
    }

    @Test
    void batchProcessAndStore_usesMinerUForPdf() throws Exception {
        MultipartFile pdfFile = mockMultipartFile("report.pdf", "fake-pdf".getBytes());
        List<Document> minerUDocs = List.of(new Document("# Markdown", Map.of("original_filename", "report.md")));
        List<Document> chunks = List.of(new Document("chunk", Map.of()));

        given(minerUReader.read(eq("report.pdf"), any(), any())).willReturn(minerUDocs);
        given(splitterRouter.apply(minerUDocs)).willReturn(chunks);

        service.batchProcessAndStore(List.of(pdfFile), "session-1", "user-1");

        then(vectorStore).should().add(anyList());
        // 验证 MinerU reader 被调用，而非 Tika
        then(minerUReader).should().read(eq("report.pdf"), any(), any());
    }

    @Test
    void batchProcessAndStore_fallsBackToTikaOnMinerUFailure() throws Exception {
        MultipartFile pdfFile = mockMultipartFile("report.pdf", "fake-pdf".getBytes());

        given(minerUReader.read(eq("report.pdf"), any(), any()))
            .willThrow(new MinerUParseException("API error"));

        // 不抛出异常，降级成功，vectorStore.add 被调用
        assertThatCode(() ->
            service.batchProcessAndStore(List.of(pdfFile), "session-1", "user-1")
        ).doesNotThrowAnyException();

        then(vectorStore).should().add(anyList());
    }

    @Test
    void batchProcessAndStore_nonPdfSkipsMinerU() throws Exception {
        MultipartFile txtFile = mockMultipartFile("notes.txt", "hello world".getBytes());

        service.batchProcessAndStore(List.of(txtFile), "session-1", "user-1");

        then(minerUReader).shouldHaveNoInteractions();
        then(vectorStore).should().add(anyList());
    }
}
```

### 4.2 实现

**文件：`service/VectorStoreDataIngestionService.java`**

1. 构造函数新增 `ObjectProvider<MinerUDocumentReader> minerUReaderProvider` 参数
2. 提取私有方法：

```java
private List<Document> readDocumentsWithMinerUFallback(
        String filename, byte[] content, Map<String, Object> meta) {
    if (minerUReader != null && isPdf(filename)) {
        try {
            return minerUReader.read(filename, content, meta);
        } catch (MinerUParseException e) {
            logger.warn("MinerU parse failed for {}, falling back to Tika: {}", filename, e.getMessage());
        }
    }
    // Tika 读取（从字节流构建 ByteArrayResource）
    return new TikaDocumentReader(new ByteArrayResource(content, filename)).get();
}

private static boolean isPdf(String filename) {
    return filename != null && filename.toLowerCase().endsWith(".pdf");
}
```

3. 在 `batchProcessAndStore(List<MultipartFile>, ...)` 中：
   - 将 `TikaDocumentReader reader = new TikaDocumentReader(...)` 替换为调用 `readDocumentsWithMinerUFallback(...)`

4. 同理更新 `batchProcessAndStoreResources` 和 `ingest(Resource)` 中的 PDF 读取路径

### 4.3 验收标准
- [ ] PDF 文件优先走 MinerU reader
- [ ] MinerU 失败时自动降级，不影响摄入流程继续
- [ ] 非 PDF 文件完全不触发 MinerU 逻辑
- [ ] MinerU 未注入时（disabled）行为与改动前完全一致

---

## Task 5：Bean 注册与配置

### 目标
在 `MinerUConfiguration` 中注册 Bean，并在 `application.yml` 中添加配置项。

### 5.1 先写测试

文件：`src/test/java/.../config/rag/MinerUConfigurationTest.java`

```java
// 测试 MinerU Bean 在 enabled=true 时注册
@SpringBootTest(properties = {
    "spring.ai.alibaba.deepresearch.rag.enabled=true",
    "spring.ai.alibaba.deepresearch.rag.mineru.enabled=true",
    "spring.ai.alibaba.deepresearch.rag.mineru.api-token=test",
    // 其他必要的最小配置 ...
})
class MinerUEnabledConfigurationTest {

    @Autowired ApplicationContext context;

    @Test
    void minerUBeansRegisteredWhenEnabled() {
        assertThat(context.containsBean("minerUApiClient")).isTrue();
        assertThat(context.containsBean("minerUDocumentReader")).isTrue();
    }
}

// 测试 MinerU Bean 在 enabled=false（默认）时不注册
@SpringBootTest(properties = {
    "spring.ai.alibaba.deepresearch.rag.enabled=true"
    // mineru.enabled 未设置，默认 false
})
class MinerUDisabledConfigurationTest {

    @Autowired ApplicationContext context;

    @Test
    void minerUBeansAbsentWhenDisabled() {
        assertThat(context.containsBean("minerUApiClient")).isFalse();
        assertThat(context.containsBean("minerUDocumentReader")).isFalse();
    }
}
```

### 5.2 实现

**文件：`config/rag/MinerUConfiguration.java`**

```java
@Configuration
@ConditionalOnProperty(
    prefix = "spring.ai.alibaba.deepresearch.rag.mineru",
    name = "enabled",
    havingValue = "true"
)
public class MinerUConfiguration {

    @Bean
    public MinerUApiClient minerUApiClient(RagProperties ragProperties) {
        return new MinerUApiClient(ragProperties.getMinerU());
    }

    @Bean
    public MinerUDocumentReader minerUDocumentReader(
            MinerUApiClient client, RagProperties ragProperties) {
        return new MinerUDocumentReader(client, ragProperties.getMinerU());
    }
}
```

**文件：`src/main/resources/application.yml`** 新增：

```yaml
spring:
  ai:
    alibaba:
      deepresearch:
        rag:
          mineru:
            enabled: false          # 默认关闭，按需开启
            api-base-url: https://mineru.net
            api-token: ${MINERU_API_TOKEN:}
            model-version: pipeline
            enable-formula: true
            enable-table: true
            language: ch
            polling-interval-ms: 5000
            max-polling-attempts: 72
```

### 5.3 验收标准
- [ ] `MinerUEnabledConfigurationTest` 通过
- [ ] `MinerUDisabledConfigurationTest` 通过
- [ ] `application.yml` 默认 `enabled: false`，不破坏现有启动

---

## Task 6：集成测试（可选，需真实 Token）

### 目标
端到端验证：上传真实 PDF → MinerU 解析 → Markdown 写入 VectorStore。

### 6.1 测试

文件：`src/test/java/.../integration/MinerUIntegrationTest.java`

```java
// 标记为 @Tag("integration")，CI 中默认跳过，本地按需运行
@Tag("integration")
@SpringBootTest(properties = {
    "spring.ai.alibaba.deepresearch.rag.enabled=true",
    "spring.ai.alibaba.deepresearch.rag.mineru.enabled=true",
    "spring.ai.alibaba.deepresearch.rag.mineru.api-token=${MINERU_API_TOKEN}"
})
class MinerUIntegrationTest {

    @Autowired MinerUDocumentReader reader;

    @Test
    void parseRealPdf_returnsNonEmptyMarkdown() throws Exception {
        byte[] pdfBytes = getClass().getResourceAsStream("/test-data/sample.pdf").readAllBytes();
        List<Document> docs = reader.read("sample.pdf", pdfBytes, Map.of());
        assertThat(docs).isNotEmpty();
        assertThat(docs.get(0).getText()).isNotBlank();
        System.out.println("MinerU output preview:\n" + docs.get(0).getText().substring(0, 500));
    }
}
```

### 6.2 验收标准
- [ ] 真实 PDF 能成功解析并返回 Markdown
- [ ] 解析结果中包含结构化标题（`#` 开头的行）

---

## 开发顺序与依赖关系

```
Task 1 (Properties)
    ↓
Task 2 (ApiClient)
    ↓
Task 3 (DocumentReader)
    ↓
Task 4 (Service 集成) ← 依赖 Task 1-3
    ↓
Task 5 (Bean 注册)    ← 依赖 Task 1-4
    ↓
Task 6 (集成测试)     ← 依赖 Task 5 + 真实 Token
```

---

## 新增文件清单

| 文件 | Task |
|------|------|
| `config/rag/MinerUConfiguration.java` | Task 5 |
| `service/MinerUApiClient.java` | Task 2 |
| `rag/reader/MinerUDocumentReader.java` | Task 3 |
| `rag/reader/MinerUParseException.java` | Task 3 |
| `src/test/.../config/rag/MinerUPropertiesTest.java` | Task 1 |
| `src/test/.../service/MinerUApiClientTest.java` | Task 2 |
| `src/test/.../rag/reader/MinerUDocumentReaderTest.java` | Task 3 |
| `src/test/.../service/VectorStoreDataIngestionServiceMinerUTest.java` | Task 4 |
| `src/test/.../config/rag/MinerUConfigurationTest.java` | Task 5 |
| `src/test/.../integration/MinerUIntegrationTest.java` | Task 6 |

## 修改文件清单

| 文件 | 改动内容 |
|------|----------|
| `config/rag/RagProperties.java` | 新增 `MinerU` 内部类和字段 |
| `service/VectorStoreDataIngestionService.java` | 注入 MinerU reader，替换 PDF 读取逻辑 |
| `src/main/resources/application.yml` | 新增 `mineru.*` 配置项 |

---

## 测试依赖

在 `pom.xml` 中需要确认以下测试依赖已存在：

```xml
<!-- MockWebServer（用于 MinerUApiClientTest） -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>mockwebserver</artifactId>
    <scope>test</scope>
</dependency>
```
