# 代码风格确认稿（待老板拍板 · 2026-09-30）

> **用途**：迁移 `OceanEngine` 代码前，先把「写出来的代码长什么样」定死，避免迁完再返工。
> 本文所有「现有风格」条目都**实测自 `D:\program\aolandai\OceanEngine` 的具体文件行号**，
> 不是我的推测。标 ⚠️ 的是**需要您拍板**的。

---

## 0. 结论先行

`OceanEngine` 的风格**整体很好，我建议大部分照搬**，只改三处：

| 改动 | 理由 |
|---|---|
| ① 日志 `System.out.println` → **slf4j + logback** | 现有工程日志全跑到 WinSW `.out.log`，`application.log` 几乎是空的（架构文档已定） |
| ② `@Scheduled` 定时轮询 → **HTTP 任务执行器**（影刀驱动） | 形态变了，这是需求 |
| ③ 包名 `com.aolandai.oceanengine` → `com.aolandai.dingtou` | 另起工程 |

其余（不用 Lombok、中文详注、`record`、构造器注入、原子写 JSONL、`@AssertTrue` 自检）**建议原样保留**。

---

## 1. 实测到的 OceanEngine 现有风格

### 1.1 依赖与构建（`OceanEngine/pom.xml`）

| 项 | 现状 |
|---|---|
| Spring Boot | **3.4.5**（parent） |
| JDK | **17**（`java.version`） |
| Web | ✅ `spring-boot-starter-web`（内嵌 Tomcat） |
| 监控 | ✅ `spring-boot-starter-actuator`（`/actuator/health`） |
| 校验 | ✅ `spring-boot-starter-validation`（配 `@AssertTrue` 自检） |
| SDK | `org.openapitools:oceanengine-mapi-java-client:1.1.96` |
| Excel | ⚠️ 只有 `poi` **5.3.0**（仅 `.xls`/HSSF）。**我们的表是 `.xlsx`，要补 `poi-ooxml`** |
| **Lombok** | ❌ **没有**（全项目零 lombok） |
| 仓库 | `https://artifact.bytedance.com/repository/releases/`（千川 SDK 必需） |

### 1.2 命名与分层

```
com.aolandai.oceanengine
├── Main.java / launcher/     启动
├── client/                   ApiErrors / RateLimitException / QianchuanTokenClient / SuixintuiTokenClient
├── domain/                   ReinvestSafetyGuard / SuixintuiReinvestCandidateSelector
├── model/                    SuixintuiUniOrderListRequest
├── report/                   DailyReinvestReportScheduler / DailyReinvestReportService
├── scheduler/                SuixintuiReinvestScheduler / SuixintuiReinvestProperties / ReinvestHealthIndicator
├── service/suixintui/        ★ SuixintuiUniOrderByAwemeService / SuixintuiOrderService ...
├── storage/                  ReinvestRecord / ReinvestRecordStore
└── webhook/                  WebhookNotificationStore / HttpWebhookSender / WebhookSender
```

- 包名全小写，按**职责**分包（不按 `controller/service/dao` 三层切）
- 类名：`名词 + 角色`（`XxxService` / `XxxStore` / `XxxProperties` / `XxxScheduler`）
- 常量：`private static final` + 全大写，**每条都写清为什么取这个值**
  （例：`ApiErrors.java:30` `DEVELOPER_PENALTY_MILLIS = 5 * 60_000L + 30_000L` —— 多留 30 秒缓冲）

### 1.3 不写 Lombok，显式 getter/setter

`SuixintuiReinvestProperties.java` 里 `AdvertiserTier`（155-197 行）与 `Webhook`（214-307 行）
**每个字段都手写 getter/setter**，并额外写 `toString()` 用于启动日志。

→ 与项目硬性规则一致，**照搬**。

### 1.4 Javadoc：中文、极详尽

这是最鲜明的特征。类头一律包含：

```
/** 一句话功能。
 *
 *  <p>整体链路（对应巨量引擎开放平台）：</p>
 *  <ol>
 *    <li>步骤一……</li>
 *  </ol>
 *
 *  <p>注意：</p>
 *  <ul>
 *    <li>⚠️ 坑点一……</li>
 *  </ul>
 */
```

实例：
- `SuixintuiUniOrderByAwemeService.java:30-55` —— 4 步链路 + 3 条注意（含「订单接口不支持按抖音号筛选，只能客户端匹配」）
- `RateLimitException.java:5-22` —— 把官方三层频控逐条抄进注释
- `ApiErrors.java:3-12` —— 附官方文档链接 + 「各服务原先各自维护一份，容易漂移」的原因说明

配置类还把 **application.yml 示例整段贴在类头**（`SuixintuiReinvestProperties.java:30-53`）。

### 1.5 异常与错误分类

- 工具类 `ApiErrors`：`public final class` + private 构造器，**集中**所有错误码判定
  - `isRateLimit(Long)` / `isBusinessError(String)` / `rateLimitOrNull(...)`
  - 注释写明「各服务原先各自维护一份 `isBusinessError`，容易漂移；此处统一」
