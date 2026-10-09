package com.aolandai.dingtou.config;

import com.aolandai.dingtou.service.task.TaskHandler;
import com.aolandai.dingtou.service.task.TaskRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动横幅：把「已注册哪些任务」「当前是演练还是生产」打到日志里。
 *
 * <p>为什么值得单独写一个类：这个服务没有界面，运维只能看日志。
 * 启动时一行「已注册 2 个任务: ping, decide」能省掉一半的「为什么调不通」排查。</p>
 */
@Component
public class StartupBanner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupBanner.class);

    private final TaskRegistry registry;
    private final DingtouProperties properties;
    private final StoreProperties storeProperties;
    private final AggregateProperties aggregateProperties;
    private final SourceProperties sourceProperties;
    private final QianchuanProperties qianchuanProperties;
    private final SuixintuiProperties suixintuiProperties;
    private final PlanProperties planProperties;
    private final TaskProperties taskProperties;

    public StartupBanner(TaskRegistry registry, DingtouProperties properties,
                         StoreProperties storeProperties, AggregateProperties aggregateProperties,
                         SourceProperties sourceProperties, QianchuanProperties qianchuanProperties,
                         SuixintuiProperties suixintuiProperties,
                         PlanProperties planProperties,
                         TaskProperties taskProperties) {
        this.registry = registry;
        this.properties = properties;
        this.storeProperties = storeProperties;
        this.aggregateProperties = aggregateProperties;
        this.sourceProperties = sourceProperties;
        this.qianchuanProperties = qianchuanProperties;
        this.suixintuiProperties = suixintuiProperties;
        this.planProperties = planProperties;
        this.taskProperties = taskProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("==================== dingtou 启动完成 ====================");
        log.info("配置: {}", properties.describe());
        log.info("存储: {}", storeProperties.describe());
        log.info("聚合: {}", aggregateProperties.describe());
        // 数据源与千川配置也打出来：服务没有界面，运维只能看日志。
        // 「表可读吗」「查哪些场景/翻多少页/限流参数多少」不打印的话，
        // 出问题只能靠猜（已踩过「翻页上限写死 20 漏数据」的坑）。
        log.info("数据源: {}", sourceProperties.describe());
        log.info("千川: {}", qianchuanProperties.describe());
        log.info("随心推: {}", suixintuiProperties.describe());
        log.info("乘方计划: {}", planProperties.describe());
        log.info("任务执行: {}", taskProperties.describe());
        log.info("已注册 {} 个任务:", registry.size());
        // ⚠️ SLF4J 的占位符只认 `{}`，不认 `{:<20}` 这类格式说明符 ——
        // 写了 `{:<20}` 会被当普通文本原样输出，而且第一个 `{}` 会被后面的参数顶掉。
        // 要对齐就用 String.format 先拼好，再交给单个 `{}`。
        registry.codes().forEach(code -> registry.find(code).ifPresent(handler ->
                log.info("  - {}", String.format("%-16s %s", code, describe(handler)))));
        log.info("==========================================================");
    }

    private static String describe(TaskHandler handler) {
        String description = handler.description();
        return description == null || description.isBlank()
                ? handler.getClass().getSimpleName()
                : description;
    }
}
