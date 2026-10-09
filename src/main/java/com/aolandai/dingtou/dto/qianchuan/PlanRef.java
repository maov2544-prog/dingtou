package com.aolandai.dingtou.dto.qianchuan;

/**
 * 一个千川计划的定位坐标：<b>投放账户 + 计划 ID</b>。
 *
 * <p>为什么要带 {@code advertiserId}：千川详情接口的鉴权是<b>账户维度</b>的，
 * 只给 {@code adId} 就只能在 8 个账户里逐个试 —— 既慢又容易触发频控。
 * 调用方通常已经知道计划属于哪个账户（例如上一步查出来的），顺手带上即可。</p>
 *
 * @param advertiserId 投放账户 ID
 * @param adId         千川计划 ID
 */
public record PlanRef(long advertiserId, long adId) {

    @Override
    public String toString() {
        return advertiserId + ":" + adId;
    }
}
