# 影刀对接接口契约（v1 · 更新于 2026-10-04）

> 本服务是**影刀驱动的 HTTP 任务执行器**。影刀只做 RPA 动作与流程编排；
>   
> **全部业务逻辑与千川 API 调用都在本服务**。
>   
> 本文是影刀侧写流程时的对接依据。**代码即契约**，实现见 `controller/TaskController`。

---

> 📘 **写影刀流程的人请看 [`RPA-INTEGRATION.md`](./RPA-INTEGRATION.md)** —— 那里有完整的
>   
> 「扫表 → 提交 → 轮询 → 写回」伪代码、curl 自测用例、上线切换步骤与常见问题对照表。

## 1. 基本约定

| 项    | 值                                                                                                 |
| ---- | ------------------------------------------------------------------------------------------------- |
| 基地址  | `http://127.0.0.1:8080`（与影刀同机；跨机部署改 `server.address`）                                             |
| 鉴权   | 请求头 **`X-Auth-Token: <token>`**，值由服务端 `dingtou.auth-token` 配置                                     |
| 内容类型 | **推荐** `application/json; charset=UTF-8`<br />⚠️ 也**兼容 `text/plain`**（影刀 HTTP 组件默认发的就是它 —— 见下方说明） |
| 探活   | `GET /actuator/health` —— **免鉴权**，正式调用前先打一次                                                       |
| 日志   | `logs/dingtou.log`（slf4j + logback，不是 `.out.log`）                                                 |

---

## 2. 提交任务

```
POST /api/v1/task/{taskCode}
Header: X-Auth-Token: <token>
Body:   { "requestId": "<影刀生成的唯一号>", "params": { ... } }
```

> ⚠️ **`requestId` 必须带，且同一次业务动作重试时必须用同一个值。**
>   
> 幂等落盘靠它 —— 影刀的重试策略不可控，而「重复建千川计划」是要花钱的。

> 💡 **关于 `Content-Type`**：影刀的 HTTP 组件**默认发 `text/plain;charset=utf-8`**，
>   
> 而服务端原先只认 `application/json` → 对接第一步就报
>   
> `HttpMediaTypeNotSupportedException`。
>   
> **现已主动兼容 `text/plain`**（体内容照样按 JSON 解析），所以**影刀侧不用改任何配置**。
>   
> 传 `application/json` 当然也可以。
>   
> 但 body **必须是合法 JSON**，否则仍返回 `code=2`。

### 统一应答

```json
{
  "code": 0,
  "message": "ok",
  "requestId": "r-20260930-001",
  "costMs": 3,
  "data": { ... }
}
```

| `code` | 含义       | 影刀该怎么做                                             |
| ------ | -------- | -------------------------------------------------- |
| `0`    | 成功       | 读 `data` 继续                                        |
| `1`    | **业务失败** | **上报人工，不要重试**（message 可直接贴企微群）                     |
| `2`    | **参数错误** | **改参数再调，不要重试**                                     |
| `3`    | **未授权**  | 检查 token 配置，**不要重试**                               |
| `4`    | **执行超时** | 拿 `taskId` 轮询 `GET /api/v1/task/{taskId}`，**不要重发** |
| `5`    | 内部错误     | 可重试，但**必须带同一个 `requestId`**                        |

---

## 3. 已实现的任务

> 当前共 **8 个 taskCode**：`ping` / `kolPlanCostQuery` / `planAwemeLookup` /
> `suixintuiCreate` / `planBuild` / `processPending` / `refreshOrders` /
> **`resolveProductByVideo`**。启动横幅会打印实际注册清单，**以那个为准**。
>
> ⚠️ **2026-10-05 起 `decide` 与 `planQuery` 已废弃**（handler 已删除）：
>
> - `planQuery`：账户来源改由【素材表 `E 全域账号`】唯一确定，不再自己遍历账户；
> - `decide`：它的 `hasPlan` 是影刀通过 `planQuery` 拿的，而**判定逻辑已整体并入 `planBuild`**
>     
>   （`planBuild` 内部会算 N + 判场景 + 执行），影刀**只需调 `planBuild` 一个接口**。

### ⚠️ 最易混的两个：`kolPlanCostQuery` vs `planAwemeLookup`

它们常一起用（先查列表 → 再查达人），但**参数完全不同，别把参数发错接口**：

