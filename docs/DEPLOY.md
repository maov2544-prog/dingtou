# 部署手册（dingtou）

> 面向**运维/实施**：上线前要配什么、怎么起、怎么验证。
> 接口怎么调见 [`API-CONTRACT.md`](API-CONTRACT.md)；
> **影刀侧的接入流程见 [`RPA-INTEGRATION.md`](RPA-INTEGRATION.md)**；
> 设计细节见 [`NEXT-PROJECT-ARCHITECTURE.md`](NEXT-PROJECT-ARCHITECTURE.md)。

---

## 0. 这个服务是什么（30 秒版）

**影刀（RPA）驱动的 HTTP 任务执行器。**

```
影刀 ──POST /api/v1/task/{taskCode}──► 本服务 ──► 读 Excel 表 / 调千川 API ──► 返回 JSON
```

- 影刀只做 **RPA 动作与流程编排**（点页面、发企微消息）
- **全部业务逻辑与千川 API 调用都在本服务**
- 服务**没有界面**，一切靠 HTTP 接口 + 日志

**唯一入口**：`POST /api/v1/task/{taskCode}`（一个 URL，多个 taskCode）——
所以**加新能力不需要影刀改 URL**，只多一个 taskCode。

---

## 1. 前置条件

| 项 | 要求 | 说明 |
|---|---|---|
| **JDK** | **17**（必须） | `pom.xml` 里 `java.version=17` |
| **网络** | 能访问 `api.oceanengine.com`（巨量引擎） | 千川接口 |
| **网络** | 能访问 **`\\192.168.10.253\文件共享rpa\...`** | 《达人巡查配置表》在共享盘上，**不是本机文件** |
| **Windows 服务账号** | 需能读共享盘、能读 `D:/rpa_workspace/...` | 服务以 LocalSystem 跑时**读不到用户级环境变量**（见 §3） |
| **磁盘** | `data/` 与 `logs/` 可写 | 幂等流水、台账、日志 |
| **工作目录** | 必须是项目根目录 | ⚠️ `logs/dingtou.log` 与 `data/` 是**相对路径**，工作目录不对日志/流水会跑到别处 |

---

## 2. 必须配置的项（`application.yml`）

配置前缀 `dingtou.*`。**改完必须重启**（`@ConfigurationProperties` 不热加载）。

### 2.1 必填（不改就起不来 / 跑不通）

| 配置项 | 现默认 | 上线怎么填 |
|---|---|---|
| `dingtou.auth-token` | `${DINGTOU_AUTH_TOKEN:}` | **必填**，且不能是占位值（`change-me`/`test`/`123456` 会被拒），长度 ≥ 8。**从环境变量注入**，别写进文件 |
| `dingtou.source.delivery-sheet` | `D:/rpa_workspace/shuixitui1/26年视频交付表.xlsx` | 定投素材表路径。⚠️ **表按月改名**（9月→10月），改名后要同步改这里 |
| `dingtou.source.order-sheet` | `.../历史随心推全量订单总表.xlsx` | 订单快照表 |
| `dingtou.source.account-dict` | `.../投放账户信息表.xlsx` | 8 个投放账户字典（**账户 ID 以此表为准**） |
| `dingtou.source.product-dict` | `.../商品 ID 映射表(1).xlsx` | ⚠️ 文件名带空格与括号，yml 里要**加引号** |
| `dingtou.source.kol-patrol-config` | `\\192.168.10.253\文件共享rpa\...\达人巡查配置表.xlsx` | ⚠️ **UNC 共享路径**。yml 里双引号内 `\\` 才是 1 个反斜杠，所以写成 `"\\\\192.168..."` |
| `dingtou.store.data-dir` | `${DINGTOU_DATA_DIR:D:/program/aolandai/dingtou/data}` | ⚠️ **必须是绝对路径** —— 相对路径会随工作目录漂移，重启后读不到幂等流水 → **防重复形同虚设** |

### 2.2 上线前要确认的开关

