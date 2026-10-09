package com.aolandai.dingtou.service.normalize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link MdDate} 单测：M.D 文本的格式化与「跨年取最近年」解析。 */
class MdDateTest {

    // ==================== format ====================

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "2026-09-02, 9.2",
            "2026-09-10, 9.10",
            "2026-09-30, 9.30",
            "2026-10-05, 10.5",
            "2026-12-31, 12.31",
            "2026-01-01, 1.1",
    })
    @DisplayName("格式化：M.D，无前导零")
    void formatHasNoLeadingZero(String date, String expected) {
        assertEquals(expected, MdDate.format(LocalDate.parse(date)));
    }

    @Test
    @DisplayName("format 拒绝 null")
    void formatRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> MdDate.format(null));
    }

    // ==================== parse：同年 ====================

    @ParameterizedTest(name = "基准 {1} 解析 {0} → {2}")
    @CsvSource({
            "9.2,  2026-09-30, 2026-09-02",
            "9.30, 2026-09-30, 2026-09-30",
            "10.5, 2026-09-30, 2026-10-05",
            "9.19, 2026-09-30, 2026-09-19",
    })
    @DisplayName("同年内直接取该年")
    void parseSameYear(String text, String base, String expected) {
        assertEquals(LocalDate.parse(expected), MdDate.parse(text, LocalDate.parse(base)));
    }

    // ==================== parse：跨年取最近的年 ====================

    @Test
    @DisplayName("跨年：1 月初遇到 12.30 → 取上一年（更近）")
    void parseCrossYearPicksNearest() {
        assertEquals(LocalDate.of(2025, 12, 30),
                MdDate.parse("12.30", LocalDate.of(2026, 1, 5)));
    }

    @Test
    @DisplayName("跨年：12 月底遇到 1.2 → 取下一年（更近）")
    void parseCrossYearForward() {
        assertEquals(LocalDate.of(2027, 1, 2),
                MdDate.parse("1.2", LocalDate.of(2026, 12, 28)));
    }

    @Test
    @DisplayName("跨年：基准 7 月遇到 6.15 → 取同一年")
    void parseMidYearStaysInSameYear() {
        assertEquals(LocalDate.of(2026, 6, 15),
                MdDate.parse("6.15", LocalDate.of(2026, 7, 1)));
    }

    // ==================== parse：容错与非法输入 ====================

    @Test
    @DisplayName("允许首尾空白")
    void parseTrimsWhitespace() {
        assertEquals(LocalDate.of(2026, 9, 2),
                MdDate.parse("  9.2  ", LocalDate.of(2026, 9, 30)));
    }

    @Test
    @DisplayName("2.29 在非闰年跳过、闰年命中")
    void parseLeapDay() {
        // 2024 是闰年，基准 2026 → 候选 2025/2026/2027 全非闰 → 应报错
        assertThrows(IllegalArgumentException.class,
                () -> MdDate.parse("2.29", LocalDate.of(2026, 6, 1)));
        // 基准 2024 → 候选含 2024（闰年）→ 命中
        assertEquals(LocalDate.of(2024, 2, 29),
                MdDate.parse("2.29", LocalDate.of(2024, 6, 1)));
    }

    @Test
    @DisplayName("非法输入一律抛 IllegalArgumentException")
    void parseRejectsBadInput() {
        LocalDate base = LocalDate.of(2026, 9, 30);
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse(null, base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("   ", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("9", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse(".2", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("9.", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("九.二", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("13.1", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("0.1", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("9.0", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("9.32", base));
        assertThrows(IllegalArgumentException.class, () -> MdDate.parse("9.2", null));
    }

    @Test
    @DisplayName("round-trip：format → parse 回到同一天")
    void roundTrip() {
        LocalDate date = LocalDate.of(2026, 9, 30);
        assertEquals(date, MdDate.parse(MdDate.format(date), date));
    }
}
