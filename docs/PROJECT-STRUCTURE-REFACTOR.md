# 工程重构方案：包结构 + 逐文件映射（2026-09-30）

> 老板指令：**本项目代码改成跟 OceanEngine 一样的风格**，按 `controller / service / config / dto / exception`
> 分类，按工程思想重构代码与项目结构。
> 本文 = 目标结构 + 逐文件「源 → 目标」映射。**已确认的前提**：`plant_search/` 整体弃用、`chen/` 与 `ccreate_plan/` 保留。

---

## 一、目标结构

```
com.aolandai.dingtou
│
├── DingtouApplication.java          ★ 启动类
│
├── controller/                      ★ 老板要的第 1 个桶 —— Web 层，只收请求发应答，不含业务
│     TaskController                 POST /api/v1/task/{code} · GET /api/v1/task/{id}
│     AuthInterceptor                X-Auth-Token 鉴权
│
├── service/                         ★ 第 2 个桶 —— 全部业务逻辑
│   ├── task/                        任务分发（加任务 = 加一个类，不改分发器）
│   │     TaskHandler（接口）· TaskDispatcher · TaskRegistry
│   │     handler/                   PingHandler / DecideHandler / AggregateHandler ...
│   ├── qianchuan/                   ★ 千川能力（本项目核心，含迁入的同事代码）
│   │     QianchuanPlanService            查计划
│   │     QianchuanOverallVideoCreateService  建乘方计划
│   │     ProductIdFromVideo              视频 → 商品 ID
│   │     SuixintuiOrderService           账户自动发现
│   │     SuixintuiUniOrderByAwemeService ★ 按抖音号查随心推订单
│   │     SuixintuiOrderDetailService     订单详情
│   ├── aggregate/                   按【达人 + 商品】聚合累计笔数 N
│   ├── decision/                    ★ 纯函数、无 IO：A/B/C1/C2 判定 + 计划命名
│   ├── normalize/                   抖音号 / 商品名 / 账户名 归一
│   └── sheet/                       定投素材表读写（POI）
│
├── source/                          数据源：Excel → 对象
│     ExcelReader · DeliverySheetReader · AccountDictReader
│
├── store/                           持久化（JSONL + 内存索引 + 原子写）
│     JsonlStore（泛型基类）· RequestLedger · PlanLedger · OrderSnapshotStore
│
├── client/                          接入基础设施（外部系统）
│     ApiErrors · QianchuanTokenClient · SuixintuiTokenClient · HttpSender
│
├── config/                          ★ 第 3 个桶 —— 配置
│     DingtouProperties · QianchuanProperties · SourceProperties · TaskProperties
│     DingtouConfig（Bean 装配）
│
├── dto/                             ★ 第 4 个桶 —— 跨层传值对象（只放数据，不放逻辑）
│     api/         ApiRequest · ApiResponse · ErrorCode
│     qianchuan/   OverallVideoCreateParam · CreatePlanResult · QianchuanPlanQueryResult
│                  · SuixintuiUniOrderListRequest
│     sheet/       DeliveryRow · OrderRow · AccountRow
│     DecisionResult · OrderSnapshot · MaterialInfo
│
├── exception/                       ★ 第 5 个桶 —— 异常
│     BizException · RateLimitException · AmbiguousPlanException
│
└── tools/                           调试探针（不进生产路径）
```

**为什么是 5 + 3**：老板点名的 5 个桶是**业务五件套**；
`source/`（读表）、`store/`（落盘）、`client/`（调外部接口）、`tools/`（调试）是**工程必需的基础设施层**，
塞进 5 个桶里会让 `service/` 变成垃圾桶。**如果老板希望严格只保留 5 个桶，我可以把 source/store 折进 service/ 下。**

**依赖方向严格单向**：
```
controller → service/task → service/{decision,aggregate,qianchuan,normalize}
                                    ↓
                        source / store / client / dto
       decision 不依赖任何 IO（纯函数，单测 100% 覆盖）
```

---

## 二、逐文件映射

### 2.1 dingtou 现有代码（同事的 demo，`com.example.oceanengine`）

