package com.aolandai.dingtou.dto.sheet;

/**
 * 一条「请影刀写进 Excel」的指令。
 *
 * <p>⚠️ 服务端<b>不写 Excel</b>，只返回这个 —— 由影刀执行写入。
 * 好处：不碰文件格式（素材表里有 3 个条件格式 + 自动筛选 + 冻结窗格，POI 改写有丢失风险）、
 * 不怕文件被业务同事的 Excel 占用、也不用每次加载 4993 行的大工作簿。</p>
 *
 * <p>放在 {@code dto/sheet} 而不是某个业务域包里：<b>它是通用的回写指令</b>，
 * A 场景（随心推）和 B/C1/C2（乘方计划）都用同一套语义 ——
 * 挂在某个业务域下会让另一个域被迫依赖它。</p>
 *
 * @param row     Excel 行号（含表头，<b>1-based</b>）
 * @param column  列字母（如 {@code K}）
 * @param value   要写入的值（如 {@code 全域随心推}）
 */
public record CellWrite(int row, String column, String value) {

    public CellWrite {
        column = column == null ? "" : column.trim();
        value = value == null ? "" : value;
    }

    /** 一行式描述，便于日志与排障。 */
    public String describe() {
        return column + row + " = " + value;
    }
}
