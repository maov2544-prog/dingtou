package com.aolandai.dingtou.service.decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link PlanNamer} 单测：千川计划命名规则。 */
class PlanNamerTest {

    @ParameterizedTest(name = "{0} / {1} / {2} → {3}")
    @CsvSource({
            "602085838, 益生元面霜, 2026-09-30, 602085838-益生元面霜-9.30",
            "602085838, 益生元面霜, 2026-09-02, 602085838-益生元面霜-9.2",
            "789987wss, 生姜洗护,   2026-10-05, 789987wss-生姜洗护-10.5",
            "527796516_, 燕窝套装,  2026-12-31, 527796516_-燕窝套装-12.31",
    })
    @DisplayName("按「抖音号-商品-M.D」生成，日期无前导零")
    void buildsName(String aweme, String product, String date, String expected) {
        assertEquals(expected, PlanNamer.build(aweme, product, LocalDate.parse(date)));
    }

    @Test
    @DisplayName("商品名里含分隔符也能整名生成（所以解析计划名永远不可靠）")
    void productNameMayContainSeparator() {
        assertEquals("602085838-山茶花-套装-9.30",
                PlanNamer.build("602085838", "山茶花-套装", LocalDate.of(2026, 9, 30)));
    }

    @Test
    @DisplayName("参数非法时拒绝，不生成半成品计划名")
    void rejectsBadArguments() {
        LocalDate date = LocalDate.of(2026, 9, 30);
        assertThrows(IllegalArgumentException.class, () -> PlanNamer.build(null, "商品", date));
        assertThrows(IllegalArgumentException.class, () -> PlanNamer.build("  ", "商品", date));
        assertThrows(IllegalArgumentException.class, () -> PlanNamer.build("602085838", null, date));
        assertThrows(IllegalArgumentException.class, () -> PlanNamer.build("602085838", "  ", date));
        assertThrows(IllegalArgumentException.class, () -> PlanNamer.build("602085838", "商品", null));
    }
}
