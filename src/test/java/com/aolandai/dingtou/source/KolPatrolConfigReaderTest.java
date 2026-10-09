package com.aolandai.dingtou.source;

import com.aolandai.dingtou.config.SourceProperties;
import com.aolandai.dingtou.dto.kol.KolAccountConfig;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KolPatrolConfigReader} 单测：在临时目录造一张同结构的表，<b>绝不读共享盘上的真实文件</b>。
 *
 * <p>重点覆盖「配置写错也不能让任务崩，但必须能被调用方看见」这条设计。</p>
 */
class KolPatrolConfigReaderTest {

    @TempDir
    Path tempDir;

    private Path file;
    private KolPatrolConfigReader reader;

    @BeforeEach
    void setUp() throws IOException {
        file = tempDir.resolve("kol-patrol.xlsx");
        SourceProperties props = new SourceProperties();
        props.setKolPatrolConfig(file);
        reader = new KolPatrolConfigReader(props);
    }

    @Test
    @DisplayName("正常读：账户名 / 账户ID / 阈值（元）逐字解析")
    void readsAllColumns() throws IOException {
        writeSheet(new String[][] {
            {"账户名称", "账户 ID", "消耗金额阈值（元）", "周期完成状态"},
            {"官方【母婴商品】", "1823379540880396", "2000", ""},
            {"官方【青少年-商品】", "1776098375039040", "2000", ""},
            {"婴童【儿童-商品】", "1778533513439244", "1500", ""},
        });

        List<KolAccountConfig> configs = reader.readAll();

        assertEquals(3, configs.size());
        assertEquals("官方【母婴商品】", configs.get(0).accountName());
        assertEquals("1823379540880396", configs.get(0).advertiserId());
        assertEquals(2000.0, configs.get(0).thresholdYuan());
        assertEquals(1823379540880396L, configs.get(0).advertiserIdAsLong());
        assertEquals(200_000_000L, configs.get(0).thresholdRaw());
        assertEquals(2, configs.get(0).rowNumber());

        assertEquals(1500.0, configs.get(2).thresholdYuan());
        assertEquals(150_000_000L, configs.get(2).thresholdRaw());
    }

    @Test
    @DisplayName("⚠️ 账户 ID 按【文本】读，末位不能被改写（16 位数字）")
    void advertiserIdIsReadAsText() throws IOException {
        writeSheet(new String[][] {
            {"账户名称", "账户 ID", "消耗金额阈值（元）", "周期完成状态"},
            {"官方【母婴商品】", "1823379540880396", "2000", ""},
        });

        // 末位 6 必须原样保留（按数值读会变成 ...390 / ...400 之类）
        assertEquals("1823379540880396", reader.readAll().get(0).advertiserId());
    }

    @Test
    @DisplayName("阈值留空 → NaN（保留该行，交由调用方决定跳过）")
    void blankThresholdIsNan() throws IOException {
        writeSheet(new String[][] {
            {"账户名称", "账户 ID", "消耗金额阈值（元）", "周期完成状态"},
            {"官方【母婴商品】", "1823379540880396", "2000", ""},
            {"官方【孕产-商品】", "1771298760893511", "", ""},
        });

        List<KolAccountConfig> configs = reader.readAll();
        assertEquals(2, configs.size());
        assertTrue(KolPatrolConfigReader.hasThreshold(configs.get(0)));
        assertFalse(KolPatrolConfigReader.hasThreshold(configs.get(1)));
        assertTrue(Double.isNaN(configs.get(1).thresholdYuan()));
    }

    @Test
    @DisplayName("阈值不是数字 → NaN + 不抛异常（表是业务同事在维护，一格填错不该让任务挂掉）")
    void nonNumericThresholdIsNan() throws IOException {
        writeSheet(new String[][] {
            {"账户名称", "账户 ID", "消耗金额阈值（元）", "周期完成状态"},
            {"官方【母婴商品】", "1823379540880396", "两千", ""},
        });

        List<KolAccountConfig> configs = reader.readAll();
        assertEquals(1, configs.size());
        assertFalse(KolPatrolConfigReader.hasThreshold(configs.get(0)));
    }

    @Test
    @DisplayName("阈值列名兼容半角括号 / 无括号（表头被人手改过也不能崩）")
    void thresholdColumnNameTolerant() throws IOException {
        writeSheet(new String[][] {
            {"账户名称", "账户 ID", "消耗金额阈值(元)"},
            {"官方【母婴商品】", "1823379540880396", "2000"},
        });

        assertEquals(2000.0, reader.readAll().get(0).thresholdYuan());
    }

    @Test
    @DisplayName("空行跳过（配置表尾部有大量空行）")
    void skipsBlankRows() throws IOException {
        writeSheet(new String[][] {
            {"账户名称", "账户 ID", "消耗金额阈值（元）"},
            {"官方【母婴商品】", "1823379540880396", "2000"},
            {"", "", ""},
            {"", "", ""},
        });

        assertEquals(1, reader.readAll().size());
    }

    // ==================== 工具 ====================

    private void writeSheet(String[][] rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
    }
}