| # | 现路径（`src/main/java/com/example/oceanengine/`） | 行数 | → 目标（`com.aolandai.dingtou.*`） | 处理 |
|---|---|---|---|---|
| 1 | `chen/QianchuanPlanService.java` | 181 | `service/qianchuan/` | 改包名 |
| 2 | `chen/QianchuanPlanQueryResult.java` | 66 | `dto/qianchuan/` | 改包名 |
| 3 | `chen/AmbiguousPlanException.java` | — | `exception/` | 改包名 |
| 4 | `chen/ProductIdFromVideo.java` | 743 | `service/qianchuan/` | 改包名 + ⚠️ **硬编码常量抽进配置** |
| 5 | `ccreate_plan/QianchuanOverallVideoCreateService.java` | 345 | `service/qianchuan/` | 改包名 |
| 6 | `ccreate_plan/OverallVideoCreateParam.java` | 587 | `dto/qianchuan/` | 改包名 |
| 7 | `ccreate_plan/CreatePlanResult.java` | 89 | `dto/qianchuan/` | 改包名 |
| 8 | `ccreate_plan/CreatePlanDemo.java` | 736 | `tools/` | 调试入口，移出生产路径 |
| 9 | `service/suixintui/SuixintuiUniOrderByAwemeService.java` | 420 | `service/qianchuan/` | 改包名 + 去 `System.out` |
| 10 | `service/suixintui/SuixintuiOrderService.java` | 340 | `service/qianchuan/` | 改包名 + 去 `System.out` |
| 11 | `service/suixintui/SuixintuiOrderDetailService.java` | 74 | `service/qianchuan/` | 改包名 |
| 12 | `client/QianchuanTokenClient.java` | 95 | `client/` | 与 OceanEngine 版**比对合并** |
| 13 | `client/SuixintuiTokenClient.java` | 91 | `client/` | 与 OceanEngine 版**比对合并** |
| 14 | `model/SuixintuiUniOrderListRequest.java` | 170 | `dto/qianchuan/` | 改包名 |
| 15 | `domain/SuixintuiReinvestCandidateSelector.java` | 216 | ❌ **删除** | 老业务 ROI 追投规则，本需求用不到 |
| 16-22 | `plant_search/`（7 个文件） | ~1443 | ❌ **整体移出编译路径** | 已确认弃用。移到项目根 `deprecated/`，**不删除**（无 git，留退路） |
| 23-29 | `test/`（7 个探针） | ~900 | `tools/` | 探针类，移出生产路径 |

> ⚠️ 注意：`client/QianchuanTokenClient` 与 `plant_search/QianchuanTokenClient` **重名**，
> 弃用 `plant_search/` 后只剩 `client/` 一个，不冲突。

### 2.2 从 OceanEngine 迁入

| # | 源（`OceanEngine/src/main/java/com/aolandai/oceanengine/`） | → 目标 | 处理 |
|---|---|---|---|
| 30 | `client/ApiErrors.java` | `client/` | **原样照搬**，改包名 |
| 31 | `client/RateLimitException.java` | `exception/` | 改包名（它继承 SDK 的 `ApiException`，与 `client/ApiErrors` 协作） |
| 32 | `storage/ReinvestRecordStore.java` | `store/JsonlStore.java` | **抽成泛型基类**（原子写 + 内存索引） |
| 33 | `webhook/WebhookNotificationStore.java` | `store/` | 抄幂等落盘范式 |
| 34 | `webhook/HttpWebhookSender.java` | `client/HttpSender.java` | 抄 HTTP 封装（超时/重试/自定义头） |
| 35 | `scheduler/SuixintuiReinvestProperties.java` | `config/` | **拆分**成 4 个 Properties |
| 36 | `scheduler/SuixintuiReinvestScheduler.java` | `scheduler/InstanceLock.java` | **只抽 `FileChannel.tryLock()`** |
| 37 | `report/DailyReinvestReportService.java` | `source/ExcelReader.java` | **只抄 POI 用法**（⚠️ 换 `poi-ooxml`） |
| 38 | `client/QianchuanTokenClient.java` | `client/` | 与 dingtou 版比对合并 |
| 39 | `client/SuixintuiTokenClient.java` | `client/` | 与 dingtou 版比对合并 |

**明确不迁**：`domain/ReinvestSafetyGuard`、`domain/SuixintuiReinvestCandidateSelector`（老业务 ROI 规则）、
`@Scheduled` 轮询、`plant_search/`。

---

## 三、风格约定（老板已确认，2026-09-30 10:10）

| 项 | 约定 |
|---|---|
| Lombok | ❌ **不用**（显式 getter/setter + `toString()`） |
| 日志 | ✅ **slf4j + logback**（不再 `System.out.println`） |
| Javadoc | ✅ 中文超详尽：类头写链路 `<ol>` + 坑点 `<ul>` + yml 示例 |
| `record` | 跨层传值 / 不可变值对象用 `record`；**Spring 配置类必须显式 getter/setter** |
| `var` | ✅ 只在**局部变量**用；字段/参数/返回值显式类型 |
| 区块注释 | ✅ 保留 `// ==================== 分组 ====================` |
| 异常 | ✅ `RateLimitException extends ApiException`（继承 SDK，`withRetry` 的 catch 不用改） |
| 重试 | 业务错不重试 / **限流不重试直接抛** / 瞬时错重试 3 次退避 1s·2s |
| 配置 | `@Validated` + `@ConfigurationProperties` + `@AssertTrue` 自检 + `describeXxx()` 启动横幅 |
| 存储 | JSONL + 内存索引 + `synchronized` + 临时文件 → `force(true)` → `ATOMIC_MOVE` |
| 魔法值 | 全部进配置或常量类（`ProductIdFromVideo` 是反面教材） |

---

## 四、执行顺序

1. 重写 `pom.xml`（坐标 + Spring Boot + 依赖）
2. 建新包骨架
3. 移动 + 改包名（现有代码）
4. 迁入 OceanEngine 基础设施
5. `plant_search/` + `domain/` + `test/` 移出编译路径
6. `mvn compile` 验证
