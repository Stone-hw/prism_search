# PrismSearch

多路召回聚合搜索引擎（Meta Search Engine）MVP，基于 Spring Boot 3 + JDK 21 虚拟线程构建，一次查询并发调用 **SearXNG**、**Google Custom Search**、**Bing Web Search**，经过标准化、URL/SimHash 双层去重、RRF 融合排序后返回统一结果。

设计文档见 [`TECH_DESIGN.md`](./TECH_DESIGN.md)。

## 特性

- 三路 Provider 并发调用，单路失败/超时不影响整体（`CompletableFuture.orTimeout` + 虚拟线程）
- URL 归一化（去 utm/tracking 参数、去 `www.`、去末尾 `/`、丢弃默认端口）
- SimHash 64 位近似去重，中文走 HanLP，英文走空格 + 停用词
- RRF (Reciprocal Rank Fusion) 融合排序，可配置权重与平滑常数 k
- Redis 结果缓存（30 min，TTL ±10% 抖动，空结果 5 min），Redis 宕机自动降级
- 每 IP 30 req/min 限流（Redis INCR + EXPIRE）
- Provider 级熔断（连续 5 次失败短路 5 分钟）
- Micrometer + Actuator + Prometheus 指标，MDC traceId 串联日志
- Thymeleaf 前端，多引擎命中显示徽标，支持分页与 `nocache` 绕过

## 目录结构