|           | `kolPlanCostQuery`                    | `planAwemeLookup`                        |
| --------- | ------------------------------------- | ---------------------------------------- |
| **URL**   | `/api/v1/task/kolPlanCostQuery`       | `/api/v1/task/planAwemeLookup`           |
| **参数**    | `startDate` / `endDate` / `withAweme` | **`items[]`**（`{advertiserId, adId}` 数组） |
| **输入是什么** | 时间窗（都可选）                              | 计划坐标列表（**必填**）                           |
| **输出**    | 达标计划列表（`withAweme=true` 时带抖音号/名）      | 每个计划的抖音号/抖音名                             |
| **典型耗时**  | 全量约 **4 分钟**                          | 每计划约 1 次调用                               |

> 把 `startDate`/`endDate` 发到 `planAwemeLookup` 会得到：
>   
> `code=2 参数错误: items 必须是非空数组，形如 [{"advertiserId":123,"adId":456}]`
>
> ⚠️ **JSON 必须严格合法**（少一个引号就是 `code=2 JSON parse error`）。
>   
> 建议用 JSON 校验工具先过一遍再贴进影刀。

### 异常表（不是接口，是落盘的 Excel）

> 📌 **2026-10-08 新增**（老板：「新建一张 Excel」）。记录流程里**没办成、且不是「主动等待」**&#x7684;情况。
>
> **列**：`时间 / 抖音号 / 商品名 / 账户 / 阶段 / 异常类型 / 详情`
>
> **写入方式**：服务**直接写**、**追加**（不是覆盖）、**按天备份**（`xxx.backup-20261008.xlsx`）。
>   
> ⚠️ 文件**不存在会自动创建**（带表头）；⚠️ 写失败**不拖垮主流程**，但会记 ERROR。
>
> **已接入**：① 计划存在但**不是投放中**；② `planBuild` **全方法兜底**（建随心推 / 建计划 / 加素材失败）。
>   
> ⚠️ **参数错（`BAD_REQUEST`）不记** —— 那是影刀传参/配置问题，记进去会刷满。

### 投放决策口径（2026-10-08 老板定，与旧版不同）

| 动作           | 受哪些状态限制                                                               |
| ------------ | --------------------------------------------------------------------- |
| **创建随心推订单**  | **5 个互斥状态**：`DELIVERY_OK` / `AUDIT` / `CREATING` / `BOOK` / `FROZEN`  |
| **创建千川乘方计划** | **只判 `DELIVERY_OK`**（只为「等它跑完再建」）—— 因为同一抖音号 × 同一商品维度下，无论随心推什么状态基本都能建成功 |

**N（累计笔数）计数白名单**：

- ✅ 算：`DELIVERY_OK` / `FROZEN` / `FINISHED` / `OVER` / `AUDIT` / `BOOK` / `CREATING`
- ❌ 不算：`OFFLINE_AUDIT` / `FAILED` / `TIMEOUT` / `UNPAID` / `UNPAIDCANCEL`

**查计划时排除**：`DELETE`（已删除，⚠️ 不是 `DELETED`）/ `OFFLINE_AUDIT`（审核不通过）—— 剔除后当「没有计划」。

### `refreshOrders` —— 刷新随心推订单快照（★ N 的数据源）

> 📌 **2026-10-07 新增。** 老板定调：**N 不预筛，只认随心推订单数**。
>
> ```
> // 增量（默认）：拉最近 7 天，按 order_id 去重合并。实测 8 账户约 44 秒，同步返回
> {"requestId":"r1","params":{}}
>
> // 指定回溯天数
> {"requestId":"r2","params":{"lookbackDays":14}}
>
> // 首次全量：8 个账户各拉全部历史（实测 ≈90 分钟）→【异步】，立即返回
> {"requestId":"r3","params":{"full":true}}
> ```
>
> ⚠️ **为什么必须这样**：订单接口 `/qianchuan/aweme/uni_promotion/order/get/`
>   
> **没有达人/商品过滤**（只有时间/状态/账户）→ 只能拉回来本地按
>   
> `awemeInfo.awemeShowId + productInfo.productId` 分组计数。
>
> ⚠️ **实测数字**（最大账户官方【母婴商品】全量）：
>   
> **689 页 / 34,386 条 / 11 分 38 秒**；最早订单到 **2025-08-29**（跨 404 天）。
>   
> 8 个账户 ≈90 分钟 → 所以「**首次全量 + 之后每次投放前增量**」。
>
> ⚠️ **增量是幂等的**：快照存了已计入的 `orderId`，合并时做集合并集
>   
> → **重复拉同一天不会多算**，可以放心地每次投放前都跑。
>
> ⚠️ **回溯天数必须 ≥ 7 天**（随心推投放时长上限 168 小时）——
>   
> 否则「7 天前创建、现在还在投」的订单看不到，「投放中」标记会漏。
>
> ⚠️ **N 的口径**：**不看订单状态**，有一个算一笔（含 `FAILED` / `TIMEOUT`）。

