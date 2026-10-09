package com.aolandai.dingtou.dto.kol;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link StatCostUnit} 单测：<b>单位换算错一次，筛选结果就会悄悄多一批或少一批</b>，
 * 而这类错误不会报错。所以把需求里给的两个例子直接钉成测试。
 */
class StatCostUnitTest {

    @Test
    @DisplayName("需求原文：消耗 > 100 元 → stat_cost > 100 × 100000 = 10000000")
    void hundredYuan() {
        assertEquals(10_000_000L, StatCostUnit.yuanToRaw(100));
    }

    @Test
    @DisplayName("配置表实际阈值：2000 元 → 200000000")
    void twoThousandYuan() {
        assertEquals(200_000_000L, StatCostUnit.yuanToRaw(2000));
    }

    @Test
    @DisplayName("1 元 = 100000（千分之一分：1 元 = 100 分 = 100 × 1000）")
    void oneYuan() {
        assertEquals(100_000L, StatCostUnit.yuanToRaw(1));
    }

    @Test
    @DisplayName("反向换算：原始值 → 元")
    void rawToYuan() {
        assertEquals(2000.0, StatCostUnit.rawToYuan(200_000_000L));
        assertEquals(2500.5, StatCostUnit.rawToYuan(250_050_000L));
    }

    @Test
    @DisplayName("带小数（千分之一分允许小数）：0.5 元 → 50000")
    void fractionalYuan() {
        assertEquals(50_000L, StatCostUnit.yuanToRaw(0.5));
    }
}
