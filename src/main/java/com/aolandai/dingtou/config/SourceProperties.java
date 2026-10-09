package com.aolandai.dingtou.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据源表路径配置（前缀 {@code dingtou.source}）。
 *
 * <p>对应 application.yml：</p>
 * <pre>
 * dingtou:
 *   source:
 *     delivery-sheet: D:/rpa_workspace/shuixitui1/26年视频交付表.xlsx
 *     order-sheet:    D:/rpa_workspace/shuixitui1/历史随心推全量订单总表.xlsx
 *     account-dict:   D:/rpa_workspace/shuixitui1/投放账户信息表.xlsx
 *     product-dict:   D:/rpa_workspace/shuixitui1/商品 ID 映射表(1).xlsx
 *     # sheet 名留空 = 取第一个 sheet（业务表按月改名，写死会在下个月崩）
 *     delivery-sheet-name: ""
 * </pre>
 *
 * <p>⚠️ <b>刻意不在启动时校验文件是否存在</b>：表可能由影刀在服务启动后才生成/替换，
 * 启动即失败会让运维顺序变得很脆。文件到底能不能读，由 {@code ping} 任务实时报告。</p>
 */
@Validated
@ConfigurationProperties(prefix = "dingtou.source")
public class SourceProperties {

    /** 定投素材表（同时也是历史随心推订单存量表）。 */
    @NotNull
    private Path deliverySheet;

    /** 历史随心推全量订单总表（每天从接口拉取后落盘的那份）。 */
    @NotNull
    private Path orderSheet;

    /** 投放账户字典（名称 / 账户 ID / 所属店铺）。 */
    @NotNull
    private Path accountDict;

    /** 商品 ID 映射表（商品 ID → 投放商品名）。 */
    @NotNull
    private Path productDict;

    /**
     * 达人巡查配置表（投放账户 + 消耗金额阈值），由 {@code KolPatrolConfigReader} 解析。
     *
     * <p>⚠️ 这是<b>共享盘上的 UNC 路径</b>（{@code \\192.168.10.253\文件共享rpa\...}），
     * 不是本机文件 —— 共享盘短暂不可达时任务应报错退出，而不是静默返回空列表。</p>
     */
    private Path kolPatrolConfig;

    /** 定投素材表的 sheet 名；留空 = 取第一个 sheet。 */
    private String deliverySheetName = "";

    /** 订单总表的 sheet 名；留空 = 取第一个 sheet。 */
    private String orderSheetName = "";

    /** 账户字典的 sheet 名；留空 = 取第一个 sheet。 */
    private String accountSheetName = "";

    /** 商品映射表的 sheet 名；留空 = 取第一个 sheet。 */
    private String productSheetName = "";

    /** 达人巡查配置表的 sheet 名；留空 = 取第一个 sheet。 */
    private String kolPatrolSheetName = "";

    /** 启动横幅用：把 5 张表的可读性一次性说清楚，省掉一半「为什么读不到表」的排查。 */
    public String describe() {
        return "定投素材表=" + readability(deliverySheet)
                + "，订单总表=" + readability(orderSheet)
                + "，账户字典=" + readability(accountDict)
                + "，商品映射表=" + readability(productDict)
                + "，达人巡查配置表=" + readability(kolPatrolConfig);
    }

    private static String readability(Path path) {
        if (path == null) {
            return "未配置";
        }
        if (!Files.exists(path)) {
            return "缺失(" + path + ")";
        }
        return Files.isReadable(path) ? "可读" : "不可读(被占用?)";
    }

    // ==================== getter / setter ====================

    public Path getDeliverySheet() {
        return deliverySheet;
    }

    public void setDeliverySheet(Path deliverySheet) {
        this.deliverySheet = deliverySheet;
    }

    public Path getOrderSheet() {
        return orderSheet;
    }

    public void setOrderSheet(Path orderSheet) {
        this.orderSheet = orderSheet;
    }

    public Path getAccountDict() {
        return accountDict;
    }

    public void setAccountDict(Path accountDict) {
        this.accountDict = accountDict;
    }

    /**
     * 《定向投放登记表》路径（老板 2026-10-08 重新定义用途）。
     *
     * <p>含义 = <b>已经在千川建计划的【达人 × 商品 × 账户】</b>。
     * 处理某组合前先查它，命中就用它给的账户去查计划（范围收窄到 1 个账户）。</p>
     *
     * <p>⚠️ 表结构是 <b>3 行复合表头</b>（账户 → 分组 → 字段），商品在<b>奇数列</b>。
     * 详见 {@code TargetingRegistryReader}。</p>
     */
    private Path targetingSheet;

    /**
     * <b>是否由服务直接写《定投素材表》</b>（老板 2026-10-08 确认的方案 A）。
     *
     * <p>⚠️ <b>开了之后影刀就不该再写素材表</b>，否则两边互相覆盖。
     * 这是「服务自己定时跑（轮询）」的前提 —— 不写表的话定时任务的结果落不下去。</p>
     */
    private boolean directWrite = true;

    /**
     * 表读取缓存秒数（默认 300 = 5 分钟）。
     *
     * <p>⚠️ 缓存以<b>文件修改时间</b>为主判据 —— 文件一改就失效，
     * 所以这个 TTL 设长一点也安全（防的是「同一批处理内反复读同一份没变的文件」）。</p>
     */
    @jakarta.validation.constraints.PositiveOrZero
    private int sheetCacheSeconds = 300;

    public int getSheetCacheSeconds() {
        return sheetCacheSeconds;
    }

    public void setSheetCacheSeconds(int sheetCacheSeconds) {
        this.sheetCacheSeconds = sheetCacheSeconds;
    }

    public boolean isDirectWrite() {
        return directWrite;
    }

    public void setDirectWrite(boolean directWrite) {
        this.directWrite = directWrite;
    }

    public Path getTargetingSheet() {
        return targetingSheet;
    }

    public void setTargetingSheet(Path targetingSheet) {
        this.targetingSheet = targetingSheet;
    }

    public Path getProductDict() {
        return productDict;
    }

    public void setProductDict(Path productDict) {
        this.productDict = productDict;
    }

    public String getDeliverySheetName() {
        return deliverySheetName;
    }

    public void setDeliverySheetName(String deliverySheetName) {
        this.deliverySheetName = deliverySheetName;
    }

    public String getOrderSheetName() {
        return orderSheetName;
    }

    public void setOrderSheetName(String orderSheetName) {
        this.orderSheetName = orderSheetName;
    }

    public String getAccountSheetName() {
        return accountSheetName;
    }

    public void setAccountSheetName(String accountSheetName) {
        this.accountSheetName = accountSheetName;
    }

    public String getProductSheetName() {
        return productSheetName;
    }

    public void setProductSheetName(String productSheetName) {
        this.productSheetName = productSheetName;
    }

    public Path getKolPatrolConfig() {
        return kolPatrolConfig;
    }

    public void setKolPatrolConfig(Path kolPatrolConfig) {
        this.kolPatrolConfig = kolPatrolConfig;
    }

    public String getKolPatrolSheetName() {
        return kolPatrolSheetName;
    }

    public void setKolPatrolSheetName(String kolPatrolSheetName) {
        this.kolPatrolSheetName = kolPatrolSheetName;
    }
}