### `resolveProductByVideo` —— 抖音分享链接 → 商品 ID（★ 新增）

> 📌 **2026-10-08 新增。** 从分享口令解析出【视频ID → 商品ID】，顺带回传抖音名/抖音号/数值UID。
>
> ```
> {"requestId":"r1","params":{
>   "link": "0.56 复制打开抖音，看看【尚尚孕期（努力版）的作品】… https://v.douyin.com/0q-qmQYi6hQ/ …",
>   "advertiserId": 1823379540880396,
>   "awemeShowId": "173701235",     // 选填：素材表 D 列，给了提高命中率
>   "awemeUid": 65628672972         // 选填：已知数值 uid 直接给，跳过解析
> }}
> ```
>
> **链路**：
>
> ```
> 分享口令 → v.douyin.com 短链（多跳跟随，≤10 跳）
>          → 每跳同时扫 URL 与正文，拿两样：
>             · /video/(\d{10,25})  → 视频ID
>             · social_author_id   → 作者数值 uid（分享参数自带，不走风控）
>          → 视频列表（账户 + uid，⚠️ count 最多 20）→ 匹配视频ID → product_info.id
>          → 兜底：品名反查（返回 candidates 供人工确认）
> ```
>
> **返回**：`videoId` / `awemeUid` / `awemeName` / `awemeShowId` / `productId` /
>   
> `productName` / `title` / `img` / `candidates` / `stage` / `error`
>
> ⚠️ **失败不抛异常** —— 结构化返回（`error` 非空即失败），让影刀自己决定怎么处理。
>   
> ⚠️ **只读、不花钱**。
>
> 📌 算法移植自老板给的 `ProductIdFromVideo.java`，但**重写成 Spring Bean** ——
>   
> 原版全是 `static` 可变状态，作者自己注明「HTTP 服务下必须串行」→ **并发不安全**。

### `ping` —— 探活

> 📌 **2026-10-07**：`processPending` / `planBuild` **开头会自动跑一次订单增量**（老板：「refreshOrders 可以自动跑」）。
>   
> 带 **10 分钟节流**（`dingtou.aggregate.auto-refresh-orders-throttle-minutes`）——
>   
> 因为 `planBuild` 是逐条调的，不节流会「每条组合都拉一次全账户订单」。
>   
> 关掉用 `dingtou.aggregate.auto-refresh-orders: false`（关了就必须让影刀显式调 `refreshOrders`）。

```
POST /api/v1/task/ping        （可无 body）
```

```json
{"code":0,"message":"ok","costMs":0,"data":{"status":"up","time":"2026-09-30T10:43:28+08:00"}}
```

> 同时返回 6 张业务表的可读性 —— 上线自检先打它。

### `kolPlanCostQuery` —— 查消耗达标的千川计划（达人巡查/催更）

