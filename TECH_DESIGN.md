# MySearch — 多路召回聚合搜索引擎 技术设计文档

| 项目 | 内容 |
|------|------|
| 需求名称 | MySearch 多路召回聚合搜索引擎 MVP |
| 所属服务 | mysearch（Java Spring Boot 单体应用） |
| 作者 | — |
| 创建日期 | 2026-09-21 |
| 最后更新 | 2026-09-21 |
| 状态 | 草稿 |

---

## 一、需求背景与目标

### 1.1 背景

单一搜索引擎受限于自身索引库和排序算法，难以覆盖全网信息。不同搜索引擎在索引范围、排序策略、内容质量上各有优劣：Google 全球覆盖广、Bing 对英文技术内容质量高、SearXNG 可自建并聚合 DuckDuckGo/Wikipedia 等隐私友好源。

用户希望有一个聚合多个搜索引擎的元搜索引擎（Meta Search），能够：
1. 一次查询并发调用多个搜索引擎
2. 对多路结果进行标准化、去重
3. 基于相关度进行融合排序后统一返回

### 1.2 目标

- **多路召回**：并发调用 SearXNG、Google Custom Search JSON API、Bing Web Search API
- **结果标准化**：将三种不同的返回格式统一为内部标准结构
- **去重**：URL 归一化去重 + SimHash 内容近似去重
- **融合排序**：基于 Reciprocal Rank Fusion (RRF) 算法排序
- **缓存**：Redis 缓存热门查询结果，降低 API 配额消耗
- **Web 前端**：简洁搜索页面（Thymeleaf 或静态 HTML + JSON API）
- **性能**：MVP 阶段 P95 响应时间 < 3s，支持 50 QPS

### 1.3 非目标（本次不做）

- 用户登录鉴权、搜索历史、个性化推荐
- 图片/视频/新闻等垂直搜索
- 自研 Learning-to-Rank 模型（MVP 阶段用 RRF）
- 移动端 App
- 高可用集群部署（单机 + Docker Compose 即可）

---

## 二、方案设计

### 2.1 整体设计

#### 涉及模块

| 模块 | 职责 | 类型 |
|------|------|------|
| `mysearch-web` | Controller 层，HTTP API 与页面渲染 | 新增 |
| `mysearch-core` | 搜索编排、Provider 管理、结果处理 | 新增 |
| `mysearch-provider` | 三个搜索引擎的适配层（SearXNG/Google/Bing） | 新增 |
| `mysearch-rank` | 标准化、去重、RRF 融合排序 | 新增 |
| `mysearch-cache` | Redis 缓存封装 | 新增 |
| `mysearch-common` | DTO、常量、工具类、异常 | 新增 |

MVP 阶段采用 **单 Maven 多模块** 结构，后续可按需拆分。

#### 系统架构图

```
                        ┌──────────────────────────────────────────────┐
                        │            Nginx / 反向代理 (可选)             │
                        └──────────────────┬───────────────────────────┘
                                           │
              ┌────────────────────────────┼────────────────────────────┐
              │                            │                            │
       ┌──────▼──────┐              ┌──────▼──────┐             ┌──────▼──────┐
       │ 静态页面     │              │ Spring Boot │             │  /actuator   │
       │ /index.html │              │ 应用         │             │  健康检查     │
       └─────────────┘              └──────┬──────┘             └─────────────┘
                                           │
                            ┌──────────────▼──────────────┐
                            │  SearchController (API)     │
                            │  GET /api/search?q=xxx      │
                            └──────────────┬──────────────┘
                                           │
                            ┌──────────────▼──────────────┐
                            │      SearchOrchestrator     │
                            │      (核心编排层)            │
                            └──────────────┬──────────────┘
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
            ┌───────▼──────┐      ┌────────▼───────┐     ┌────────▼───────┐
            │ SearchProvider│     │ SearchProvider  │     │ SearchProvider │
            │ SearXNG       │     │ Google CS API   │     │ Bing API       │
            └───────────────┘     └─────────────────┘     └────────────────┘
                    │                      │                      │
                    └──────────────────────┼──────────────────────┘
                                           │
                            ┌──────────────▼──────────────┐
                            │  ResultProcessor            │
                            │  1. Normalizer (标准化)      │
                            │  2. Deduplicator (去重)      │
                            │  3. RRFRanker (融合排序)     │
                            └──────────────┬──────────────┘
                                           │
                            ┌──────────────▼──────────────┐
                            │  RedisCache (查询缓存)       │
                            └─────────────────────────────┘
```