- `RateLimitException extends ApiException`，内嵌 `enum Scope { CUSTOMER, DEVELOPER, GLOBAL }`，
  每个枚举带 `code / label / advice` 三属性 + `isAccountScoped()` 语义方法
- **重试策略**（`SuixintuiUniOrderByAwemeService.java:377-400`）：
  - 业务错误 → 不重试
  - 限流 `RateLimitException` → **不重试直接上抛**（原地重试只会加重惩罚）
  - 瞬时错误（网络/5xx）→ 重试 3 次，退避 1s / 2s

### 1.6 存储：JSONL + 内存索引 + 原子写

`ReinvestRecordStore.java`：
- `synchronized` 串行化所有读写；每次变更**全量重写**文件（记录量小，保证磁盘=内存）
- **原子落盘**（238-264 行）：临时文件 → `FileChannel.force(true)` → `ATOMIC_MOVE`（不支持则退化为普通 move）
- 启动加载**坏行直接抛异常拒绝启动**（`load()`，316 行）
- 内存索引 `byKey` / `pendingOrUnknownByOrder`，注释写明各自用途

### 1.7 配置：`@Validated` + `@AssertTrue` 自检 + `describeXxx()`

- `@ConfigurationProperties(prefix = "suixintui.reinvest")` + jakarta validation 注解（`@Positive` / `@NotBlank`…）
- **跨字段校验用 `@AssertTrue`**，message 写清「为什么不行」：
  `isSafetyLimitsConsistent()`（528-545）、`isWebhookConsistent()`（586-594）
- 注释写明「刻意让它在**启动阶段**就失败，否则会退化成每轮报错、很难发现」
- `describeTiers()` / `describeWebhook()` → 启动横幅用，「让『到底有没有生效』一眼可见」

### 1.8 现代 Java 语法（都在用）

| 语法 | 实例 |
|---|---|
| `record` | `ReinvestRecordStore.java:115` `public record OrderUsage(...)` |
| `var` | `SuixintuiUniOrderByAwemeService.java:409` `for (var o : list)` |
| switch expression | `ApiErrors.java:53-57` |
| stream API | 到处都是（`.stream().filter(...).toList()`） |
| 下划线数字 | `600_000L` / `5 * 60_000L` |
| 函数式接口 | `SuixintuiUniOrderByAwemeService.java:367` `@FunctionalInterface ThrowingSupplier<T>` |

### 1.9 代码组织细节

- 用 `// ==================== 分组名 ====================` 分隔区块
- 方法顺序：**查询 → 写入 → 内部工具**
- 内嵌 `public static class XxxResult` 做返回对象，字段 `public final`，带便捷方法（`total()`）
- 构造器注入 + `final` 字段；**重载构造器给默认值**
  （`SuixintuiUniOrderByAwemeService.java:71-85`）

---

## 2. ⚠️ 需要您拍板的 6 个点

| # | 问题 | 我的建议 |
|---|---|---|
| **①** | **日志**：OceanEngine 全用 `System.out.println`/`printf`。改用 `slf4j` + logback？ | ✅ **改**（架构文档已定）。⚠️ 但会改变排查习惯：以后看 `application.log` 而不是 `.out.log` |
| **②** | **Javadoc 详略**：照搬这种「中文超详尽」风格吗？ | ✅ **照搬**。这是这个项目最值钱的资产（坑都写在注释里了）。**但我会精简重复描述**，不为了长而长 |
| **③** | **`var` 用不用**？ | 建议**限定局部变量**用，字段/参数/返回值一律显式类型（可读性） |
| **④** | **`record` vs 显式 getter/setter**：分界在哪？ | 建议：**跨层传值 / 不可变值对象用 `record`**；**Spring 配置类必须显式 getter/setter**（Spring 需要 setter 绑定） |
| **⑤** | **`// ===== 分组 =====` 区块注释**保留吗？ | ✅ 保留，长类里确实好用 |
| **⑥** | **异常体系**：`RateLimitException extends ApiException`（继承 SDK 的），还是自己建一套？ | ✅ **照搬继承 SDK** —— 这样现有 `withRetry` 的 `catch` 逻辑不用改 |

---

## 3. 可复用代码迁移清单