> 📌 **2026-10-06 新增选填逻辑**：传 `"checkDelivery": true` 时，会**顺带做「交付状态」检查**
>   
> 并**直接写回共享盘的《高消耗达人 7 天未更新素材总表》**。
>
> ```
> {"requestId":"r9","params":{
>   "withAweme": true,
>   "checkDelivery": true,          // 选填，默认 false —— 不传时行为与以前完全一致
>   "referenceDate": "2026-10-11"   // 选填，用于定位「上一个周期」；默认今天
> }}
> ```
>
> **它做什么**：
>
> 1. 找【上一个周期】—— **准绳是「上一个周日」**：
>      
>    参考日往前找最近的周日（参考日本身是周日则再往前 7 天）→ 上一个周日；
>      
>    再取**结束日 == 那个周日**的 sheet。
>      
>    例：参考日 `2026-10-11`（周日）→ 上一个周日 `2026-10-04` → sheet `2026-09-13到2026-10-04`；
>      
>    ⚠️ **不是整个 sheet，只处理 `J 列「周期时间」== 上一个周日` 的行** ——
>      
>    一个 sheet 是好几周合并的（实测 `2026-09-13到2026-10-04` 里 J 列有
>      
>    `09-13`(50 行) / `09-20`(56 行) / **`10-04`(57 行)** 三个值）；
> 2. 判定窗口 = **[上一周期结束日, 本周期开始日]**（闭区间，如 `2026-10-04 ~ 2026-10-11`）；
> 3. 逐行拿 C 列「计划 ID」→ 调【乘方计划详情】拿计划名（如 `朗朗呀-儿童彩妆套装-10.4更新`）；
> 4. 从计划名里抠出 `M.D` 日期 → 落在窗口内写「**已交付**」，否则写「**未交付**」；
>      
>    ⚠️ **解析不出日期的行【留空】**（不写「未交付」—— 免得污染人工判断，
>      
>    也便于看出「这行没检查成功」）。
>
> ⚠️ **它直接改 Excel**（本工程唯一一处）—— 写之前会**自动备份**到同目录
>   
> `.backup-yyyyMMdd-HHmmss.xlsx`；表被 Excel 打开时**会如实报错**，不会静默。
>
> ⚠️ **成本**：实测「上一个周日」那个周期是 **57 行** → 调 **57 次**详情接口，**约 1~2 分钟**。
>   
> 建议**只在周末跑**（老板说明该流程本来只在周末运行）。
>
> ⚠️ 受 `dingtou.dry-run` 保护：dry-run 时只算不写，应答里的 `delivery.dryRun=true`。

```
POST /api/v1/task/kolPlanCostQuery
{"requestId":"r3","params":{
  "startDate": "2026-09-04",   // 选填，必须与 endDate 同时给；不给则用默认窗口（最近 N 个月）
  "endDate":   "2026-10-03",   // 选填
  "withAweme": true            // 选填，默认 false；true 会为每个达标计划多一次详情请求
}}
```

应答 `data`：

```json
{
  "windowStart": "2026-09-04 00:00:00",
  "windowEnd":   "2026-10-03 23:59:59",
  "accountsScanned": 7,
  "plansScanned": 759,
  "rows": [ { "advertiserId": 1823379540880396, "advertiserName": "官方【母婴商品】",
              "adId": 1853820668082395, "adName": "伊嘉好物-气垫-9.26更新",
              "statCost": 100070.13, "smartBidType": "SMART_BID_CUSTOM",
              "awemeShowId": "MOWEI6688", "awemeName": "伊嘉好物" } ],
  "warnings": []
}
```

> - `rows` 已按 **`statCost` 降序**，且 `statCost` 单位是**元**
> - ⚠️ **`smartBidType` 是【本项目自定义的业务字段】，不是接口原始语义**：
>   - **乘方**计划 → 固定输出 **`"乘方"`**
>   - **全域**计划 → 输出接口原值（`SMART_BID_CUSTOM` / `SMART_BID_CONSERVATIVE`）
>   - 拿不到时为 `null`，**字段会整个不出现**
>   ⚠️ **副作用（已知情接受）**：乘方计划的**真实出价方式在本接口看不到了**。
>     
>   注意接口的 `smart_bid_type` **本身区分不了乘方/全域**（实测全域计划也是
>     
>   `SMART_BID_CUSTOM`），所以这里的判断依据是**场景**，不是出价方式。
> - `withAweme=false` 时 `awemeShowId` / `awemeName` **字段不出现**（Jackson 省略 null）
> - ⚠️ **耗时较长**（全量约 4 分钟），影刀侧超时要设够
> - ⚠️ `warnings` 非空 = 有账户没查完，**不是「这些就是全部达标计划」**

### `planAwemeLookup` —— 按【投放账户 + 计划ID】批量查达人信息

```
POST /api/v1/task/planAwemeLookup
{"requestId":"r4","params":{
  "items": [
    { "advertiserId": 1823379540880396, "adId": 1877813517052328 },
    { "advertiserId": 1776098375039040, "adId": 1877000000000000 }
  ]
}}
```

应答 `data`：

```json
{
  "requested": 2,
  "resolved": 2,
  "items": [ { "adId": 1877813517052328, "awemeShowId": "610129yiyi", "awemeName": "伊伊麻麻" } ],
  "warnings": []
}
```