#### 核心搜索流程时序图

```
User    Frontend   Controller   Orchestrator   Redis   Providers   Processor
 │         │           │            │           │         │           │
 │─输入──▶│           │            │           │         │           │
 │         │─GET /api/search?q=x─▶│            │         │           │
 │         │           │─search(q)▶│           │         │           │
 │         │           │            │─get(key)─▶│         │           │
 │         │           │            │◀─miss─────│         │           │
 │         │           │            │                    │           │
 │         │           │            │─并发调用 3 个 Provider (CompletableFuture.allOf)
 │         │           │            │                    │           │
 │         │           │            │─searxng.search()──▶│           │
 │         │           │            │─google.search()───▶│           │
 │         │           │            │─bing.search()─────▶│           │
 │         │           │            │                    │           │
 │         │           │            │◀─各 Provider 结果──│           │
 │         │           │            │                    │           │
 │         │           │            │─process(results)──────────────▶│
 │         │           │            │                    │   1.标准化 │
 │         │           │            │                    │   2.去重   │
 │         │           │            │                    │   3.RRF排序│
 │         │           │            │◀─排序后结果─────────────────────│
 │         │           │            │                    │           │
 │         │           │            │─put(key,val,TTL)──▶│           │
 │         │           │◀─结果──────│           │         │           │
 │         │◀─JSON─────│           │           │         │           │
 │◀─渲染──│           │            │           │         │           │
```

### 2.2 技术选型

| 组件 | 选型 | 版本 | 说明 |
|------|------|------|------|
| JDK | Eclipse Temurin | 17 或 21 | 推荐 21，可用虚拟线程 |
| 框架 | Spring Boot | 3.2.x | Web + Validation |
| HTTP 客户端 | Spring WebClient | 内置 | 响应式并发；或 Java 11+ HttpClient |
| 并发编排 | CompletableFuture | JDK 内置 | `allOf` + 超时降级 |
| 缓存 | Spring Cache + Redis (Lettuce) | — | 查询结果缓存 |
| JSON | Jackson | 内置 | DTO 序列化 |
| 模板引擎 | Thymeleaf | 内置 | 前端页面渲染 |
| SimHash | 自实现 | — | 64 位 SimHash，中文用 HanLP/结巴分词 |
| 分词 | HanLP 或 Jieba-analysis | — | SimHash 前置分词 |
| 参数校验 | Hibernate Validator | 内置 | JSR-380 |
| 日志 | SLF4J + Logback | 内置 | — |
| 构建 | Maven | 3.9+ | 多模块 |
| 部署 | Docker Compose | — | 应用 + Redis + SearXNG |
| API 文档 | SpringDoc OpenAPI | 2.x | Swagger UI |

### 2.3 接口设计

#### 2.3.1 搜索接口

- **路径**：`GET /api/search`
- **说明**：聚合多引擎的元搜索接口
- **鉴权**：MVP 阶段无鉴权

**请求参数：**

| 字段 | 类型 | 必填 | 默认 | 说明 | 校验规则 |
|------|------|------|------|------|---------|
| `q` | String | 是 | — | 查询关键词 | @NotBlank，长度 ≤ 200 |
| `page` | Integer | 否 | 1 | 页码 | @Min(1) @Max(20) |
| `size` | Integer | 否 | 10 | 每页条数 | @Min(1) @Max(50) |
| `lang` | String | 否 | `zh-CN` | 语言 | 枚举白名单 |
| `safesearch` | Integer | 否 | 1 | 安全搜索等级 0/1/2 | @Min(0) @Max(2) |
| `providers` | String | 否 | 全部 | 指定 Provider，逗号分隔 | 枚举校验 |
| `nocache` | Boolean | 否 | false | 强制绕过缓存 | — |

**请求示例：**
```
GET /api/search?q=spring%20boot%20%E5%B9%B6%E5%8F%91&page=1&size=10&lang=zh-CN
```

**响应参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | Integer | 状态码，0 成功，非 0 失败 |
| `msg` | String | 提示信息 |
| `data` | Object | 业务数据 |
| `data.query` | String | 回显查询词 |
| `data.total` | Integer | 结果总数 |
| `data.page` | Integer | 当前页 |
| `data.size` | Integer | 每页条数 |
| `data.elapsedMs` | Long | 服务端耗时（ms） |
| `data.cached` | Boolean | 是否命中缓存 |
| `data.providers` | Object | 各 Provider 状态（成功/失败/耗时/条数） |
| `data.results` | Array | 结果列表 |
| `data.results[].title` | String | 标题 |
| `data.results[].url` | String | 规范化后的 URL |
| `data.results[].snippet` | String | 摘要 |
| `data.results[].score` | Double | RRF 融合分数 |
| `data.results[].source` | String | 主要来源（searxng/google/bing） |
| `data.results[].sources` | Array\<String\> | 命中的所有引擎（用于展示"多引擎验证"） |

