package com.mawai.wiibagent.news;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibagent.news.NewsTranslator.Translated;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 译文输出解析与批切分。两条底线：
 * <ul>
 *   <li>没给译文就留空：绝不写回原文冒充译文，否则取用侧再也分不清"没译成"和"本来就这样"</li>
 *   <li>失败是批级的：某一批挂了只丢那一批，其余照常落库</li>
 * </ul>
 */
class NewsTranslatorTest {

    private final ChatModel model = mock(ChatModel.class);
    private final NewsTranslator translator = new NewsTranslator(new PromptCatalog());

    private static NewsFlash flash(long id, String title) {
        return new NewsFlash(id, title, "<p>正文</p>", "https://x/" + id, "2026-08-12 14:03:00");
    }

    /** 每次调用按顺序吐一个应答，用来验证批切分 */
    private List<String> answerEachCall(String... answers) {
        List<String> prompts = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of(answers));
        when(model.call(any(Prompt.class))).thenAnswer(inv -> {
            prompts.add(((Prompt) inv.getArgument(0)).getContents());
            String text = queue.isEmpty() ? "[]" : queue.removeFirst();
            if ("throw".equals(text)) {
                throw new RuntimeException("上游挂了");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        });
        return prompts;
    }

    @Test
    void 一次调用拿到标题与正文译文() {
        answerEachCall("""
                [{"id":1,"title_en":"Fed holds rates","content_en":"Per CME data"}]""");

        Map<Long, Translated> out = translator.translate(model, List.of(flash(1, "美联储维持利率")));

        assertThat(out).containsEntry(1L, new Translated("Fed holds rates", "Per CME data"));
    }

    @Test
    void 空白译文留空不回填原文() {
        answerEachCall("""
                [{"id":1},{"id":2,"title_en":"  ","content_en":""}]""");

        Map<Long, Translated> out = translator.translate(model, List.of(flash(1, "甲"), flash(2, "乙")));

        assertThat(out.get(1L)).isEqualTo(new Translated(null, null));
        assertThat(out.get(2L)).isEqualTo(new Translated(null, null));
    }

    @Test
    void 提示词整篇英文且带上快讯行() {
        List<String> prompts = answerEachCall("[]");

        translator.translate(model, List.of(flash(1, "美联储维持利率")));

        assertThat(prompts).singleElement().satisfies(p -> assertThat(p)
                .contains("into English")                   // 译文目标写死英文，与用户语言无关
                .contains("id=1 TITLE: 美联储维持利率"));      // 拼接小标签跟提示词语言走
    }

    @Test
    void 正文超长的那条不留正文译文() {
        // 送进去的只是正文头部，模型给的 content_en 也只覆盖到那里：半截译文比中文原文更糟
        String longBody = "<p>" + "行情简报".repeat(300) + "</p>";
        answerEachCall("""
                [{"id":1,"title_en":"Market wrap","content_en":"Market wrap says..."}]""");

        Map<Long, Translated> out = translator.translate(model,
                List.of(new NewsFlash(1L, "行情简报", longBody, "https://x/1", "2026-08-12 14:03:00")));

        assertThat(out).containsEntry(1L, new Translated("Market wrap", null));
    }

    @Test
    void 超过一批的快讯切成多次调用() {
        List<NewsFlash> flashes = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            flashes.add(flash(i, "第" + i + "条"));
        }
        List<String> prompts = answerEachCall(
                """
                [{"id":1,"title_en":"a"},{"id":2},{"id":3},{"id":4},{"id":5}]""",
                """
                [{"id":6,"title_en":"f"},{"id":7}]""");

        Map<Long, Translated> out = translator.translate(model, flashes);

        assertThat(prompts).hasSize(2);          // 5 条一批
        assertThat(out).hasSize(7);
        assertThat(out.get(1L).titleEn()).isEqualTo("a");
        assertThat(out.get(6L).titleEn()).isEqualTo("f");
    }

    @Test
    void 某一批挂了只丢那一批其余照常落库() {
        List<NewsFlash> flashes = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            flashes.add(flash(i, "第" + i + "条"));
        }
        answerEachCall("throw", """
                [{"id":6,"title_en":"f"},{"id":7}]""");

        Map<Long, Translated> out = translator.translate(model, flashes);

        // 前 5 条不在产出里＝本轮别落库（落了就再没有译文机会），下轮重试自愈
        assertThat(out.keySet()).containsExactlyInAnyOrder(6L, 7L);
    }

    @Test
    void 本批里模型漏答的条目按没译成补齐() {
        // 整批解析成功就代表这批过过模型了：缺席＝没译成，照常落库，不留到下轮白烧一次
        answerEachCall("""
                [{"id":1,"title_en":"a"}]""");

        Map<Long, Translated> out = translator.translate(model, List.of(flash(1, "甲"), flash(2, "乙")));

        assertThat(out.get(2L)).isEqualTo(new Translated(null, null));
    }

    @Test
    void JSON外裹着废话也解析得出() {
        // 轻模型常见毛病：明令只输出 JSON 还是要加一句"好的，以下是译文"
        Map<Long, Translated> out = NewsTranslator.parse("""
                好的，以下是译文：
                [{"id":7,"title_en":"Tesla beats"}]
                以上。""");

        assertThat(out.get(7L)).isEqualTo(new Translated("Tesla beats", null));
    }

    @Test
    void 无JSON数组时抛出让这一批跳过() {
        assertThatThrownBy(() -> NewsTranslator.parse("我不知道怎么翻译"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 缺id的条目不炸() {
        Map<Long, Translated> out = NewsTranslator.parse(
                "[{\"title_en\":\"x\"},{\"id\":2,\"title_en\":\"y\"}]");

        assertThat(out).hasSize(1);
        assertThat(out.get(2L).titleEn()).isEqualTo("y");
    }
}