> - **必须带 `advertiserId`**（详情接口是账户维度鉴权；只给 adId 就得遍历 8 个账户，慢且易触发频控）
> - 单批上限默认 **200 条**（配置 `plan-aweme-max-items`），超了报 `code=2`
> - ⚠️ 一个计划若有**多个达人**，只取第一个并记 warning
> - ⚠️ 实测存在「**抖音名有值、抖音号为空**」的真实数据，此时 `awemeShowId` 为 `""` 且**会记 warning**

### `suixintuiCreate` —— A 场景：建随心推 + 返回「素材表要写哪些单元格」

```
POST /api/v1/task/suixintuiCreate
{"requestId":"r5","params":{
  "awemeShowId": "272886488",     // 必填，抖音号
  "productName": "益生元身体乳"     // 必填，商品名
}}
```

应答 `data`：

```json
{
  "advertiserId": 1778533513439244, "advertiserName": "婴童【儿童-商品】",
  "awemeId": 12040301875,               // 抖音号的【数值 UID】
  "productId": "3778413499521302561",
  "amount": 200, "deliveryTime": 24.0,  // 回显（元 / 小时），便于核对单位
  "bidType": "MANUAL_BID", "roiGoal": 1.7, // 回显出价方式与支付 ROI
  "dryRun": true,                       // ⚠️ 默认 true = 没真建单
  "orderId": null,                      // 真建成后才有
  "requestBody": "{...}",               // 实际（将要）发出的请求体
  "cells": [ { "row": 4992, "column": "K", "value": "全域随心推" } ],
  "warnings": []
}
```


**投放参数（服务端配置 `dingtou.suixintui.*`，影刀不用传）**

| 字段              | 单位     | 官方约束                                                                                                          | 当前默认             |
| --------------- | ------ | ------------------------------------------------------------------------------------------------------------- | ---------------- |
| `amount`        | **元**  | 必填；`[100, 5000000]` 且**必须是 10 的倍数**                                                                           | **200**          |
| `delivery_time` | **小时** | 必填；**白名单** `2/6/12/24/48/72/96/120/144/168`                                                                   | **24**（= 1 天）    |
| `bid_type`      | —      | 可选；`AUTO_BID` / `MANUAL_BID`                                                                                  | **`MANUAL_BID`** |
| `roi_goal`      | 支付 ROI | **条件必填**；**最多两位小数**，`[0.01, 10000]`；<br />**当 `marketing_goal=VIDEO_PROM_GOODS` 且 `bid_type=MANUAL_BID` 时必填** | **`1.7`**        |

> ⚠️ 时长是**白名单不是范围** —— 填 `36` 这类「看着合理」的值会被接口拒。
>   
> ⚠️ `bid_type` 与 `roi_goal` 是**联动**的：改成 `MANUAL_BID` 就必须给 `roi_goal`（本服务
>   
> `marketing_goal` 恒为 `VIDEO_PROM_GOODS`，条件必然成立）。两个配置必须**成对改**。
>   
> ⚠️ 这些约束在**启动时自检**（配错服务直接起不来），组装请求体前**再校验一次**；
>   
> **非法参数连 dry-run 也会返回 `code=2`**，不会放行成「看起来能建」。
>
> ⚠️ **这是花钱的操作**（建随心推），受 `dingtou.dry-run` 保护 —— **默认演练、不会真建**。
>   
> 虽然单位已由官方文档确认，第一次跑仍建议核对 `requestBody`，确认后再把 `dry-run` 改成 `false`。
>
> ⚠️ **写素材表有两种模式**（2026-10-08 起）：
> - `dingtou.source.direct-write=true`（默认）→ **服务自己写**（带备份；被 Excel 打开时如实报错）
> - 影刀仍可照 `cells` 写，但⚠️ **两边不能同时写**，会互相覆盖
>   `cells` 始终返回，便于影刀兜底或核对。
>
> ⚠️ **只处理「投放类型为空」的行** —— 已填过的历史数据一律不碰。
>
> ⚠️ **账户必须唯一**：待处理行跨多个账户时**直接报错要人工确认**（绝不瞎挑一个）。
>
> ⚠️ `warnings` 必须看：非空说明**没有全部办成**（没账户 / 未授权 / 商品映射不到），
>   
> 里面会带**行号与原值**，可拿去推人工补表。

**结果怎么读**：

