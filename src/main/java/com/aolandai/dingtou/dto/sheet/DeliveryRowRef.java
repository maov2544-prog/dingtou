package com.aolandai.dingtou.dto.sheet;

/**
 * 定投素材表里的一行 —— <b>只带「回写所需」的最小信息</b>。
 *
 * <p>⚠️ 与服务端<b>不写 Excel</b> 的设计配套：服务只负责<b>定位</b>并告诉影刀
 * 「第几行、哪一列、写什么」，实际写入由影刀完成（不碰文件格式、不怕被占用）。</p>
 *
 * @param rowNumber    Excel 行号（含表头，<b>1-based</b>）—— 影刀直接用它定位
 * @param awemeShowId  该行的抖音号（原样，便于影刀核对）
 * @param productName  该行的产品名（原样，便于影刀核对）
 * @param rawAccount   {@code 全域账号}列<b>原值</b>（未清洗，报错时要原样回显）
 * @param accountName  清洗后的账户名；<b>空串 = 该行没有可用账户</b>（脏值/空值/变体）
 * @param typeValue    {@code 投放类型}列<b>当前值</b>；空串 = 还没填
 * @param videoId      {@code 视频id}列<b>原值</b> —— 实测就是<b>抖音视频ID</b>（19 位数字），
 *                     也就是加素材时要的 {@code aweme_item_id}；空串 = 该行没有
 */
public record DeliveryRowRef(
        int rowNumber,
        String awemeShowId,
        String productName,
        String rawAccount,
        String accountName,
        String typeValue,
        String videoId) {

    public DeliveryRowRef {
        awemeShowId = awemeShowId == null ? "" : awemeShowId.trim();
        productName = productName == null ? "" : productName.trim();
        rawAccount = rawAccount == null ? "" : rawAccount.trim();
        accountName = accountName == null ? "" : accountName.trim();
        typeValue = typeValue == null ? "" : typeValue.trim();
        videoId = videoId == null ? "" : videoId.trim();
    }

    /**
     * 「没有视频id」的构造。
     *
     * <p>不是为了让老代码少改几个字，而是一种<b>真实的业务状态</b>：
     * 素材表里没有 {@code 视频id} 列、或该行这一格是空的。
     * 调用方拿到 {@code videoId=""} 时应当报错（见 {@link #videoIdAsLong()}），
     * 而不是当成 0 继续往下走。</p>
     */
    public DeliveryRowRef(int rowNumber, String awemeShowId, String productName,
                          String rawAccount, String accountName, String typeValue) {
        this(rowNumber, awemeShowId, productName, rawAccount, accountName, typeValue, "");
    }

    /** 该行是否已定位到可用账户。 */
    public boolean hasAccount() {
        return !accountName.isEmpty();
    }

    /** 该行的「投放类型」是否还没填（A 场景只处理这些行）。 */
    public boolean typeIsEmpty() {
        return typeValue.isEmpty();
    }

    /** 该行是否有视频id。 */
    public boolean hasVideoId() {
        return !videoId.isEmpty();
    }

    /**
     * 视频id 转成数字（加素材接口要 {@code Long}）。
     *
     * <p>⚠️ 实测 H 列 4992 行里 4991 行是 19 位数字，<b>有 1 行是 11 位</b>（行 1737，
     * 值 {@code 80146481767}）—— 那多半是人工填错。所以这里<b>不做「尽力而为」的解析</b>：
     * 解析不了就返回空，由调用方报出<b>行号与原值</b>，让人去改表。</p>
     *
     * <p>⚠️ 抖音视频ID 是 19 位，<b>超过 int32</b>，必须用 {@code long}。</p>
     *
     * @return 解析成功返回 ID；空值或非数字返回 {@link java.util.Optional#empty()}
     */
    public java.util.Optional<Long> videoIdAsLong() {
        if (videoId.isEmpty()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Long.parseLong(videoId));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
