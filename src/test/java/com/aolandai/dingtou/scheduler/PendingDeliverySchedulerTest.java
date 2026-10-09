package com.aolandai.dingtou.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PendingDeliveryScheduler#isBlockedToday} 单测 —— 「投放中 M.D」当天不再处理的判定。
 *
 * <p>与影刀同一套口径：日期 == 今天 → 跳过；日期 &lt; 今天 → 重新处理（自动顺延）。</p>
 */
class PendingDeliverySchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    @Test
    @DisplayName("★ 「投放中 10.8」+ 今天 10.8 → 跳过（当天已检测过）")
    void sameDayIsSkipped() {
        assertTrue(PendingDeliveryScheduler.isBlockedToday("投放中 10.8", TODAY));
    }

    @Test
    @DisplayName("★ 「投放中 10.7」+ 今天 10.8 → 不跳过（自动顺延重测）")
    void previousDayIsRetried() {
        assertFalse(PendingDeliveryScheduler.isBlockedToday("投放中 10.7", TODAY));
    }

    @Test
    @DisplayName("跨月：今天 11.1，「投放中 10.31」→ 不跳过")
    void crossMonthIsRetried() {
        assertFalse(PendingDeliveryScheduler.isBlockedToday("投放中 10.31",
                LocalDate.of(2026, 11, 1)));
        assertTrue(PendingDeliveryScheduler.isBlockedToday("投放中 11.1",
                LocalDate.of(2026, 11, 1)));
    }

    @Test
    @DisplayName("月份对但日期不对 → 不跳过（只看月+日，不看年）")
    void sameMonthDifferentDay() {
        assertFalse(PendingDeliveryScheduler.isBlockedToday("投放中 10.9", TODAY));
    }

    @Test
    @DisplayName("其它投放状态（待投放 / 已投 / 空）→ 不跳过")
    void otherStatusesAreNotBlocked() {
        assertFalse(PendingDeliveryScheduler.isBlockedToday("待投放", TODAY));
        assertFalse(PendingDeliveryScheduler.isBlockedToday("已投", TODAY));
        assertFalse(PendingDeliveryScheduler.isBlockedToday("", TODAY));
        assertFalse(PendingDeliveryScheduler.isBlockedToday(null, TODAY));
    }

    @Test
    @DisplayName("格式不对的「投放中 xxx」→ 不跳过（宁可多跑一次，不要漏处理）")
    void malformedIsNotBlocked() {
        assertFalse(PendingDeliveryScheduler.isBlockedToday("投放中", TODAY));
        assertFalse(PendingDeliveryScheduler.isBlockedToday("投放中 abc", TODAY));
        assertFalse(PendingDeliveryScheduler.isBlockedToday("投放中 10.8 备注", TODAY));
    }

    @Test
    @DisplayName("前后空格容错")
    void trimsWhitespace() {
        assertTrue(PendingDeliveryScheduler.isBlockedToday("  投放中 10.8  ", TODAY));
    }
}