| `dryRun` | `orderId` | `cells` | 含义                      |
| -------- | --------- | ------- | ----------------------- |
| true     | null      | 有       | **演练**：没真建单，核对请求体       |
| false    | 有         | 有       | **真建成**：按 `cells` 写回素材表 |
| —        | null      | 空       | **没建**：看 `warnings` 找原因 |

### `planBuild` —— A / B / C1 / C2 一站式投放（服务自己算 N + 判场景 + 执行）

```
POST /api/v1/task/planBuild
{"requestId":"r6","params":{
  "awemeShowId": "272886488",      // 必填，抖音号
  "productName": "益生元身体乳",     // 必填，商品名
  "videoId": 7690503084850281061,  // 选填，C1 的「本条素材」（= 素材表 H 视频id）
  "executionDate": "2026-10-05"    // 选填，计划名里的日期；默认今天
}}
```

> ⚠️ **影刀不需要知道场景**（A/B/C1/C2），也**不用先调 `decide`** —— 服务内部自己算 N + 判场景 + 执行。
>   
> 判定规则（与需求表一致）：
>
> - **已有千川计划** → C1（复用：加本条素材 + 改名）
> - **N < 3 且无计划** → A（投**随心推**，**不建**千川计划）
> - **N = 3 且无计划** → B（建乘方计划）
> - **N > 3 且无计划** → C2（建乘方计划）
>
> ⚠️ 判定依据会回显在 `data.reason` 里（例如「累计随心推 3 笔 = 3 且无千川计划 → 建乘方计划 + 加全部素材」），
>   
> `data.cumulativeCount` 是实际用的 N。

应答 `data`：

```json
{
  "advertiserId": 1778533513439244, "advertiserName": "婴童【儿童-商品】",
  "awemeUid": 12040301875,             // 抖音号的【数值 UID】
  "productId": 3778413499521302561,
  "planName": "272886488-益生元身体乳-10.5",
  "cumulativeCount": 3,                // 判定用的 N（回显便于排查）
  "reason": "累计随心推 3 笔 = 3 且无千川计划 → 建乘方计划 + 加全部素材",
  "action": "CREATE_PLAN",             // CREATE_SUIXINTUI（投随心推）/ CREATE_PLAN（建了新计划）/ REUSE_PLAN（复用已有）
  "adId": 1877746196794116,            // 建成或复用的计划 ID
  "materialCount": 12,                 // 本次加入计划的素材条数
  "dryRun": true,                      // ⚠️ 默认 true = 没真调
  "requestBody": "{...}",              // 实际（将要）发出的请求体
  "cells": [ { "row": 4992, "column": "K", "value": "全域PC" },
             { "row": 4992, "column": "N", "value": "已投" } ],
  "warnings": []
}
```

**三条路径**：

| 场景                  | `action`           | 服务做了什么                                                              | K 列回写   |
| ------------------- | ------------------ | ------------------------------------------------------------------- | ------- |
| **A**（N<3）          | `CREATE_SUIXINTUI` | 建**随心推**（**不建**千川计划）                                                | `全域随心推` |
| **B / C2**（N≥3 无计划） | `CREATE_PLAN`      | ① 查素材池（该达人能投这个商品的**全部**视频）→ ② 建乘方计划，素材随建计划一起带上，名字用 `抖音号-商品-执行日期M.D` | `全域PC`  |
| **C1**（已有计划）        | `REUSE_PLAN`       | ① 加**本条**素材 → ② 改名（日期改最新）                                           | `全域PC`  |

**结果怎么读**：

| `dryRun` | `adId` | `cells` | 含义                          |
| -------- | ------ | ------- | --------------------------- |
| true     | null   | 有       | **演练**：没真调，核对 `requestBody` |
| false    | 有      | 有       | **真办成**：按 `cells` 写回素材表     |
| —        | null   | 空       | **没办成**：看 `warnings` 找原因    |