**响应示例：**
```json
{
  "code": 0,
  "msg": "success",
  "data": {
    "query": "spring boot 并发",
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
        "snippet": "本文介绍 Spring Boot 中并发编程的...",
        "score": 0.0328,
        "source": "google",
        "sources": ["google", "bing"]
      }
    ]
  }
}
```

**异常场景：**

| 场景 | code | msg |
|------|------|-----|
| 参数缺失 | 400 | q 不能为空 |
| 参数越界 | 400 | size 超出范围 [1,50] |
| 所有 Provider 失败 | 502 | 搜索服务暂不可用，请稍后重试 |
| 内部异常 | 500 | 系统异常 |

#### 2.3.2 搜索建议接口（可选）

- **路径**：`GET /api/suggest`
- **说明**：基于本地热词库 + Provider 建议接口的关键词补全
- **参数**：`q`（前缀）、`limit`（默认 8）

#### 2.3.3 健康检查

- **路径**：`GET /actuator/health`
- **说明**：Spring Boot Actuator 内置，用于 Docker/负载均衡探活

### 2.4 数据库设计

MVP 阶段**不引入 MySQL**，仅使用 Redis 缓存。后续如需持久化以下数据再引入：

| 表名 | 用途 | MVP 阶段 |
|------|------|---------|
| `t_search_log` | 搜索日志（query、耗时、结果数） | 用日志文件替代 |
| `t_hot_query` | 热词库（用于 suggest） | 硬编码/配置文件 |
| `t_url_blacklist` | URL 黑名单（广告/低质站） | 配置文件 |

### 2.5 Provider 适配层设计

三个 Provider 实现统一接口 `SearchProvider`，屏蔽底层 API 差异。

```java
public interface SearchProvider {
    /** 引擎标识：searxng / google / bing */
    String name();

    /** 是否启用 */
    boolean enabled();

    /** 执行搜索，超时或失败抛异常 */
    List<RawSearchResult> search(SearchRequest request);
}
```

**统一中间结构 `RawSearchResult`：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `provider` | String | 来源引擎 |
| `rank` | Integer | 该引擎中的原始排名（1-based） |
| `title` | String | 标题 |
| `url` | String | 原始 URL |
| `snippet` | String | 摘要 |
| `publishedAt` | LocalDateTime | 发布时间（可为空） |
| `engineScore` | Double | 引擎自身评分（可为空） |

**三个 Provider 实现要点：**

| Provider | 接入方式 | 关键点 |
|----------|---------|--------|
| **SearXNG** | 自建实例 HTTP `GET /search?format=json` | Docker 部署，无配额限制，需在 settings.yml 启用 json 输出 |
| **Google** | Custom Search JSON API | `https://www.googleapis.com/customsearch/v1?key=KEY&cx=CX&q=...`，每天 100 次免费 |
| **Bing** | Bing Web Search API v7 | Header `Ocp-Apim-Subscription-Key: KEY`，Azure 订阅，每月 1000 次免费档 |

### 2.6 缓存设计

| Key 模式 | 数据类型 | TTL | 用途 | 更新策略 |
|----------|---------|-----|------|---------|
| `mysearch:result:{md5(q + page + size + lang + safesearch + providers)}` | String(JSON) | 30 min | 查询结果缓存 | 写入时更新，`nocache=true` 绕过 |
| `mysearch:rate:{ip}` | String(counter) | 1 min | IP 限流（如 30 次/分钟） | 递增 + 过期 |
| `mysearch:provider:{name}:fail` | String(counter) | 5 min | Provider 失败计数（熔断） | 递增，超阈值短路降级 |

**注意事项：**
- **缓存穿透**：空结果也缓存（TTL 缩短至 5 min）
- **缓存击穿**：热点 key 用 Redisson 分布式锁或 `SETNX` 单飞
- **缓存雪崩**：TTL 加随机抖动（± 10%）
- **序列化**：统一用 Jackson，禁用 Java 原生序列化

### 2.7 并发编排设计

