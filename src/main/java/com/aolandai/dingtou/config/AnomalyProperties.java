package com.aolandai.dingtou.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 「异常表」配置（前缀 {@code dingtou.anomaly}）。
 *
 * <p>老板 2026-10-08：「异常表」= <b>新建一张 Excel</b>，写入方式「跟交付状态表一样
 * <b>服务直接写</b>（追加一行，带备份）」，<b>路径要可以配置</b>。</p>
 *
 * <pre>
 * dingtou:
 *   anomaly:
 *     enabled: true
 *     sheet: "D:/rpa_workspace/shuixitui1/异常表.xlsx"
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.anomaly")
public class AnomalyProperties {

    /**
     * 是否写异常表。
     *
     * <p>关掉后异常只进日志（{@code warnings}）—— 排障时可以关，避免反复改共享盘文件。</p>
     */
    private boolean enabled = true;

    /**
     * 异常表路径（.xlsx）。
     *
     * <p>⚠️ 文件<b>不存在会自动创建</b>（带表头）；放共享盘的话路径要<b>带 IP</b>
     * （机器名解析不了，踩过）。</p>
     */
    private Path sheet;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getSheet() {
        return sheet;
    }

    public void setSheet(Path sheet) {
        this.sheet = sheet;
    }

    /** 启动横幅用。 */
    public String describe() {
        if (!enabled) {
            return "异常表=关（异常只进日志）";
        }
        return "异常表=" + (sheet == null ? "（未配置路径）" : sheet.toString());
    }
}