> ⚠️ **回写两列**：`K 投放类型`（A 场景写 `全域随心推`，其余写 `全域PC`）**和** `N 投放状态 = 已投`。
>   
> 写 `全域PC` 是为了和 1303 行历史数据保持一致（表里**没有**「乘方」这个词）；
>   
> 写状态是为了**双重去重** —— 只写 K 的话，影刀写失败一次下次扫表还会选中该行 → 重复投放（花钱）。
>
> ⚠️ **C1 的「本条素材」怎么定**：调用时传 `videoId` 最准（影刀扫到哪行就传哪行）。
>   
> 不传时服务会去素材表找该【抖音号+商品】「投放类型为空」的行 ——
>   
> **只有唯一一条**才用它，**多条会返回空结果 + warning 要人工确认**（绝不瞎挑一条）。
>
> ⚠️ **账户来自素材表 `E 全域账号` 列**（精确匹配账户字典，且必须唯一）——
>   
> 服务**不再遍历 8 个账户**（一个商品只能在它授权的店铺下建计划）。
>
> ⚠️ **查计划时只要有账户没查成功，服务会直接中止**（返回 `code=1`），
>   
> 而不是猜「没有计划」—— 猜错就会**重复建计划（花钱）**。
>
> ⚠️ **这是花钱的操作**（建乘方计划），受 `dingtou.dry-run` 保护 —— **默认演练、不会真建**。
>
> ⚠️ 建计划的预算 / ROI 等参数**配在服务端**（`dingtou.plan.*`），影刀不用传。

### `processPending` —— ★ 批量投放（影刀最终形态）

**一次提交整批「待投放」的行**，服务自己算 N + 判场景 + 执行，返回要写回素材表的 `cells`。

```
POST /api/v1/task/processPending
{"requestId":"r-batch-1","params":{
  "executionDate": "2026-10-05",        // 选填，默认今天
  "rows": [
    { "row": 4992, "awemeShowId": "272886488", "productName": "益生元身体乳",
      "videoId": 7690503084850281061 },  // videoId 选填（= 素材表 H 视频id）
    { "row": 4993, "awemeShowId": "55454664964", "productName": "青少年洗发水" }
  ]
}}
```

> ⚠️ **影刀不需要做任何判断**：不用调 `decide`、不用算 N、不用选场景 ——
>   
> 只要把扫到的「投放状态=待投放」的行原样交上来。
>
> ⚠️ **同一【抖音号+商品】的多行会被去重**：只查一次计划（查计划是整条链路最贵的一步），
>   
> 多行的 `videoId` 会**合并后一次加进去** —— 不会漏素材。

应答 `data`：

```json
{
  "submittedRows": 2, "combinations": 2,
  "succeeded": 1, "failed": 1, "skipped": 0,
  "cells": [ { "row": 4992, "column": "K", "value": "全域PC" },
             { "row": 4992, "column": "N", "value": "已投" } ],
  "results": [
    { "awemeShowId": "272886488", "productName": "益生元身体乳",
      "status": "OK", "action": "REUSE_PLAN", "adId": 1877848024263911,
      "cumulativeCount": 3, "materialCount": 1, "reason": "...", "warnings": [] },
    { "awemeShowId": "55454664964", "productName": "青少年洗发水",
      "status": "FAILED", "action": null, "adId": null, "cumulativeCount": 0,
      "materialCount": 0, "reason": "", "warnings": ["商品名匹配不到唯一商品 ID"] }
  ],
  "warnings": []
}
```

**怎么读**：① 按 `cells` 写回素材表 → ② 看 `failed`/`skipped` 是不是 0 →
  
③ `warnings` 必须看（非空 = 有些行没全部办成）。

| `status`  | 含义                                               |
| --------- | ------------------------------------------------ |
| `OK`      | 办成了（建了计划 / 建了随心推 / 复用了计划）                        |
| `FAILED`  | 没办成（没账户 / 未授权 / 商品映射不到 / 素材池为空）—— 看它的 `warnings` |
| `SKIPPED` | 本批没轮到（通常是前面撞了频控，整批中止）                            |

> ⚠️ **重跑是安全的**：批次跑到一半失败时，影刀可以**原样重跑整批** ——
>   
> 已建过计划的组合会被「查计划」查到 → 走 C1 复用 → **不会重复建计划（不重复花钱）**。
>   
> 所以**不需要断点续跑**。
>
> ⚠️ **单个组合失败不中断整批**（没账户/未授权是等人工补数据，不该拖累别人）；
>   
> 但**撞频控会中断整批**并把剩余标记 `SKIPPED`（继续跑只会加重 5 分钟惩罚）。
>
> ⚠️ 整批可能跑很久 → 建议**开启超时转异步**（`dingtou.task.async-timeout-millis`），
>   
> 让影刀提交后立即拿 `taskId` 去轮询。

---

## 4. 异步轮询 `GET /api/v1/task/{taskId}`

