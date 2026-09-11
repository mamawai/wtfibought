package com.mawai.wiibagent.chat.store;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文字节的编解码：老行（Java 对象流包 JSON）读得出来、新行（裸 JSON）写读一致、老行读进来再写出去不丢东西。
 * <p>
 * 老格式靠 golden 文件钉住：{@code src/test/resources/chat/chat-context-legacy.bin} 是迁移前的序列化器
 * 对 {@link #fixture()} 生成的真字节，库里的老行就长这样。
 */
class ChatContextCodecTest {

    /** 生产会存的全部形态：用户原文、带图的用户消息、纯文本回答、带 tool_call 的回答、配对回执、压缩摘要 */
    static List<Message> fixture() {
        return List.of(
                new UserMessage("2026-09-10 10:00 ｢用户提问｣ 帮我盯 BTC"),
                UserMessage.builder().text("看看这张图")
                        .media(List.of(Media.builder().id("m1").name("chart.png")
                                .mimeType(MimeType.valueOf("image/png"))
                                .data(new byte[]{1, 2, 3, 4, 5}).build()))
                        .build(),
                new AssistantMessage("BTC 现价 97000，趋势偏多。"),
                AssistantMessage.builder().content("")
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("call_1", "function", "run_deep_analysis",
                                        "{\"symbol\":\"BTCUSDT\"}"),
                                new AssistantMessage.ToolCall("call_2", "function", "market_snapshot",
                                        "{\"symbol\":\"ETHUSDT\"}")))
                        .properties(Map.of("wiib_anthropic_blocks",
                                "[{\"type\":\"thinking\",\"signature\":\"sig==\"},{\"type\":\"tool_use\",\"id\":\"call_1\"}]"))
                        .build(),
                ToolResponseMessage.builder().responses(List.of(
                                new ToolResponseMessage.ToolResponse("call_1", "run_deep_analysis",
                                        "{\"status\":\"OK\",\"narrative\":\"多头\"}"),
                                new ToolResponseMessage.ToolResponse("call_2", "market_snapshot",
                                        "{\"markPrice\":97000}")))
                        .build(),
                new SystemMessage("## 早前对话摘要：\n── 第1段 ──\n用户在 92000 附近建了多单"));
    }

    @Test
    void 老格式golden读得出来() throws Exception {
        byte[] golden = golden();

        // 首两字节是对象流魔数：golden 必须一直是老格式，被谁拿新格式覆盖了就等于兼容没在测
        assertThat(golden[0]).isEqualTo((byte) 0xAC);
        assertThat(golden[1]).isEqualTo((byte) 0xED);
        assertSameMessages(fixture(), ChatContextCodec.read(golden));
    }

    @Test
    void 新格式写出去再读回来一致() throws Exception {
        byte[] bytes = ChatContextCodec.write(fixture());

        assertThat(bytes[0]).isEqualTo((byte) '{');
        assertSameMessages(fixture(), ChatContextCodec.read(bytes));
    }

    @Test
    void 老行读进来再写出去不丢东西() throws Exception {
        // 生产里老行就是这么换新的：这一轮读老字节，跑完整体覆盖成裸 JSON
        List<Message> reWritten = ChatContextCodec.read(ChatContextCodec.write(ChatContextCodec.read(golden())));

        assertSameMessages(fixture(), reWritten);
    }

    private static byte[] golden() throws Exception {
        try (InputStream in = ChatContextCodecTest.class.getResourceAsStream("/chat/chat-context-legacy.bin")) {
            assertThat(in).as("golden 文件不在测试类路径上").isNotNull();
            return in.readAllBytes();
        }
    }

    /** 逐字段比：类型、正文、metadata、tool_call/回执配对、图片字节，一样都不许含糊过去 */
    private static void assertSameMessages(List<Message> expected, List<Message> actual) {
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            Message want = expected.get(i);
            Message got = actual.get(i);
            assertThat(got).as("第%d条类型", i).isInstanceOf(want.getClass());
            assertThat(got.getText()).as("第%d条正文", i).isEqualTo(want.getText());
            assertThat(got.getMetadata()).as("第%d条metadata", i).isEqualTo(want.getMetadata());
            if (want instanceof AssistantMessage a) {
                assertThat(((AssistantMessage) got).getToolCalls()).as("第%d条toolCalls", i)
                        .isEqualTo(a.getToolCalls());
            }
            if (want instanceof ToolResponseMessage t) {
                assertThat(((ToolResponseMessage) got).getResponses()).as("第%d条responses", i)
                        .isEqualTo(t.getResponses());
            }
            if (want instanceof UserMessage u) {
                List<Media> wantMedia = u.getMedia();
                List<Media> gotMedia = ((UserMessage) got).getMedia();
                assertThat(gotMedia).as("第%d条media条数", i).hasSize(wantMedia.size());
                for (int j = 0; j < wantMedia.size(); j++) {
                    Media wm = wantMedia.get(j);
                    Media gm = gotMedia.get(j);
                    assertThat(gm.getId()).isEqualTo(wm.getId());
                    assertThat(gm.getName()).isEqualTo(wm.getName());
                    assertThat(gm.getMimeType()).isEqualTo(wm.getMimeType());
                    assertThat(gm.getDataAsByteArray()).isEqualTo(wm.getDataAsByteArray());
                }
            }
        }
    }
}