使用 `CompletableFuture` 并发调用三个 Provider，任一失败不影响整体：

```java
// 伪代码
List<CompletableFuture<List<RawSearchResult>>> futures = providers.stream()
    .filter(SearchProvider::enabled)
    .map(p -> CompletableFuture
        .supplyAsync(() -> p.search(request), executor)
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .exceptionally(ex -> {
            log.warn("Provider {} failed: {}", p.name(), ex.getMessage());
            metrics.recordFailure(p.name());
            return Collections.emptyList();
        }))
    .toList();

CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

List<RawSearchResult> all = futures.stream()
    .map(CompletableFuture::join)
    .flatMap(List::stream)
    .toList();
```

- 线程池：`ThreadPoolTaskExecutor`，core=8, max=32, queue=100
- 单 Provider 超时：默认 2500ms，可配置
- JDK 21 可切换为虚拟线程（`Executors.newVirtualThreadPerTaskExecutor()`）

### 2.8 结果处理设计

#### 2.8.1 标准化（Normalizer）

- **URL 归一化**：
  - 转小写 scheme + host
  - 去除 `utm_*`、`fbclid`、`gclid`、`ref` 等 tracking 参数
  - 去除末尾 `/`（首页除外）
  - 解析并保留必要参数
- **标题/摘要清洗**：去除 HTML 标签、多余空白、控制字符
- **时间解析**：ISO 8601 / RFC 3339 兼容

#### 2.8.2 去重（Deduplicator）

两层去重：

**Layer 1: URL 精确去重**
- 以归一化 URL 作为 key，多个 Provider 命中同一 URL 时合并
- 合并策略：保留 rank 最小者，`sources` 集合累加

**Layer 2: 内容近似去重（SimHash）**
- 对 `title + snippet` 计算 64 位 SimHash
- 中文用 HanLP/Jieba 分词，英文按空格 + 停用词过滤
- 汉明距离 ≤ 3 判定为近似重复，合并到 rank 更高的结果
- 计算缓存：URL → SimHash 存 Redis（TTL 7 天）避免重复计算

#### 2.8.3 融合排序（RRF）

Reciprocal Rank Fusion 算法：

score(d) = Σ (w_i / (k + rank_i(d)))，其中 i 遍历所有 Provider

- `R` 是所有 Provider 集合，`rank_i(d)` 是文档 d 在引擎 i 中的排名（1-based）
- `k` 为平滑常数（默认 60）
- 未命中该引擎的文档，该引擎贡献分为 0
- 多引擎命中的文档天然分数更高（体现"多路验证"）
- 支持权重：如 Google=1.0、Bing=0.9、SearXNG=0.8

**伪代码：**

```java
Map<String, Double> scores = new HashMap<>();  // url -> rrf score
for (SearchProvider p : providers) {
    List<RawSearchResult> results = byProvider.get(p.name());
    for (int i = 0; i < results.size(); i++) {
        String url = results.get(i).getUrl();
        scores.merge(url, weight(p) / (K + (i + 1)), Double::sum);
    }
}
// 按 scores 降序输出
```

### 2.9 核心代码设计

#### 2.9.1 包结构

```
com.mysearch/
├── MysearchApplication.java          # 启动类
├── controller/                        # Web/API 层
│   ├── SearchController.java         # /api/search, /api/suggest
│   └── PageController.java           # 前端页面路由
├── service/                           # 业务层
│   ├── SearchOrchestrator.java       # 核心编排
│   ├── SearchCacheService.java       # 缓存封装
│   └── impl/
│       └── SearchOrchestratorImpl.java
├── provider/                          # Provider 适配
│   ├── SearchProvider.java           # 接口
│   ├── AbstractSearchProvider.java   # 基类（HTTP、超时、日志）
│   ├── SearxngProvider.java
│   ├── GoogleProvider.java
│   ├── BingProvider.java
│   └── config/
│       ├── SearxngProperties.java
│       ├── GoogleProperties.java
│       └── BingProperties.java
├── rank/                              # 结果处理
│   ├── ResultProcessor.java          # 编排：normalize → dedup → rank
│   ├── UrlNormalizer.java
│   ├── TextCleaner.java
│   ├── Deduplicator.java
│   ├── SimHasher.java
│   └── RRFRanker.java
├── model/                             # DTO / VO
│   ├── SearchRequest.java
│   ├── SearchResponse.java
│   ├── RawSearchResult.java
│   ├── NormalizedResult.java
│   └── ProviderStatus.java
├── common/
│   ├── ApiResponse.java              # 统一响应包装 {code,msg,data}
│   ├── ErrorCode.java
│   ├── BizException.java
│   └── Constants.java
├── config/
│   ├── WebClientConfig.java          # WebClient Bean + 连接池
│   ├── ExecutorConfig.java           # 线程池 Bean
│   ├── RedisConfig.java
│   └── OpenApiConfig.java
├── exception/
│   └── GlobalExceptionHandler.java   # @ControllerAdvice
└── util/
    ├── Md5Util.java
    └── TimeUtil.java
```

