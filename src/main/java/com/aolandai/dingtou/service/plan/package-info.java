/**
 * B / C1 / C2（乘方计划）的编排层。
 *
 * <p>这里做的是<b>跨接口的编排</b>（查计划 → 查素材池 → 建计划/加素材/改名 → 生成回写指令），
 * 每个千川接口本身的调用都在 {@code service/qianchuan} 下。</p>
 */
package com.aolandai.dingtou.service.plan;