| 配置项 | 现默认 | 说明 |
|---|---|---|
| `dingtou.dry-run` | **`true`** | **演练模式**：只判定、不真实投放。<br>⚠️ **确认要真实建计划时才改 `false`** —— 建千川计划是**花钱**的 |
| `server.address` | `127.0.0.1` | 与影刀**同机**部署保持默认；**跨机**要改 `0.0.0.0` 并务必设强 token |
| `server.port` | `8080` | 影刀侧要一致 |

### 2.3 一般不用动（已按实测调好）

| 配置项 | 默认 | 什么时候动 |
|---|---|---|
| `dingtou.qianchuan.request-interval-millis` | 500 | 通畅时的起始请求间隔 |
| `...max-request-interval-millis` | 8000 | 被频控后自动降速的上限 |
| `...rate-limit-retries` / `-budget-millis` | 5 / 300000 | 重试次数，以及**单次调用**的最大等待。⚠️ `budget` 是**按次**算的（2026-10-05 改），不是整批共享 —— 否则批量任务里一个组合被频控就能吃掉整批预算 |
| `...rate-limit-peak-avoidance` | true | 整点/半点前后自动避开 |
| `...max-pages` | 100 | ⚠️ **别调小**：实测某账户单场景 26 页，调小会**静默漏数据** |
| `...query-scenes` | `UNI_PROJECT,OVERALL_PROJECT` | ⚠️ **不能配空**（空 = 查不到任何计划，且不报错） |
| `...account-routing` | AUTO | 账户路由：`AUTO`/`TABLE_ONLY`/`SCAN_ALL` |
| `dingtou.kol.cost-window-months` | 1 | 消耗统计窗口 = 最近 N 个月 |
| `dingtou.suixintui.default-amount` | 200 | A 场景建随心推的**默认预算**，**单位：元**。⚠️ 官方约束 `[100, 5000000]` 且**必须是 10 的倍数**（配错**启动即失败**） |
| `dingtou.suixintui.default-delivery-time` | 24 | 默认投放时长，**单位：小时**。⚠️ **白名单**：`2/6/12/24/48/72/96/120/144/168`，**不是任意小时数**（配错启动即失败） |
| `dingtou.suixintui.default-bid-type` | `MANUAL_BID` | 出价方式：`AUTO_BID` / `MANUAL_BID` |
| `dingtou.suixintui.default-roi-goal` | 1.7 | 支付 ROI。⚠️ `[0.01, 10000]` 且**最多两位小数**；**`MANUAL_BID` 时必填** —— 与上一项**必须成对改**（配错启动即失败） |
| `dingtou.plan.default-budget` | 20000 | B/C1/C2 建乘方计划的**预算**，**单位：元**。⚠️ 官方必填，配 0 或负数启动即失败 |
| `dingtou.plan.default-roi2-goal` | 2 | 建计划的**支付 ROI 目标**。⚠️ **最多两位小数**（配错启动即失败） |
| `dingtou.plan.default-video-schedule-type` | `SCHEDULE_FROM_NOW` | ⚠️ 要用**接口值** `SCHEDULE_FROM_NOW` / `SCHEDULE_START_END`，**不是** SDK 常量名 `FROM_NOW` / `START_END` |
| `dingtou.plan.default-smart-bid-type` | `SMART_BID_CUSTOM` | 乘方目前**仅支持**这一种出价方式 |
| `dingtou.plan.enable-aigc-creative` | false | AIGC 动态创意。⚠️ 用基本类型 → **每次都会显式传出去**（行为确定，不依赖平台默认） |
| `dingtou.plan.star-task-material-switch` | false | 乘方成本项：**千川星选素材投放**。显式传（不依赖平台默认） |
| `dingtou.plan.alliance-commission-switch` | false | 乘方成本项：**达人带货佣金优化**（达人佣金出价）。官方要求「白名单 + `VIDEO_PROM_GOODS` + 有号」，我们条件满足，**是否开启取决于账号是否在白名单** |
| `dingtou.plan.material-pool-max-rounds` | 20 | 查素材池的游标翻页上限。⚠️ **别调小** —— 素材多的达人会被**静默截断** |
| `dingtou.aggregate.prescreen-scope` | ALL | ⚠️ **改这个会改变 N，进而改变 A/B/C 判定** |
| `dingtou.task.async-timeout-millis` | **0（不转异步）** | 超过它就返回 `code=4 + taskId` 转后台。⚠️ **改成正数前必须先确认影刀支持轮询**，否则影刀拿到 taskId 不知道怎么用。建议影刀就绪后设 `30000` |
| `dingtou.kol.delivery-status-sheet` | 共享盘《高消耗达人7天未更新素材总表》 | ⚠️ **必须带 IP**（机器名解析不了）。用于「交付状态」检查；写之前会自动备份 |
| `dingtou.kol.delivery-status-column` / `-value` | `交付状态` / `已交付` / `未交付` | 列名与写入值。⚠️ 列名必须与表头**精确一致** |
| `dingtou.anomaly.enabled` / `-sheet` | `true` / `异常表.xlsx` 路径 | 「异常表」：记录没办成且非「主动等待」的情况。**服务直接写、追加、带按天备份**；文件不存在会自动创建 |
| `dingtou.source.targeting-sheet` | 《定向投放登记表》路径 | 含义 = **已经在千川建计划的【达人 × 商品 × 账户】**。命中就用它给的账户查计划（范围收窄到 1 个账户） |
| `dingtou.aggregate.auto-refresh-orders` / `-throttle-minutes` | `true` / `10` | 投放前自动刷订单快照 + 节流窗口（`planBuild` 是逐条调的，不节流会重复拉） |
| ⚠️ **JVM 启动参数** | `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8` | **必须加！** JDK 17 在中文 Windows 上 `file.encoding=GBK`（JDK 18+ 才默认 UTF-8），不加则**中文日志全变 `�` 乱码**。已在 `pom.xml` 的 surefire `argLine` 里配好（测试）；**生产启动脚本也要加** |
| `DINGTOU_LOG_FILE`（环境变量） | `logs/dingtou.log` | 日志文件路径。⚠️ 默认是**相对路径**（跟随工作目录）→ WinSW 里务必让 `workingDirectory` 指向项目根目录，或用这个环境变量给**绝对路径** |
| `dingtou.source.sheet-cache-seconds` | `300` | 素材表/账户字典的**读取缓存**秒数（以**文件修改时间**为主判据，文件一改就失效）。⚠️ 轮询时 50 个组合会读表 50 次 → 缓存后只读 1 次（实测 `locate()` 从 566ms → 2ms） |
| `dingtou.source.direct-write` | `true` | ⚠️ **服务直接写素材表**（K 投放类型 / N 投放状态）。开了之后**影刀就不该再写素材表**，否则互相覆盖。写前自动备份 |
| `dingtou.scheduler.pending-enabled` | `false` | ⚠️ **默认关**！「轮询」总开关：服务自己扫素材表 → 跑投放 → 直写回。开了会**花钱**且和影刀抢写 |
| `dingtou.scheduler.pending-cron` | `0 */10 * * * *` | 定时投放周期（Spring 6 段式）。⚠️ 别太密，容易撞 40100 |
| `dingtou.task.worker-threads` | 4 | 后台线程数。⚠️ **别调大** —— 每个后台任务都会真打千川接口，并发上去更容易撞 40100 |

