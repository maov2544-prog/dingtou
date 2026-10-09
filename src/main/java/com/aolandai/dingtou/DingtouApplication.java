package com.aolandai.dingtou;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 定投素材 → 千川计划 自动化执行器（影刀驱动的 HTTP 任务执行器）。
 *
 * <p>形态：影刀通过 HTTP 调用本服务，按 {@code taskCode} 分发到对应的 {@code TaskHandler}，
 * 服务执行「读表 / 调千川 API / 算决策」并返回结构化应答。</p>
 *
 * <p>职责边界：<b>影刀只做 RPA 动作与流程编排；全部逻辑与千川 API 调用都在本服务。</b></p>
 *
 * <p>{@code @org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan} 让 {@code config/} 下的 Properties 类被自动绑定，
 * 不用在每个类上写 {@code @EnableConfigurationProperties}。</p>
 */
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
@ConfigurationPropertiesScan
public class DingtouApplication {

    public static void main(String[] args) {
        SpringApplication.run(DingtouApplication.class, args);
    }
}
