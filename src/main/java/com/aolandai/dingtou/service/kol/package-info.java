/**
 * 达人巡查 / 催更 业务域。
 *
 * <p>与「定投素材 → 千川计划」（{@code service/qianchuan/} + {@code service/aggregate/} 等）
 * 是<b>两条独立的业务线</b>：这里只负责「找出消耗达标的千川计划」，
 * 供后续按计划 ID 查达人信息（抖音号 / 抖音名）使用。</p>
 *
 * <p>分层约定（与其他域一致）：</p>
 * <ul>
 *   <li>本包 = <b>业务编排</b>（读哪张配置表、查哪些账户、怎么过滤、怎么汇总）；</li>
 *   <li>{@code service/qianchuan/} = <b>千川能力</b>（SDK 封装、翻页、频控、错误上抛）；</li>
 *   <li>{@code service/task/handler/} = <b>任务入口</b>（只解析参数，不含业务判断）。</li>
 * </ul>
 */
package com.aolandai.dingtou.service.kol;
