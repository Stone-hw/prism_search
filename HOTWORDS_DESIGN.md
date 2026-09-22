# PrismSearch 热词管理方案

| 项目 | 内容 |
|------|------|
| 需求名称 | 搜索热词动态管理 |
| 所属服务 | prismsearch |
| 创建日期 | 2026-09-22 |
| 状态 | 设计中 |

---

## 一、现状与问题

当前 `/api/suggest` 接口从 `application.yml` 的 `prismsearch.suggest.hot-words` 读取静态热词列表，在 `SearchController.suggest()` 中做内存前缀匹配。

**问题：**
1. 热词写死在配置文件中，修改需要重启服务
2. 无法反映用户真实搜索行为
3. 没有运营干预能力（置顶/下架特定词）

---

## 二、方案设计

### 2.1 整体架构

```
用户搜索 → SearchOrchestrator.search()
                ↓ 异步记录
          Redis ZSet: ps:searchlog (score=搜索次数, TTL=7天)
                ↓ 定时聚合（每小时）
          HotWordAggregator → 取 Top N 写入 ps:hotwords:auto
                ↓ 合并
          /api/suggest 查询时:
            ps:hotwords:manual (运营手动词, 永久)
            ∪ ps:hotwords:auto  (自动聚合词, 每小时刷新)
                ↓ 前缀匹配 + 按 score 降序
            返回建议列表
```

### 2.2 Redis 数据结构

| Key | 类型 | 用途 | TTL |
|-----|------|------|-----|
| `ps:v1:hotwords:manual` | ZSet | 运营手动管理的热词，score=权重(默认1000) | 永久 |
| `ps:v1:hotwords:auto` | ZSet | 自动聚合的热词，score=搜索频次 | 永久(定时覆盖) |
| `ps:v1:searchlog` | ZSet | 搜索日志，member=查询词，score=累计次数 | 7天 |

**Key 设计说明：**
- 统一使用 `ps:v1:` 前缀，与现有 `Constants.CACHE_PREFIX` 保持一致
- `manual` 和 `auto` 分离，便于独立管理和调试
- `searchlog` 设 7 天 TTL，避免无限膨胀

### 2.3 核心流程

#### 2.3.1 搜索词记录

在 `SearchOrchestratorImpl.search()` 成功返回后，异步将查询词写入 `ps:v1:searchlog`：

```java
// 使用 ZINCRBY 原子递增
redis.opsForZSet().incrementScore("ps:v1:searchlog", query.toLowerCase(), 1);
redis.expire("ps:v1:searchlog", Duration.ofDays(7));
```

**注意**：`ZINCRBY` + `EXPIRE` 需要原子化。使用 Lua 脚本：

```lua
redis.call('zincrby', KEYS[1], 1, ARGV[1])
redis.call('expire', KEYS[1], ARGV[2])
return 1
```

#### 2.3.2 定时聚合

使用 `@Scheduled` 每小时执行一次：

1. 从 `ps:v1:searchlog` 取 `ZREVRANGE 0 49`（Top 50 高频词）
2. 写入 `ps:v1:hotwords:auto`（先 `DEL` 再批量 `ZADD`，Pipeline 执行）
3. 日志记录聚合结果

#### 2.3.3 建议查询

`/api/suggest` 查询逻辑改为：

1. 并行读取 `ps:v1:hotwords:manual` 和 `ps:v1:hotwords:auto`
2. 合并为统一 Map（manual 优先，同 key 取 manual 的 score）
3. 按前缀过滤（case-insensitive startsWith）
4. 按 score 降序排序
5. 取 Top N 返回
6. 如果 Redis 不可用，降级回配置文件中的静态热词

#### 2.3.4 Admin API

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/admin/hotwords` | 查看当前所有热词（合并 manual + auto） |
| `POST` | `/api/admin/hotwords` | 手动添加热词 `{ "word": "xxx", "weight": 1000 }` |
| `DELETE` | `/api/admin/hotwords?word=xxx` | 从 manual 中移除指定热词 |

Admin API 操作的是 `ps:v1:hotwords:manual`，不影响自动聚合。

### 2.4 配置变更

`application.yml` 中 `prismsearch.suggest` 新增配置项：

```yaml
prismsearch:
  suggest:
    enabled: true                    # 是否启用 Redis 热词（false 则降级为静态列表）
    limit: 8                         # 默认返回条数
    aggregate-cron: "0 0 * * * ?"   # 聚合定时任务 cron
    aggregate-top-n: 50             # 聚合时取 Top N 高频词
    searchlog-ttl-days: 7           # 搜索日志保留天数
    hot-words:                       # 静态降级列表（Redis 不可用时使用）
      - spring boot
      - redis
      - ...
```

移除原有的静态热词作为主数据源的逻辑，仅保留为降级兜底。

### 2.5 涉及文件

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `cache/HotWordService.java` | 新增 | 热词 CRUD + 查询 + 聚合核心服务 |
| `web/SearchController.java` | 修改 | `/api/suggest` 改为调用 HotWordService |
| `web/AdminController.java` | 新增 | 热词管理 Admin API |
| `service/impl/SearchOrchestratorImpl.java` | 修改 | 搜索成功后异步记录搜索词 |
| `common/Constants.java` | 修改 | 新增 Redis key 常量 |
| `config/PrismsearchProperties.java` | 修改 | Suggest 内部类新增字段 |
| `application.yml` | 修改 | suggest 配置项调整 |

### 2.6 降级策略

```
HotWordService.suggest(q, limit)
  ├─ Redis 可用 → 从 manual + auto ZSet 查询 → 返回
  └─ Redis 不可用 → 从 application.yml 静态列表查询 → 返回
```

Redis 故障不影响搜索主流程，suggest 接口始终可用。

---

## 三、实施计划

1. 新增 `HotWordService`：Redis 热词存储 + 查询 + 聚合
2. 改造 `SearchController.suggest()`：接入 HotWordService
3. 搜索词记录：`SearchOrchestratorImpl` 中异步写入 searchlog
4. 定时聚合：`@Scheduled` 每小时从 searchlog 聚合到 auto ZSet
5. Admin API：`AdminController` 提供热词 CRUD
6. 配置调整 + 静态降级兜底
7. 单元测试