---

## 3. 环境变量（⚠️ 必须是 **Windows 系统级**）

服务以 **LocalSystem** 运行时**读不到用户级环境变量** —— 这是最容易踩的坑。

### ⚠️ 先分清两个 token（方向相反，别搞混）

```
影刀 ──X-Auth-Token: DINGTOU_AUTH_TOKEN──► 本服务 ──Bearer: QIANCHUAN_SERVICE_BEARER_TOKEN──► 千川
              ↑ 你要自己定（本服务认它）                    ↑ 平台给的（千川认它）
```

| 变量名 | 方向 | 值从哪来 | 现状 |
|---|---|---|---|
| **`DINGTOU_AUTH_TOKEN`** | **影刀 → 本服务** | **自己定**（强随机串即可，无外部来源） | ⚠️ **通常还没设**，部署时要新建 |
| `QIANCHUAN_SERVICE_BEARER_TOKEN` | 本服务 → 千川 | 平台/服务端给的**换取凭据** | 一般已设；⚠️ **只有 8 字符是正常的**（它是换取凭据，不是残缺 token） |
| `DINGTOU_DATA_DIR` | — | 选填，默认 `D:/program/aolandai/dingtou/data` | 一般不设也行 |

**`DINGTOU_AUTH_TOKEN` 怎么定**：随便生成一个强随机串就行，只要满足
**非空、长度 ≥ 8、不是 `change-me`/`test`/`123456`**（这几条是启动自检的硬规则）。
生成一个（PowerShell）：

