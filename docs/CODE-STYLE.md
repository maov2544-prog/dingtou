# 代码风格规范

> ⚠️ 本文原为「待老板拍板稿」（2026-09-30），现已**落地到代码**。
> 已删掉当时的过程性内容（待拍板项、迁移清单、**已废弃的订单总表方案**）。
> 剩下的 §1 是**实际在用的风格**，改代码前扫一眼。

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

## 2. 本项目额外必须守的几条（踩坑得来）

1. ⚠️ **不用 lombok**；**日志用 slf4j + logback**（老项目用 `System.out.println` → 业务日志跑到 WinSW `.out.log`）。
2. ⚠️ **SLF4J 占位符只认 `{}`**，不认 `{:<20}`（会被当文本且**顶掉第一个 `{}`**）→ 用 `String.format`。
   ⚠️ **占位符数量必须与参数数量一致**：多了会把 `{}` 原样打印进日志；少了会顶掉后面的参数。
   合法例外：`Throwable` 放最后一个参数不算「多余」。
3. ⚠️ **持久化格式里的派生 getter 必须 `@JsonIgnore`**（`isDone()`/`isOk()` 会被写成属性 →
   回读 `FAIL_ON_UNKNOWN_PROPERTIES` → **服务起不来**）。
4. ⚠️ **不要在 `JsonlStore` 基类构造器里调用可覆写方法**（子类字段还没初始化 → NPE）。
5. ⚠️ **Jackson**：`non_null` 管不住 `Optional.empty()` → 用 **`non_absent`**；
   `TextNode.canConvertToLong()` **恒 false** → 判断数字字符串用 `isNumber()` + `Long.parseLong(asText())`。
6. ⚠️ **禁止硬编码**：账户 ID / 抖音号 / 分享链接一律进配置。
7. ⚠️ **共享盘路径必须带 IP**（机器名解析不了）。
8. ⚠️ **每包补中文 `package-info.java`**；**拆包必须补 import**（否则编译 100+ 错误）。
9. ⚠️ **验证编译必须看退出码**，不能只看 `BUILD SUCCESS` ——
   `mvn compile`/`test-compile`/`test` 都会**假绿**（增量编译跳过了你刚改的东西）。
