# 归档文档（历史过程稿）

> 这里的文档**不再维护**，仅作**决策留痕**保留。
> 想知道**现在**系统怎么跑，请看 `docs/` 下的 5 份核心文档。

## 为什么归档

这些是 **2026-09-29 / 09-30 的「待确认 / 待老板拍板」过程稿**。
它们描述的阶段（开工前规划、工程迁移、重构方案）**都已经完成**，
内容与现状**不符**，留着会误导人。

## 归档清单

| 文件 | 当时用途 | 为什么过时 |
|---|---|---|
| `NEXT-REQUIREMENT-PLAN.md` | 新需求实施方案（09-29） | 开头写着「**等老板确认后再开工**」→ 早已开工 |
| `NEXT-REQUIREMENT-REUSE.md` | 复用评估（09-29） | 对照的是**旧工程** `oceanengine`，迁移已完成 |
| `NEXT-PROJECT-ARCHITECTURE.md` | 新工程技术架构（09-29） | 标「待确认」→ 架构已落地 |
| `NEXT-PROJECT-STRUCTURE.md` | 工程结构与迁移（09-29） | 标「待确认」→ 已迁移完成 |
| `NEXT-REQUIREMENT-CONFIRM.md` | 5 步流程理解确认稿（09-30） | 已被 `FLOW-OVERVIEW.md` **完全覆盖** |
| `PROJECT-STRUCTURE-REFACTOR.md` | 包结构 + 逐文件映射（09-30） | 重构已完成，逐文件映射已无意义 |

⚠️ 这几份之间还**互相取代**（如 `NEXT-REQUIREMENT-CONFIRM` 自称取代 `NEXT-REQUIREMENT-PLAN` 的一~四节），
重复度很高 —— 这也是归档的原因之一。

## 现在的核心文档

| 文档 | 讲什么 |
|---|---|
| `FLOW-OVERVIEW.md` | **全流程**：每步读什么表 / 调什么接口 / 得到什么信息 |
| `API-CONTRACT.md` | **接口契约**：所有 taskCode 的入参出参、错误码 |
| `DEPLOY.md` | **部署**：配置项、环境变量、启动 |
| `RPA-INTEGRATION.md` | **影刀接入**：伪代码、curl 自测、上线切换步骤 |
| `CODE-STYLE.md` | **代码风格**：包结构、命名、日志约定 |