> 路径相对 `D:\program\aolandai\OceanEngine\src\main\java\com\aolandai\oceanengine\`

| # | 源文件 | 目标包 | 处理方式 | 价值 |
|---|---|---|---|---|
| 1 | `client/ApiErrors.java` | `client/` | **原样照搬**，只改包名 | ★★★ 三层频控集中判定，**这是最不能少的一件** |
| 2 | `client/RateLimitException.java` | `client/` | **原样照搬** | ★★★ 频控语义 + `isAccountScoped()` |
| 3 | `client/QianchuanTokenClient.java` | `client/` | 照搬，环境变量名核对 | ★★★ token 换取 |
| 4 | `client/SuixintuiTokenClient.java` | `client/` | 照搬 | ★★★ token 换取 |
| 5 | `service/suixintui/SuixintuiUniOrderByAwemeService.java` | `qianchuan/` | 照搬 + **去掉 `System.out`** | ★★★ **最大复用** —— 已解决「按抖音号查订单」的账户发现 + 客户端过滤链路 |
| 6 | `service/suixintui/SuixintuiOrderService.java` | `qianchuan/` | 照搬（`discoverBusinessAdvertisers()` 被 5 依赖） | ★★★ 账户自动发现 |
| 7 | `storage/ReinvestRecordStore.java` | `store/` | **抽成通用 `JsonlStore<T>`** | ★★ 原子写 + 内存索引范式 |
| 8 | `webhook/WebhookNotificationStore.java` | `store/` | 抽通用 | ★★ 幂等落盘范式 |
| 9 | `scheduler/SuixintuiReinvestProperties.java` | `config/` | **拆分**成 `DingtouProperties` / `QianchuanProperties` / `SourceProperties` / `TaskProperties` | ★★ `@AssertTrue` 自检 + `describeXxx()` 范式 |
| 10 | `scheduler/SuixintuiReinvestScheduler.java` | `scheduler/` | **只抽 `FileChannel.tryLock()` 单实例锁**（调度本身不要了） | ★★ 防并发 |
| 11 | `report/DailyReinvestReportService.java` | `source/` | **只抄 POI 用法**（⚠️ 要换成 `poi-ooxml`） | ★★ Excel 读写 |
| 12 | `webhook/HttpWebhookSender.java` | `client/` | 抄 HTTP 封装范式（超时/重试/自定义头） | ★ HTTP 调用规范 |
| 13 | `pom.xml` | 根 | 抄依赖结构，**加 `poi-ooxml`**，**不加 lombok** | ★★ 构建基线 |

**明确不迁**：
- `domain/ReinvestSafetyGuard` / `SuixintuiReinvestCandidateSelector`（老业务的 ROI 追投规则，与本需求无关）
- `scheduler/SuixintuiReinvestScheduler` 的 `@Scheduled` 轮询（形态已变）
- 同事工程的 `plant_search/`（已确认整体弃用）

---

## 4. 新建【历史随心推全量订单总表】的表头方案

### 参考表实测（`D:\program\aolandai\date\历史随心推全量订单总表.xlsx`）

| 项 | 值 |
|---|---|
| sheet 1 | `抖音` —— **43,878 行订单 × 11 列** |
| sheet 2 | `查询不到投放商品` —— **空表**（预留的异常区） |
| 表头 | `订单ID / 商品标题 / 抖音号 / 抖音名 / 投放商品 / 投放账户 / 订单创建时间 / 投放状态 / 消耗 / 净成交ROI / 净成交金额` |
| ⚠️ 已知问题 | `抖音号` 列**数值+文本混存**（数值 28,828 / 文本 15,050）；`订单创建时间` 是**文本**不是日期 |
| `投放状态` 取值 | 投放结束 27,371 / 投放完成 10,230 / 投放中 3,821 / 投放终止 2,275 / 计划创建失败 141 / 审核不通过 39 / 未支付超15分钟关闭 1 |

### 我的建议表头（在参考表基础上**加 1 列**）

| 列 | 列名 | 说明 |
|---|---|---|
| A | 订单ID | 照搬 |
| B | 商品标题 | 照搬 |
| C | 抖音号 | 照搬，⚠️ **统一按文本存**（避免再次混型） |
| D | 抖音名 | 照搬 |
| **E** | **商品ID** | 🆕 **建议新增** —— 接口返回里本来就有 `productInfo.productId`（数值主键）；<br>`投放商品` 只有 75 个名称值，靠名称聚合有同名风险 |
| F | 投放商品 | 照搬（名称） |
| G | 投放账户 | 照搬 |
| H | 订单创建时间 | 照搬，⚠️ 建议**存真正的日期时间类型**（参考表存成了文本） |
| I | 投放状态 | 照搬 |
| J | 消耗 | 照搬 |
| K | 净成交ROI | 照搬 |
| L | 净成交金额 | 照搬 |

sheet 2 建议保留：`查询不到投放商品`（放商品 ID 匹配不到名称的订单，留痕）。

### ⚠️ 两个需要您定的

1. **加不加「商品ID」列？**（我建议加，理由如上）
2. **新表放哪、叫什么？** 候选：
   - `D:\program\aolandai\date\历史随心推全量订单总表-v2.xlsx`
   - `D:\rpa_workspace\shuixitui1\历史随心推全量订单总表.xlsx`（跟素材表放一起）
   - 工程内 `data/` 下

---

## 5. 请确认

1. **§2 的 6 个风格点** —— 逐条给个「行 / 不行」。
2. **§3 迁移清单** —— 有没有漏的、或不该迁的？
3. **§4 表头方案** —— 加不加「商品ID」列？新表放哪？

确认完我就开始迁移。