#### 2.9.2 关键类清单

| 类名 | 类型 | 职责 |
|------|------|------|
| `SearchController` | Controller | 参数校验、调用编排、返回统一响应 |
| `SearchOrchestrator` | Service | 编排：缓存查询 → 并发 Provider → 结果处理 → 缓存写入 |
| `SearchProvider` | Interface | Provider 统一契约 |
| `SearxngProvider` / `GoogleProvider` / `BingProvider` | Impl | 三个引擎适配实现 |
| `ResultProcessor` | Component | 标准化 → 去重 → 排序的编排 |
| `UrlNormalizer` | Component | URL 归一化 |
| `SimHasher` | Component | 64 位 SimHash 计算 |
| `Deduplicator` | Component | URL 去重 + SimHash 近似去重 |
| `RRFRanker` | Component | RRF 融合排序 |
| `SearchCacheService` | Service | Redis 缓存读写 |
| `GlobalExceptionHandler` | Advice | 全局异常兜底，返回统一响应 |

#### 2.9.3 关键伪代码

**SearchOrchestrator：**

```java
public SearchResponse search(SearchRequest req) {
    long start = System.currentTimeMillis();
    String cacheKey = buildCacheKey(req);

    // 1. 缓存查询
    if (!req.isNocache()) {
        SearchResponse cached = cacheService.get(cacheKey);
        if (cached != null) {
            cached.setCached(true);
            return cached;
        }
    }

    // 2. 并发调用 Provider
    Map<String, List<RawSearchResult>> byProvider =
        orchestrator.invokeProvidersConcurrently(req);

    // 3. 全部失败 -> 抛异常
    if (byProvider.values().stream().allMatch(List::isEmpty)) {
        throw new BizException(ErrorCode.ALL_PROVIDERS_FAILED);
    }

    // 4. 结果处理
    List<NormalizedResult> processed = resultProcessor.process(byProvider);

    // 5. 分页
    int from = (req.getPage() - 1) * req.getSize();
    int to = Math.min(from + req.getSize(), processed.size());
    List<NormalizedResult> pageData =
        from >= processed.size() ? List.of() : processed.subList(from, to);

    // 6. 组装响应
    SearchResponse resp = SearchResponse.builder()
        .query(req.getQ())
        .total(processed.size())
        .page(req.getPage())
        .size(req.getSize())
        .elapsedMs(System.currentTimeMillis() - start)
        .cached(false)
        .providers(buildProviderStatus(byProvider))
        .results(pageData)
        .build();

    // 7. 写缓存（异步）
    cacheService.putAsync(cacheKey, resp, computeTtl(processed.isEmpty()));

    return resp;
}
```

### 2.10 前端设计

MVP 阶段前端极简：

- 单页面 `index.html` + Thymeleaf 模板（或纯静态 + fetch）
- 搜索框 + 结果列表 + 分页
- 结果卡片展示：`title` / `url` / `snippet` / `sources badge`（多引擎命中显示徽标）
- 加载态：Loading 动画
- 错误态：友好提示 + 重试按钮

```
┌──────────────────────────────────────┐
│  MySearch                            │
│  ┌────────────────────────┐ ┌─────┐ │
│  │ 输入关键词...           │ │搜索 │ │
│  └────────────────────────┘ └─────┘ │
│                                      │
│  找到 27 条结果 (1.2s) [Google][Bing]│
│                                      │
│  ▸ Spring Boot 并发编程最佳实践        │
│    example.com  ✓Google ✓Bing        │
│    本文介绍 Spring Boot 中并发...      │
│                                      │
│  ▸ Java CompletableFuture 详解       │
│    ...                               │
│                                      │
│  [< 1 2 3 >]                         │
└──────────────────────────────────────┘
```

### 2.11 部署设计

**Docker Compose 拓扑：**

```yaml
services:
  mysearch:       # Spring Boot 应用（8080）
  redis:          # 缓存（6379）
  searxng:        # SearXNG 自建实例（8888）
```

