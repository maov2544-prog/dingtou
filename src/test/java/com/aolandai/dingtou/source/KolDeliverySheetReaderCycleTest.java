package com.aolandai.dingtou.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「上一个周日」的判定 —— 这是「上一个周期」的准绳（老板 2026-10-06 明确）。
 *
 * <p>实测周期 sheet 名：{@code 2026-09-13到2026-10-04} / {@code 2026-10-11到2026-11-01}
 * / {@code 2026-11-08到2026-11-29} —— <b>结束日都是周日</b>。</p>
 */
class KolDeliverySheetReaderCycleTest {

    private static LocalDate sunday(LocalDate d) {
        return KolDeliverySheetReader.previousSunday(d);
    }

    @Test
    @DisplayName("★ 老板给的例子：参考日 10-11（周日）→ 上一个周日 10-04")
    void bossExample() {
        assertEquals(LocalDate.of(2026, 10, 4), sunday(LocalDate.of(2026, 10, 11)));
    }

    @Test
    @DisplayName("★ 参考日本身是周日 → 【再往前 7 天】（「上一个」不含今天）")
    void sundayReferenceGoesBackOneMoreWeek() {
        assertEquals(LocalDate.of(2026, 10, 4), sunday(LocalDate.of(2026, 10, 11)));
        assertEquals(LocalDate.of(2026, 9, 27), sunday(LocalDate.of(2026, 10, 4)));
    }

    @Test
    @DisplayName("★ 参考日是周一到周六 → 都是【上一周的周日】（不随今天星期几漂移）")
    void anyWeekdayMapsToTheSameSunday() {
        LocalDate expected = LocalDate.of(2026, 10, 4);
        // 10-05(一) ~ 10-10(六) 都应指向 10-04
        for (int day = 5; day <= 10; day++) {
            LocalDate ref = LocalDate.of(2026, 10, day);
            assertEquals(expected, sunday(ref), "参考日 " + ref + " 应指向 " + expected);
        }
    }

    @Test
    @DisplayName("返回值一定是个周日")
    void resultIsAlwaysSunday() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 400; i++) {
            LocalDate got = sunday(d);
            assertEquals(DayOfWeek.SUNDAY, got.getDayOfWeek(), "参考日 " + d + " 算出的不是周日");
            assertTrue(got.isBefore(d), "「上一个」周日必须早于参考日：" + got + " vs " + d);
            d = d.plusDays(1);
        }
    }

    @Test
    @DisplayName("解析周期 sheet 名：只有形如 yyyy-MM-dd到yyyy-MM-dd 的才算周期")
    void parseCycle() {
        assertTrue(KolDeliverySheetReader.parseCycle("2026-09-13到2026-10-04") != null);
        assertTrue(KolDeliverySheetReader.parseCycle("Sheet1") == null);
        assertTrue(KolDeliverySheetReader.parseCycle("2026-09-13") == null);
        assertTrue(KolDeliverySheetReader.parseCycle(null) == null);
    }
}
