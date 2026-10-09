package com.aolandai.dingtou.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * JSONL 文件存储基类：<b>内存持有全量记录 + 每次变更原子落盘</b>。
 *
 * <p>为什么选「全量重写」而不是「追加」：这些流水每天只有几十~几百条，
 * 全量重写能保证<b>磁盘内容与内存状态严格一致</b>，进程重启后可完整恢复，
 * 也不会出现「追加了半行」的损坏文件。</p>
 *
 * <h2>原子落盘（照抄老工程 OceanEngine 的验证过的做法）</h2>
 * <ol>
 *   <li>写同目录临时文件；</li>
 *   <li>{@code FileChannel.force(true)} —— 强制刷盘，否则断电时内容可能还在 OS 缓存里；</li>
 *   <li>{@code ATOMIC_MOVE} 替换正式文件 —— 要么是旧内容，要么是新内容，<b>不存在半份</b>。</li>
 * </ol>
 *
 * <p>⚠️ 子类必须在 {@link #reindex()} 里重建自己的内存索引 —— 它在启动加载后、
 * 以及<b>每一次变更落盘前</b>都会被调用。</p>
 *
 * @param <T> 单条记录的类型（建议用 {@code record}）
 */
public abstract class JsonlStore<T> {

    /** 共享 ObjectMapper。⚠️ 必须注册 Jdk8Module（Optional）与 JavaTimeModule（LocalDate/DateTime）。 */
    protected static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path filePath;
    private final Class<T> type;
    private final List<T> records = new ArrayList<>();

    protected JsonlStore(Path filePath, Class<T> type) throws IOException {
        if (filePath == null) {
            throw new IOException("存储文件路径为空");
        }
        if (type == null) {
            throw new IOException("记录类型为空");
        }
        this.filePath = filePath;
        this.type = type;
        loadRecords();
    }

    /**
     * 重建索引。⚠️ <b>子类构造器的最后一行必须调用它</b>（{@code super(...); ...; buildIndex();}）。
     *
     * <p>为什么不在基类构造器里自动调：那是典型的「<b>构造器里调用可覆写方法</b>」陷阱 ——
     * Java 的字段初始化器在 {@code super()} <b>之后</b>才执行，所以基类构造器里调 {@code reindex()} 时，
     * 子类那些 {@code private Map<...> index = new LinkedHashMap<>()} 还都是 {@code null}，
     * 一进去就 NPE，而且报错位置在子类，排查起来很绕。</p>
     */
    protected final void buildIndex() {
        reindex();
    }

    public Path filePath() {
        return filePath;
    }

    /** 全部记录（只读快照）。 */
    protected synchronized List<T> records() {
        return new ArrayList<>(records);
    }

    protected synchronized int size() {
        return records.size();
    }

    /**
     * 变更记录的唯一入口：<b>改内存 → 重建索引 → 原子落盘；落盘失败则回滚内存</b>。
     *
     * <p>回滚这一步很关键：如果只改内存不管落盘失败，进程会带着一份「以为已经记下、
     * 其实磁盘上没有」的状态继续跑 —— 对幂等流水来说就是「以为防住了重复，其实没防」。</p>
     */
    protected synchronized <R> R mutate(Function<List<T>, R> action) throws IOException {
        List<T> backup = new ArrayList<>(records);
        try {
            R result = action.apply(records);
            reindex();
            persist();
            return result;
        } catch (IOException | RuntimeException e) {
            records.clear();
            records.addAll(backup);
            reindex();
            throw e;
        }
    }

    /** 重建内存索引。启动加载后与每次变更落盘前都会被调用。 */
    protected abstract void reindex();

    // ==================== 落盘 / 加载 ====================

    /** 先写临时文件并强制刷盘，再原子替换正式文件，避免崩溃留下半份流水。 */
    private void persist() throws IOException {
        Path target = filePath.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            List<String> lines = new ArrayList<>(records.size());
            for (T record : records) {
                lines.add(MAPPER.writeValueAsString(record));
            }
            Files.write(temp, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 启动加载：逐行解析。
     *
     * <p>⚠️ 坏行<b>直接拒绝启动</b>，不做「跳过坏行继续跑」——
     * 一份损坏的幂等流水，静默跳过等于放弃了防重复能力，后果是重复建计划（花钱）。</p>
     */
    private void loadRecords() throws IOException {
        if (!Files.exists(filePath)) {
            return;
        }
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(MAPPER.readValue(line, type));
            } catch (IOException e) {
                // 把出错的那行也带出来 —— 只报「第几行坏了」在现场根本没法定位
                String preview = line.length() > 300 ? line.substring(0, 300) + "…" : line;
                throw new IOException("存储文件损坏，拒绝启动；文件=" + filePath.toAbsolutePath()
                        + " 行号=" + lineNumber + " 内容=" + preview, e);
            }
        }
    }
}