**环境变量：**

| 变量 | 说明 |
|------|------|
| `GOOGLE_API_KEY` | Google Custom Search API Key |
| `GOOGLE_CX` | Google Custom Search Engine ID |
| `BING_API_KEY` | Bing Web Search API Key |
| `SEARXNG_BASE_URL` | SearXNG 实例地址，如 `http://searxng:8080` |
| `REDIS_HOST` / `REDIS_PORT` | Redis 连接 |
| `SPRING_PROFILES_ACTIVE` | 环境（dev/prod） |

---

## 三、非功能性设计

### 3.1 性能评估

| 指标 | 目标值 | 说明 |
|------|--------|------|
| 接口 P95 RT | < 3000 ms | 主要受 Provider 网络耗时限制 |
| 接口 P99 RT | < 5000 ms | 单 Provider 超时 2500ms 兜底 |
| 缓存命中率 | > 40% | 依赖热词分布 |
| 缓存命中 RT | < 100 ms | 直接返回 Redis |
| QPS | 50（MVP） | 主要受 Google/Bing 免费配额限制 |
| 冷启动 | < 15 s | Spring Boot 3 |

**瓶颈分析：**
1. Provider 网络调用（不可控）→ 并发 + 超时降级
2. Google 每天 100 次免费配额 → 缓存 + 付费扩容 / 只用 SearXNG
3. Bing 每月 1000 次免费配额 → 同上

### 3.2 安全设计

- [ ] API Key 通过环境变量或 Vault 注入，禁止硬编码进代码库
- [ ] `application.yml` 中 API Key 用占位符 `${GOOGLE_API_KEY}`
- [ ] IP 限流：Redis 计数器，单 IP 默认 30 次/分钟，超限返回 429
- [ ] 输入校验：`q` 长度 ≤ 200，防止超长查询打爆下游
- [ ] 输出编码：前端渲染 snippet 时转义 HTML，防止 XSS
- [ ] SSRF 防护：Provider 请求地址固定，不接受用户输入
- [ ] CORS：MVP 阶段同源，如需跨域用 `@CrossOrigin` 白名单
- [ ] 生产环境建议加 Basic Auth 或 IP 白名单，避免被公网滥用刷爆配额

### 3.3 异常处理

| 异常场景 | 处理策略 | 降级方案 |
|---------|---------|---------|
| 单 Provider 超时 | `orTimeout(2500ms)` + `exceptionally` 捕获 | 返回空列表，其他 Provider 结果照常融合 |
| 单 Provider HTTP 5xx | 重试 1 次（指数退避 200ms） | 同上 |
| 单 Provider 认证失败 | 记录告警，熔断 5 分钟 | 短时间内跳过该 Provider |
| 全部 Provider 失败 | 抛 `BizException(ALL_PROVIDERS_FAILED)` | 前端提示"稍后重试" |
| Redis 不可用 | 捕获异常继续（降级为无缓存） | 只影响性能，不影响功能 |
| 参数校验失败 | `@Valid` + `MethodArgumentNotValidException` | 返回 code=400 |
| 未知异常 | `GlobalExceptionHandler` 兜底 | 返回 code=500，日志告警 |

### 3.4 幂等设计

搜索接口为 **只读接口**，天然幂等。

写场景（本期无）：
- 缓存写入：同 key 覆盖写，天然幂等
- 后续如加"搜索历史"，用 `query + userId` 唯一键

### 3.5 可观测性

**日志规范：**
- 使用 `@Slf4j` + SLF4J
- 关键节点：请求入口、Provider 调用前后、结果处理耗时、缓存命中、异常
- MDC 注入 `traceId`（UUID 前 8 位），便于串联日志

**日志格式：**
```
[traceId=abc123] [q=spring boot] [provider=google] [elapsed=610ms] [count=10] success
```

**监控指标（Micrometer + Actuator）：**

| 指标 | 类型 | 说明 |
|------|------|------|
| `mysearch.request.total` | Counter | 请求总数（tag: cached, success） |
| `mysearch.request.latency` | Timer | 端到端耗时 |
| `mysearch.provider.latency` | Timer | 单 Provider 耗时（tag: name） |
| `mysearch.provider.error` | Counter | Provider 失败数（tag: name, type） |
| `mysearch.cache.hit` | Counter | 缓存命中 |
| `mysearch.cache.miss` | Counter | 缓存未命中 |
| `mysearch.result.count` | Distribution | 结果条数分布 |

