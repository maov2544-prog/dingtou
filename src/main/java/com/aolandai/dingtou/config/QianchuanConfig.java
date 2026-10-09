package com.aolandai.dingtou.config;

import com.aolandai.dingtou.client.AccessTokenProvider;
import com.aolandai.dingtou.client.HttpTokenProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 千川 / 随心推 接入装配。
 *
 * <p>两个 token 提供者都是 {@link AccessTokenProvider} 类型，所以用 <b>Bean 名 + {@code @Qualifier}</b>
 * 区分 —— 千川与随心推是两套独立的 token，拿错会一直鉴权失败。</p>
 *
 * <p>⚠️ <b>这里刻意不做任何「启动时换取 token」的动作</b>：换取依赖外部服务，
 * 启动即换会让「对方服务抖一下 → 我方服务起不来」。token 在第一次真正调用时才换取并缓存。</p>
 */
@Configuration
public class QianchuanConfig {

    /** 千川 PC 版 token 提供者。 */
    @Bean("qianchuanAccessTokenProvider")
    public AccessTokenProvider qianchuanAccessTokenProvider(QianchuanProperties properties) {
        return new HttpTokenProvider(properties.qianchuanEndpoint());
    }

    /** 随心推 token 提供者。 */
    @Bean("suixintuiAccessTokenProvider")
    public AccessTokenProvider suixintuiAccessTokenProvider(QianchuanProperties properties) {
        return new HttpTokenProvider(properties.suixintuiEndpoint());
    }

    /** 便于启动横幅与探活读取配置概览（不涉及凭据本身）。 */
    @Bean
    public String qianchuanConfigSummary(QianchuanProperties properties) {
        return properties.describe();
    }

    /** 避免「注入了两个同类型 Bean」时的歧义 —— 显式声明默认走千川侧。 */
    @Bean("defaultAccessTokenProvider")
    public AccessTokenProvider defaultAccessTokenProvider(
            @Qualifier("qianchuanAccessTokenProvider") AccessTokenProvider qianchuan) {
        return qianchuan;
    }
}