```powershell
-join ((48..57) + (65..90) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
```

> ⚠️ **同一个值要配到两处**：服务端环境变量 + 影刀每个请求的 `X-Auth-Token` 头。
> 两边不一致 → 服务返回 **`code=3 未授权`**。

### 设置方式（管理员 PowerShell / 系统属性 → 环境变量 → 系统变量）

```powershell
[Environment]::SetEnvironmentVariable("DINGTOU_AUTH_TOKEN", "<你生成的串>", "Machine")
[Environment]::SetEnvironmentVariable("QIANCHUAN_SERVICE_BEARER_TOKEN", "<8位凭据>", "Machine")
```

设完**必须重启服务**（服务进程不会自动重读环境变量）。

**确认设上了**（三级都要看，`Process` 空是正常的，重启服务后才有值）：

```powershell
foreach ($s in "Machine","User") {
  "$s : " + [Environment]::GetEnvironmentVariable("DINGTOU_AUTH_TOKEN", $s)
}
```

> 💡 **没设 token 会怎样**：服务**启动直接失败**（`@NotBlank` + 占位值校验）——
> 这是刻意设计的：这个服务能建千川计划（花钱），宁可启动就炸，也不要裸奔上线。

---

## 4. 打包与启动

```bash
# 打包（产出可执行 jar）
mvn -B clean package -DskipTests
# → target/dingtou-1.0-SNAPSHOT.jar

# 直接跑（排障用）
java -jar target/dingtou-1.0-SNAPSHOT.jar
```

**生产**建议用 WinSW 注册成 Windows 服务（以 LocalSystem 运行），
`workingDirectory` 指向项目根目录，日志看 `logs/dingtou.log`。

> ⚠️ 本仓库**不含 WinSW 配置**（在部署环境里维护）。如果服务起不来但命令行能跑，
> 优先检查 WinSW 的 `workingDirectory` 与「系统级环境变量」。

### ⚠️⚠️ 踩坑：命令行参数会**压过**环境变量

Spring 的配置优先级（高 → 低）：

```
命令行参数  >  系统属性(-D)  >  环境变量  >  application.yml
```

**所以：如果启动命令里带了 `--dingtou.auth-token=xxx`，那么你设的环境变量 `DINGTOU_AUTH_TOKEN` 完全不生效** ——
服务认的是命令行那个值。这正是「明明设了环境变量，影刀还是 401」的典型原因。

**上线前务必确认服务实际用的 token 是哪个**：

```powershell
# 看服务进程的启动参数（把 <PID> 换成实际进程号）
(Get-CimInstance Win32_Process -Filter "ProcessId=<PID>").CommandLine
```

- 看到 `--dingtou.auth-token=...` → 用的是**命令行**那个值（环境变量被压掉了）
- 没看到 → 用的是**环境变量** `DINGTOU_AUTH_TOKEN`

**本地调试用的 `local-dev-only-not-a-real-token` 绝不能带到生产**（见 §7）。

---

## 5. 上线自检清单

```bash
# ① 探活（免鉴权，最先打这个）
curl --noproxy '*' http://127.0.0.1:8080/actuator/health

# ② 业务探活（带 token）—— 返回 5 张表的可读性
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/task/ping \
  -H "X-Auth-Token: <token>" -H "Content-Type: application/json" -d '{}'
```

- [ ] 启动横幅打出**全部 6 项配置**（`配置/存储/聚合/数据源/千川`）+ **5 个任务**
- [ ] `数据源:` 一行里 5 张表**全是「可读」**（出现「缺失」或「不可读」要立刻处理）
- [ ] `千川:` 一行确认 **请求间隔 / 查询场景 / 翻页上限** 符合预期
- [ ] `ping` 返回 `code=0`，且 `tables` 里没有缺失项
- [ ] 影刀侧能连通（注意 **`--noproxy '*'`**：Windows 上配了系统代理时，
      curl/影刀 访问 `127.0.0.1` 会被代理拦掉，报 `upstream connect failed`）