### 3.6 配置清单

`application.yml` 核心配置项：

```yaml
mysearch:
  providers:
    searxng:
      enabled: true
      base-url: ${SEARXNG_BASE_URL:http://localhost:8888}
      timeout-ms: 2500
      weight: 0.8
    google:
      enabled: true
      api-key: ${GOOGLE_API_KEY}
      cx: ${GOOGLE_CX}
      timeout-ms: 2500
      weight: 1.0
    bing:
      enabled: true
      api-key: ${BING_API_KEY}
      endpoint: https://api.bing.microsoft.com/v7.0/search
      timeout-ms: 2500
      weight: 0.9
  rank:
    rrf-k: 60
    simhash-threshold: 3          # 汉明距离阈值
    simhash-cache-days: 7
  cache:
    ttl-seconds: 1800             # 30 min
    empty-ttl-seconds: 300        # 5 min
    jitter-ratio: 0.1
  rate-limit:
    enabled: true
    per-minute: 30
  executor:
    core-size: 8
    max-size: 32
    queue-capacity: 100
```

---

## 四、影响范围与兼容性

### 4.1 影响范围

新项目，无历史影响。

### 4.2 兼容性

- **接口兼容**：`/api/search` 版本化预留 `v1` 前缀（MVP 可省略），未来加参数保持向后兼容
- **数据兼容**：Redis 缓存 key 带版本前缀 `mysearch:v1:...`，方便升级清空
- **配置兼容**：所有 Provider 通过 `enabled` 开关控制，未配置 Key 时自动禁用

---

## 五、上线方案

### 5.1 上线步骤

1. [ ] 准备部署环境（Docker + Docker Compose）
2. [ ] 申请 Google Custom Search API Key + 创建 CSE，获取 `cx`
3. [ ] 申请 Azure Bing Search API Key（Free F1 层）
4. [ ] `docker-compose up -d redis searxng` 起依赖
5. [ ] 配置 SearXNG `settings.yml`，启用 `json` 输出格式
6. [ ] 打包应用 `mvn clean package -DskipTests`
7. [ ] 配置环境变量（`GOOGLE_API_KEY` / `GOOGLE_CX` / `BING_API_KEY`）
8. [ ] `docker-compose up -d mysearch`
9. [ ] 验证 `curl http://localhost:8080/actuator/health`
10. [ ] 验证 `curl 'http://localhost:8080/api/search?q=test'`
11. [ ] 浏览器打开首页手工验证

### 5.2 回滚方案

| 步骤 | 操作 | 说明 |
|------|------|------|
| 1 | `docker-compose down mysearch` | 停止新版应用 |
| 2 | `docker tag mysearch:prev mysearch:latest` | 回滚镜像标签 |
| 3 | `docker-compose up -d mysearch` | 起旧版 |
| 4 | `redis-cli FLUSHDB`（可选） | 清理不兼容缓存 |

### 5.3 数据迁移

MVP 无历史数据。

---

## 六、测试要点

| 测试场景 | 预期结果 | 优先级 |
|---------|---------|--------|
| 正常查询（三 Provider 全部返回） | 结果融合排序，多引擎命中排前 | P0 |
| 单 Provider 超时 | 其余 Provider 结果正常返回，`providers` 状态标记 timeout | P0 |
| 全部 Provider 失败 | 返回 502，前端友好提示 | P0 |
| 缓存命中 | `cached=true`，RT < 100ms | P0 |
| `nocache=true` | 绕过缓存 | P1 |
| URL 归一化（含 utm 参数） | 相同 URL 合并，sources 累加 | P0 |
| SimHash 近似去重 | 汉明距离 ≤ 3 的结果合并 | P1 |
| 参数校验（q 为空、size 越界） | 返回 400 + 明确错误信息 | P1 |
| IP 限流 | 超过阈值返回 429 | P1 |
| 分页 | page=2 返回第 11-20 条 | P1 |
| 空结果查询（生僻词） | 返回空列表，缓存 5 min | P2 |
| 中文长查询 | 分词正确，SimHash 计算成功 | P1 |
| 特殊字符查询（引号、反斜杠） | URL 编码正确，不报错 | P1 |
| 并发压测（50 QPS × 5 min） | 无内存泄漏，P95 < 3s | P1 |
| Redis 宕机 | 应用降级为无缓存模式，功能可用 | P2 |
| Provider API Key 失效 | 熔断 5 分钟，其他 Provider 正常 | P2 |

---

