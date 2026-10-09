package com.aolandai.dingtou.service.qianchuan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlanMaterialResolver} 的<b>纯算法</b>单测（不碰网络）。
 *
 * <p>用老板给的<b>真实分享口令</b>做样本。</p>
 */
class PlanMaterialResolverTest {

    /** 老板给的真实样本。 */
    private static final String REAL_SHARE =
            "0.56 复制打开抖音，看看【尚尚孕期（努力版）的作品】孕期皮肤干敏的姐妹看过来✨澳兰黛孕产面膜 "
            + "无酒精香... https://v.douyin.com/0q-qmQYi6hQ/ 11/09 A@g.oQ :1pm OKW:/  ";

    @Test
    @DisplayName("★ 从真实分享口令里抠出 v.douyin.com 短链")
    void extractsShortLink() {
        var m = java.util.regex.Pattern
                .compile("https?://v\\.douyin\\.com/[\\w\\-]+/?").matcher(REAL_SHARE);
        assertTrue(m.find(), "应该能从分享口令里找到短链");
        assertEquals("https://v.douyin.com/0q-qmQYi6hQ/", m.group());
    }

    @Test
    @DisplayName("★ 视频ID：4 种出现形态都能抠出来")
    void extractsItemIdFromAllShapes() {
        assertEquals("7690582056733081211",
                PlanMaterialResolver.extractItemId("https://www.douyin.com/video/7690582056733081211"));
        assertEquals("7690582056733081211",
                PlanMaterialResolver.extractItemId("https://www.iesdouyin.com/share/video/7690582056733081211"));
        assertEquals("7690582056733081211",
                PlanMaterialResolver.extractItemId("https://www.douyin.com/?modal_id=7690582056733081211"));
        assertEquals("7690582056733081211",
                PlanMaterialResolver.extractItemId("x?item_id=7690582056733081211&y=1"));
    }

    @Test
    @DisplayName("抠不出视频ID时返回 null（不抛异常）")
    void returnsNullWhenNoItemId() {
        assertNull(PlanMaterialResolver.extractItemId("https://v.douyin.com/abc/"));
        assertNull(PlanMaterialResolver.extractItemId(""));
        assertNull(PlanMaterialResolver.extractItemId(null));
    }

    @Test
    @DisplayName("★ social_author_id：URL 编码的 JSON 里也能抠出来（要先解码）")
    void extractsAuthorIdFromEncodedJson() {
        // 真实形态：activity_info 是 URL 编码的 JSON
        String raw = "https://www.douyin.com/?activity_info=%7B%22social_author_id%22%3A"
                + "%223206653666920558%22%2C%22other%22%3A1%7D";
        assertEquals(3206653666920558L, PlanMaterialResolver.extractAuthorIdFromShare(raw));
    }

    @Test
    @DisplayName("social_author_id：未编码 / 带引号 / 冒号分隔 都认")
    void extractsAuthorIdFromLooseFormats() {
        assertEquals(123456789L,
                PlanMaterialResolver.extractAuthorIdFromShare("\"social_author_id\":\"123456789\""));
        assertEquals(123456789L,
                PlanMaterialResolver.extractAuthorIdFromShare("social_author_id=123456789"));
        assertEquals(123456789L,
                PlanMaterialResolver.extractAuthorIdFromShare("social_author_id : 123456789"));
    }

    @Test
    @DisplayName("social_author_id：抠不到返回 0（不抛异常）")
    void authorIdZeroWhenAbsent() {
        assertEquals(0L, PlanMaterialResolver.extractAuthorIdFromShare("nothing here"));
        assertEquals(0L, PlanMaterialResolver.extractAuthorIdFromShare(""));
        assertEquals(0L, PlanMaterialResolver.extractAuthorIdFromShare(null));
    }

    @Test
    @DisplayName("★ clean()：去空白/全角空格/emoji/零宽字符（昵称匹配用）")
    void cleansNickname() {
        assertEquals("辰辰麻麻", PlanMaterialResolver.clean("辰辰麻麻🌙"));
        assertEquals("小夏天妈妈", PlanMaterialResolver.clean(" 小夏天妈妈 "));
        assertEquals("abc", PlanMaterialResolver.clean("a\u200Db\uFE0Fc"));
        assertEquals("", PlanMaterialResolver.clean(null));
    }

    @Test
    @DisplayName("★ 从分享口令抠抖音名：不能把「的」吃进昵称（贪婪陷阱）")
    void extractsNicknameFromShare() {
        assertEquals("尚尚孕期（努力版）",
                PlanMaterialResolver.extractNicknameFromShare(REAL_SHARE));
        // 没有「的」的形态也要认
        assertEquals("辰辰麻麻🌙",
                PlanMaterialResolver.extractNicknameFromShare("看看【辰辰麻麻🌙作品】"));
        // 抠不到返回空串
        assertEquals("", PlanMaterialResolver.extractNicknameFromShare("没有昵称的口令"));
        assertEquals("", PlanMaterialResolver.extractNicknameFromShare(null));
    }

    @Test
    @DisplayName("分享口令里同时有短链和视频ID时，短链优先（走跳转拿 social_author_id）")
    void shortLinkTakesPriority() {
        // 这条口令里其实没有长链，只是验证正则不会把别的 URL 当短链
        var m = java.util.regex.Pattern
                .compile("https?://v\\.douyin\\.com/[\\w\\-]+/?").matcher(REAL_SHARE);
        assertTrue(m.find());
        // www.douyin.com 的长链不该被当成短链
        var m2 = java.util.regex.Pattern
                .compile("https?://v\\.douyin\\.com/[\\w\\-]+/?")
                .matcher("https://www.douyin.com/video/7690582056733081211");
        assertTrue(!m2.find(), "长链不该被短链正则命中");
    }
}