**接口已就绪**（2026-10-05）。影刀在收到 `code=4 + taskId` 后，用它轮询取结果。

```
GET /api/v1/task/{taskId}
Header: X-Auth-Token: <同一个 token>
```

| 任务状态          | 返回                      | 影刀该怎么做                                 |
| ------------- | ----------------------- | -------------------------------------- |
| **不存在 / 已过期** | `code=1`                | 别重试，上报人工（可能任务从没提交成功）                   |
| **仍在执行**      | `code=4` + 同一个 `taskId` | **继续轮询**（建议间隔 5~10 秒）；⚠️ **不要重发原请求**   |
| **已完成**       | **原样回放**当初那份完整应答        | 按那份应答的 `code` 走（`0` 成功 / `1` 业务失败 / …） |
| **已失败**       | 同上（回放失败应答）              | 同上                                     |

> ⚠️ 完成的任务**保留 24 小时**，超时后轮询返回 `code=1`。影刀应在合理时间内取走结果。
>
> ⚠️ 轮询是**只读**的：回放的是当初那份应答，**不会重新执行**（重算可能因数据变化给出不同结果，也会重复花钱）。
>
> ⚠️ **哪些任务会转异步**：由 `dingtou.task.async-timeout-millis` 控制，
>   
> **默认 `0` = 全部同步**（行为与改造前完全一致，影刀侧无感）。
>   
> 只有当某个任务跑得比这个阈值还久，才会返回 `code=4 + taskId`。
>
> 📌 **上线节奏**：① 先部署服务（保持 0）→ ② 影刀实现「`code=4` 就轮询」并验证 →
>   
> ③ 再把阈值设成 `30000`（30 秒）。**顺序反了会让影刀拿到一个不知道怎么用的 taskId。**

---

## 5. 一个完整的影刀调用示例（curl）

```bash
# 1) 探活（免 token）
curl --noproxy '*' http://127.0.0.1:8080/actuator/health

# 2) 判定
curl --noproxy '*' -X POST http://127.0.0.1:8080/api/v1/task/decide \
  -H "X-Auth-Token: <token>" -H "Content-Type: application/json" \
  -d '{"requestId":"r-001","params":{
        "awemeShowId":"602085838","productName":"益生元面霜",
        "cumulativeCount":3,"hasPlan":false,"executionDate":"2026-09-30"}}'
```

> ⚠️ Windows 上如果配了系统代理（`HTTP_PROXY`），curl 访问 `127.0.0.1` 会被代理拦掉，
>   
> 报 `upstream connect failed`。**影刀侧也要注意走直连**。

---

## 6. 服务端配置与部署

**完整配置清单、环境变量、自检清单见 [`DEPLOY.md`](DEPLOY.md)** —— 本节只留最小必要项。

```yaml
dingtou:
  auth-token: ${DINGTOU_AUTH_TOKEN:}   # 必填，占位值会导致启动失败；必须来自【Windows 系统级】环境变量
  dry-run: true                        # 演练模式：只判定不真实投放；⚠️ 要真实建计划才改 false（花钱）
```

启动横幅会打印「配置 + 已注册任务清单」，一眼能看出服务到底有没有生效：

```
==================== dingtou 启动完成 ====================
配置: 鉴权=已启用(长度 27)，模式=演练(dry-run，不真实投放)
存储: 数据目录=...，订单快照有效期=24 小时
聚合: 预筛口径=ALL(全部行)，优先订单快照=true，预筛缓存=300 秒
数据源: 定投素材表=可读，订单总表=可读，账户字典=可读，商品映射表=可读，定向登记表=可读，达人巡查配置表=可读
千川: api=...，请求间隔=500ms（自适应上限 8000ms，频控重试 5 次，预算 300s），查询场景=[UNI_PROJECT, OVERALL_PROJECT]，翻页上限=100
已注册 5 个任务:
  - decide           按【达人+商品】判定投放场景 A/B/C1/C2（N 由服务自算）
  - kolPlanCostQuery 查消耗达标的千川计划（投放中 + 有消耗 + 消耗≥阈值），可选附带抖音号/抖音名
  - ping             探活：返回服务状态、时间与 6 张业务表的可读性
  - planAwemeLookup  按【投放账户 + 计划ID】批量查达人信息（抖音号 / 抖音名）
  - planQuery        查该【达人+商品】是否已有千川计划（遍历全部投放账户）
==========================================================
```