## 七、开发排期建议（MVP 单人 1-2 周）

| 阶段 | 任务 | 工时 |
|------|------|------|
| Day 1 | 项目脚手架、多模块 Maven 结构、Docker Compose | 0.5d |
| Day 1-2 | Provider 接口 + SearXNG 适配 + 单元测试 | 1d |
| Day 2-3 | Google + Bing Provider 适配 + 单元测试 | 1d |
| Day 3-4 | 并发编排、超时降级、SearchOrchestrator | 1d |
| Day 4-5 | URL 归一化、SimHash、去重、RRF 排序 | 1.5d |
| Day 5-6 | Redis 缓存、限流、异常处理、可观测性 | 1d |
| Day 6-7 | 前端页面（Thymeleaf）+ API 联调 | 0.5d |
| Day 7-8 | 集成测试、Docker 部署、文档 | 1d |
| **合计** | | **7.5d** |

---

## 八、后续演进方向（非本次范围）

1. **排序模型升级**：RRF → Learning-to-Rank（LambdaMART）→ Embedding 语义重排
2. **Provider 扩展**：DuckDuckGo、Brave Search、Yandex、Baidu
3. **个性化**：用户历史、点击反馈、地域偏好
4. **垂直搜索**：图片、新闻、代码（GitHub/StackOverflow）
5. **持久化**：MySQL 存搜索日志、热词库、URL 黑名单
6. **高可用**：多副本部署 + Nginx 负载均衡 + 熔断限流升级（Sentinel/Resilience4j）
7. **LLM 增强**：搜索结果摘要生成（RAG）、多轮对话式搜索

---

## 附录 A：参考资源

- SearXNG 官方文档：https://docs.searxng.org/
- Google Custom Search JSON API：https://developers.google.com/custom-search/v1/overview
- Bing Web Search API：https://learn.microsoft.com/en-us/bing/search-apis/bing-web-search/
- Reciprocal Rank Fusion 论文：Cormack et al., SIGIR 2009
- SimHash：Charikar, "Similarity Estimation Techniques from Rounding Algorithms", STOC 2002

## 附录 B：核心算法参考实现

### B.1 SimHash（64 位）

```java
public long simHash(String text, int featureBits) {
    List<String> tokens = tokenizer.tokenize(text);   // 分词
    int[] v = new int[featureBits];                    // 64 维向量
    for (String token : tokens) {
        long hash = murmurHash64(token);
        for (int i = 0; i < featureBits; i++) {
            if (((hash >> i) & 1L) == 1L) v[i]++;
            else v[i]--;
        }
    }
    long fingerprint = 0L;
    for (int i = 0; i < featureBits; i++) {
        if (v[i] > 0) fingerprint |= (1L << i);
    }
    return fingerprint;
}

public int hammingDistance(long a, long b) {
    return Long.bitCount(a ^ b);
}
```

### B.2 URL 归一化

```java
public String normalize(String rawUrl) {
    URI uri = URI.create(rawUrl);
    String scheme = uri.getScheme().toLowerCase();
    String host = uri.getHost().toLowerCase();
    if (host.startsWith("www.")) host = host.substring(4);
    String path = uri.getPath();
    if (path == null || path.isEmpty()) path = "/";
    if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

    // 过滤 tracking 参数
    Map<String, String> params = parseQuery(uri.getQuery());
    params.keySet().removeIf(k ->
        k.startsWith("utm_") || TRACKING_PARAMS.contains(k.toLowerCase()));

    return scheme + "://" + host + path + buildQuery(params);
}
```

### B.3 RRF 融合排序

```java
public List<NormalizedResult> rank(Map<String, List<RawSearchResult>> byProvider,
                                   Map<String, Double> weights, int k) {
    Map<String, Double> scores = new HashMap<>();
    Map<String, NormalizedResult> merged = new HashMap<>();

    for (Map.Entry<String, List<RawSearchResult>> e : byProvider.entrySet()) {
        String provider = e.getKey();
        double w = weights.getOrDefault(provider, 1.0);
        List<RawSearchResult> list = e.getValue();
        for (int i = 0; i < list.size(); i++) {
            NormalizedResult r = normalize(list.get(i));
            String key = r.getUrl();
            scores.merge(key, w / (k + i + 1), Double::sum);
            merged.computeIfAbsent(key, x -> r).addSource(provider);
        }
    }

    return scores.entrySet().stream()
        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
        .map(e -> merged.get(e.getKey()).withScore(e.getValue()))
        .toList();
}
```
