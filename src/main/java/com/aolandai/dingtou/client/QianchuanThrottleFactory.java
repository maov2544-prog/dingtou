package com.aolandai.dingtou.client;

import com.aolandai.dingtou.config.QianchuanProperties;
import org.springframework.stereotype.Component;

/**
 * {@link QianchuanThrottle} 的工厂：把配置里的限流参数组装成
 * {@link QianchuanThrottle.Settings}。
 *
 * <p>⚠️ <b>限流器不能做成 Spring 单例</b>：它的自适应间隔是<b>按批</b>变化的，
 * 单例会让两个并发任务互相干扰对方的间隔（A 任务被频控降速，B 任务无辜跟着变慢）。
 * 所以这里只提供 {@link #newThrottle()}，由每个批量任务自己 new 一个。</p>
 */
@Component
public class QianchuanThrottleFactory {

    private final QianchuanProperties properties;

    public QianchuanThrottleFactory(QianchuanProperties properties) {
        this.properties = properties;
    }

    /** 为一次批量任务创建一个新的限流器。 */
    public QianchuanThrottle newThrottle() {
        return new QianchuanThrottle(new QianchuanThrottle.Settings(
                properties.getRequestIntervalMillis(),
                properties.getMaxRequestIntervalMillis(),
                properties.getRateLimitRetries(),
                properties.getRateLimitBudgetMillis(),
                properties.getRateLimitInitialBackoffMillis(),
                properties.getRateLimitMaxBackoffMillis(),
                properties.isRateLimitPeakAvoidance()));
    }
}
