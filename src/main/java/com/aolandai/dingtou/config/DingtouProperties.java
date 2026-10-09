package com.aolandai.dingtou.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 本工程主配置（前缀 {@code dingtou}）。
 *
 * <p>对应 application.yml：</p>
 * <pre>
 * dingtou:
 *   auth-token: ${DINGTOU_AUTH_TOKEN}   # 影刀调用时带的 X-Auth-Token，必须配置
 *   dry-run: true                       # 演练模式：只判定不真实投放；生产设 false
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou")
public class DingtouProperties {

    /**
     * 影刀调用时必须在 {@code X-Auth-Token} 头里带的值。
     *
     * <p>⚠️ <b>刻意不设默认值</b>：这个服务能被进程外调用，而它做的事情是要花钱的（建千川计划）。
     * 宁可启动就失败，也不要裸奔上线。</p>
     */
    @NotBlank(message = "dingtou.auth-token 必须配置（可用环境变量 DINGTOU_AUTH_TOKEN 注入）")
    private String authToken;

    /**
     * 演练模式：<b>只做判定与查询，不做任何会花钱的写操作</b>。
     *
     * <p>默认 {@code true} —— 新增任务默认是安全的，要真跑必须显式改成 {@code false}。</p>
     */
    private boolean dryRun = true;

    // ==================== 启动自检 ====================

    /**
     * token 不能是那几个一看就是占位的值。
     *
     * <p>故意让它在<b>启动阶段</b>失败：否则会退化成「服务正常跑着但谁都能调」，
     * 直到某天被扫到才发现在裸奔。</p>
     */
    @AssertTrue(message = "dingtou.auth-token 不能是占位值（change-me / test / 123456 等），请设置真实随机串")
    public boolean isAuthTokenNotPlaceholder() {
        if (authToken == null) {
            return true;
        }
        String normalized = authToken.trim().toLowerCase();
        return !normalized.isEmpty()
                && !normalized.equals("change-me")
                && !normalized.equals("changeme")
                && !normalized.equals("test")
                && !normalized.equals("123456")
                && normalized.length() >= 8;
    }

    /** 启动横幅用：让「到底有没有生效」一眼可见。 */
    public String describe() {
        return "鉴权=已启用(长度 " + (authToken == null ? 0 : authToken.length()) + ")"
                + "，模式=" + (dryRun ? "演练(dry-run，不真实投放)" : "生产(会真实投放)");
    }

    // ==================== getter / setter ====================

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }
}