- [ ] `dry-run` 状态符合预期（真实投放前必须确认）

---

## 6. 上线后怎么验证（两个只读任务，不花钱）

```bash
# 查「消耗达标的千川计划」+ 顺带取抖音号/抖音名
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/task/kolPlanCostQuery \
  -H "X-Auth-Token: <token>" -H "Content-Type: application/json" \
  -d '{"requestId":"deploy-check-1","params":{"startDate":"2026-09-04","endDate":"2026-10-03","withAweme":true}}'
```

预期：`code=0`，`data.rows` 是达标计划列表（按消耗降序），
每行带 `advertiserId / advertiserName / adId / adName / statCost / awemeShowId / awemeName`。

⚠️ **`data.warnings` 必须看**：非空说明**有账户没查成功，结果不完整**，
不能当成「这就是全部达标计划」。

---

## 7. 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| **`code=3 未授权: 缺少或错误的 X-Auth-Token 请求头`** | **两边 token 不一致**。① 确认影刀发的值；② 确认服务**实际**认的值（看启动参数，见 §4 —— 命令行参数会压过环境变量）；③ 两边改成同一个 |
| **日志里中文变成 `�`（乱码）** | ⚠️ **JVM 没加 `-Dfile.encoding=UTF-8`**。JDK 17 在中文 Windows 上默认 `file.encoding=GBK`，而日志 charset 跟随它 → 中文变替换字符。**修**：启动加 `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8`（`application.yml` 已显式设 `logging.charset.file: UTF-8`，但 JVM 参数仍要加） |
| 日志里 `HttpMediaTypeNotSupportedException: Content-Type 'text/plain...' is not supported` | 影刀默认发 `text/plain`。**已兼容**（`WebConfig` 让 Jackson 也认 `text/plain`），重新部署即可。若仍报，说明 body 不是合法 JSON → 看是否返回 `code=2` |
| 收到 `code=5 内部错误` 但重试无用 | 检查是不是**客户端错误被误报**。`code=5` 才是可重试的；`code=2/3` 重试无意义。若确认是传参问题却收到 `code=5`，把日志里的异常类名反馈给开发 |
| 启动直接失败，报 `auth-token` 校验 | 环境变量没设或设成了用户级 → 改成**系统级**并重启服务 |
| 表全部「缺失」 | 路径不对 / 共享盘不通 / 服务账号无权限。⚠️ 共享盘要**用 IP**（`\\192.168.10.253\...`），机器名可能解析不了 |
| 表「不可读(被占用?)」 | 有人正用 Excel 打开着该文件 |
| `40100 系统请求频率超限` | 全局频控（所有开发者共享、平台动态调整）。服务会**自动降速+重试**；若日志里频繁出现，调大 `max-request-interval-millis` 或**错峰**（避开整点/半点） |
| `40002 ... doesn't exist or the role is wrong` | **账户 ID 错了**。以《投放账户信息表》为准，别用其它表里的 ID |
| `40000 仅支持查询近180天以内的数据` | 查询窗口超了 API 硬上限 180 天 |
| 任务跑很久（几分钟） | 正常：千川接口 + 翻页本身慢。`kolPlanCostQuery` 全量约 4 分钟。**影刀侧超时要设够**（>10 分钟） |

---

## 8. 文档索引

| 文档 | 内容 |
|---|---|
| **`docs/DEPLOY.md`** | **本文** —— 部署、配置、自检 |
| `docs/API-CONTRACT.md` | 影刀对接契约：URL、POST 格式、请求/应答字段、错误码 |
| `docs/NEXT-PROJECT-ARCHITECTURE.md` | 架构设计与分层 |
| `docs/NEXT-PROJECT-STRUCTURE.md` | 目录结构与包职责 |
| `docs/CODE-STYLE.md` | 代码规范 |
| `docs/NEXT-REQUIREMENT-*.md` | 需求确认 / 计划 / 复用说明 |