```
prismsearch/
├── src/main/java/com/prismsearch/
│   ├── PrismsearchApplication.java
│   ├── web/               # Controller / TraceIdFilter
│   ├── service/           # SearchOrchestrator (+impl)
│   ├── provider/          # SearchProvider / SearxngProvider / GoogleProvider / BingProvider
│   ├── rank/              # UrlNormalizer / TextCleaner / Tokenizer / SimHasher / Deduplicator / RRFRanker / ResultProcessor
│   ├── cache/             # SearchCacheService / RateLimitFilter
│   ├── model/             # SearchRequest / SearchResponse / RawSearchResult / NormalizedResult / ProviderStatus
│   ├── common/            # ApiResponse / ErrorCode / BizException / Constants
│   ├── config/            # PrismsearchProperties / WebClientConfig / ExecutorConfig / RedisConfig / OpenApiConfig
│   ├── exception/         # GlobalExceptionHandler
│   └── util/              # Md5Util / TimeUtil
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   ├── logback-spring.xml
│   ├── templates/index.html
│   └── static/{css,js}/
├── src/test/java/com/prismsearch/    # 单元 + 集成测试
├── deploy/searxng/settings.yml
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

## 快速开始

### 前置条件

- JDK 21（推荐 Eclipse Temurin）
- Maven 3.9+
- Docker + Docker Compose（可选，用于跑 Redis 和 SearXNG）
- Google Custom Search API Key + CSE `cx`（可选，无则 Google Provider 自动禁用）
- Bing Web Search API Key（可选，同上）

### 1. 启动依赖

```bash
docker-compose up -d redis searxng
```

Redis 监听 `localhost:6379`，SearXNG 监听 `localhost:8888`。

### 2. 配置 API Key

```bash
export GOOGLE_API_KEY=your-google-api-key
export GOOGLE_CX=your-cse-id
# 可选
export BING_API_KEY=your-bing-key
```

也可以创建 `.env` 文件（不入库，见 `.gitignore`）：

```
GOOGLE_API_KEY=xxx
GOOGLE_CX=yyy
BING_API_KEY=zzz
```

没有 Key 时 Google/Bing Provider 会自动禁用，只跑 SearXNG。

### 3. 本地运行

```bash
mvn spring-boot:run
```

或先打包再跑：

```bash
mvn clean package -DskipTests
java -jar target/prismsearch.jar
```

浏览器打开 <http://localhost:8080/>，或调用 API：

```bash
curl 'http://localhost:8080/api/search?q=spring+boot&page=1&size=10'
```

### 4. Docker 部署

```bash
mvn clean package -DskipTests
docker build -t prismsearch:latest .
docker-compose up -d
```

## API

### `GET /api/search`

| 参数 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `q` | string | 必填 | 查询关键词，长度 ≤ 200 |
| `page` | int | 1 | 页码 [1,20] |
| `size` | int | 10 | 每页条数 [1,50] |
| `lang` | string | zh-CN | 语言 |
| `safesearch` | int | 1 | 0/1/2 |
| `providers` | string | 全部 | 逗号分隔白名单，如 `google,bing` |
| `nocache` | bool | false | 强制绕过缓存 |

响应示例：

```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "query": "spring boot",
    "total": 27,
    "page": 1,
    "size": 10,
    "elapsedMs": 1234,
    "cached": false,
    "providers": {
      "searxng": {"status": "ok", "elapsedMs": 820, "count": 10},
      "google":  {"status": "ok", "elapsedMs": 610, "count": 10},
      "bing":    {"status": "timeout", "elapsedMs": 2000, "count": 0}
    },
    "results": [
      {
        "title": "Spring Boot 并发编程最佳实践",
        "url": "https://example.com/spring-boot-concurrency",
        "snippet": "...",
        "score": 0.0328,
        "source": "google",
        "sources": ["google", "bing"]
      }
    ]
  }
}
```

### `GET /api/suggest`

基于本地热词库的前缀补全。参数：`q`（前缀）、`limit`（默认 8）。

### `GET /actuator/health`

Spring Boot Actuator 健康检查。

### Swagger UI

启动后访问 <http://localhost:8080/swagger-ui.html>。

## 配置

核心配置见 [`src/main/resources/application.yml`](./src/main/resources/application.yml)。所有 API Key 通过环境变量注入，禁止硬编码：

```yaml
prismsearch:
  providers:
    searxng:
      enabled: ${SEARXNG_ENABLED:true}
      base-url: ${SEARXNG_BASE_URL:http://localhost:8888}
      timeout-ms: 5000
      weight: 0.8
    google:
      enabled: ${GOOGLE_ENABLED:true}
      api-key: ${GOOGLE_API_KEY:}
      cx: ${GOOGLE_CX:}
      timeout-ms: 2500
      weight: 1.0
    bing:
      enabled: ${BING_ENABLED:false}
      api-key: ${BING_API_KEY:}
      timeout-ms: 2500
      weight: 0.9
  rank:
    rrf-k: 60
    simhash-threshold: 3
  cache:
    ttl-seconds: 1800
    empty-ttl-seconds: 300
    jitter-ratio: 0.1
  rate-limit:
    enabled: true
    per-minute: 30
```

## 测试

```bash
mvn test
```

覆盖：`UrlNormalizer`、`SimHasher`、`Deduplicator`、`RRFRanker`、三个 Provider（`MockWebServer`）、`SearchOrchestratorImpl`（合并、降级、全失败、过滤、分页）、`SearchControllerIT`（Spring MVC 集成）。

## 指标

启动后访问 <http://localhost:8080/actuator/prometheus>：

- `prismsearch_request_total{cached,success}`
- `prismsearch_request_latency_seconds`
- `prismsearch_provider_latency_seconds{name}`
- `prismsearch_provider_error_total{name,type}`
- `prismsearch_cache_hit_total` / `prismsearch_cache_miss_total` / `prismsearch_cache_error_total{op}`
- `prismsearch_ratelimit_blocked_total`
- `prismsearch_result_count`

## 已知限制（MVP）

- 无用户系统、搜索历史、个性化
- 无垂直搜索（图片/新闻/视频）
- 排序仅 RRF，无 LTR / Embedding 语义重排
- 单机部署，未做多副本 + Nginx 负载均衡
- Bing Provider 默认关闭，需拿到 Key 后开启
- SearXNG 需自行部署（`docker-compose up -d searxng`）并在 `settings.yml` 打开 JSON 输出

后续演进方向详见 [`TECH_DESIGN.md`](./TECH_DESIGN.md) 第八章。

## License

MIT
