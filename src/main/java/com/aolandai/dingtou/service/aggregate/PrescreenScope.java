package com.aolandai.dingtou.service.aggregate;

/**
 * 素材表预筛的口径：统计【达人 + 商品】的累计笔数时，数哪些行。
 *
 * <p>⚠️ <b>这个口径会直接改变 N，进而改变 A/B/C 的判定结果</b>，所以做成配置项 ——
 * 以后要改口径改配置就行，不用改代码。</p>
 */
public enum PrescreenScope {

    /**
     * 数该组合在定投素材表里的<b>全部行</b>。
     *
     * <p>对应老板 2026-09-30 09:32 的原话「这个表不用筛选」。
     * 偏保守：会把还没投的行也算进来，N 可能偏高 —— 但预筛本来就只是第一层近似，
     * 真正定档以订单快照（权威）为准。</p>
     */
    ALL("全部行"),

    /**
     * 只数<b>已投放</b>的行（{@code K 投放类型} 非空）。
     *
     * <p>语义更贴近「累计随心推笔数」，但依赖 `投放类型` 列被正确回填。</p>
     */
    INVESTED("仅已投放行");

    private final String label;

    PrescreenScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
