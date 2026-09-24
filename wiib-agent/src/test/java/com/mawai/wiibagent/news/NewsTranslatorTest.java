package com.mawai.wiibagent.news;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.news.NewsTranslator.Translated;
import com.mawai.wiibagent.runtime.AiAgentRuntime.NamedModel;
import com.mawai.wiibquant.mapper.NewsEventMapper.Untranslated;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 译文输出解析、批切分与主备切换。三条底线：
 * <ul>
 *   <li>没给译文就留空：绝不写回原文冒充译文，否则取用侧再也分不清"没译成"和"本来就这样"</li>
 *   <li>失败是批级的：某一批挂了只丢那一批，其余照常回填</li>
 *   <li>主位抛错才换备用，主位好着备用一次都不调</li>
 * </ul>
 */
class NewsTranslatorTest {

    private final ChatModel model = mock(ChatModel.class);
    private final ChatModel backup = mock(ChatModel.class);
    private final NewsTranslator translator = new NewsTranslator(new PromptCatalog());

    private static Untranslated row(long id, String title) {
        return row(id, title, "正文");
    }

    private static Untranslated row(long id, String title, String content) {
        Untranslated r = new Untranslated();
        r.setSourceId(id);
        r.setTitle(title);
        r.setContent(content);
        return r;
    }

    private List<NamedModel> onlyMain() {
        return List.of(new NamedModel("light", model));
    }

    private List<NamedModel> mainThenBackup() {
        return List.of(new NamedModel("light", model), new NamedModel("backup", backup));
    }

    /** 每次调用按顺序吐一个应答，用来验证批切分；"throw" 表示这次抛错 */
    private static List<String> answerEachCall(ChatModel target, String... answers) {
        List<String> prompts = new ArrayList<>();
        List<String> queue = new ArrayList<>(List.of(answers));
        when(target.call(any(Prompt.class))).thenAnswer(inv -> {
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
        answerEachCall(model, """
                [{"id":1,"title_en":"Fed holds rates","content_en":"Per CME data"}]""");

        Map<Long, Translated> out = translator.translate(onlyMain(), List.of(row(1, "美联储维持利率")));

        assertThat(out).containsEntry(1L, new Translated("Fed holds rates", "Per CME data", "light"));
    }

    @Test
    void 空白译文留空不回填原文() {
        answerEachCall(model, """
                [{"id":1},{"id":2,"title_en":"  ","content_en":""}]""");

        Map<Long, Translated> out = translator.translate(onlyMain(), List.of(row(1, "甲"), row(2, "乙")));

        assertThat(out.get(1L)).isEqualTo(new Translated(null, null, "light"));
        assertThat(out.get(2L)).isEqualTo(new Translated(null, null, "light"));
    }

    @Test
    void 提示词整篇英文且带上快讯行() {
        List<String> prompts = answerEachCall(model, "[]");

        translator.translate(onlyMain(), List.of(row(1, "美联储维持利率")));

        assertThat(prompts).singleElement().satisfies(p -> assertThat(p)
                .contains("into English")                   // 译文目标写死英文，与用户语言无关
                .contains("id=1 TITLE: 美联储维持利率"));      // 拼接小标签跟提示词语言走
    }

    @Test
    void 正文超长的那条不留正文译文() {
        // 送进去的只是正文头部，模型给的 content_en 也只覆盖到那里：半截译文比中文原文更糟
        answerEachCall(model, """
                [{"id":1,"title_en":"Market wrap","content_en":"Market wrap says..."}]""");

        Map<Long, Translated> out = translator.translate(onlyMain(),
                List.of(row(1, "行情简报", "行情简报".repeat(300))));

        assertThat(out).containsEntry(1L, new Translated("Market wrap", null, "light"));
    }

    @Test
    void 超过一批的快讯切成多次调用() {
        List<Untranslated> rows = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            rows.add(row(i, "第" + i + "条"));
        }
        List<String> prompts = answerEachCall(model,
                """
                [{"id":1,"title_en":"a"},{"id":2},{"id":3},{"id":4},{"id":5}]""",
                """
                [{"id":6,"title_en":"f"},{"id":7}]""");

        Map<Long, Translated> out = translator.translate(onlyMain(), rows);

        assertThat(prompts).hasSize(2);          // 5 条一批
        assertThat(out).hasSize(7);
        assertThat(out.get(1L).titleEn()).isEqualTo("a");
        assertThat(out.get(6L).titleEn()).isEqualTo("f");
    }

    @Test
    void 某一批挂了只丢那一批其余照常回填() {
        List<Untranslated> rows = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            rows.add(row(i, "第" + i + "条"));
        }
        answerEachCall(model, "throw", """
                [{"id":6,"title_en":"f"},{"id":7}]""");

        Map<Long, Translated> out = translator.translate(onlyMain(), rows);

        // 前 5 条不在产出里＝留着待译，下轮再试
        assertThat(out.keySet()).containsExactlyInAnyOrder(6L, 7L);
    }

    @Test
    void 主位挂了换备用且记备用模型名() {
        answerEachCall(model, "throw");
        answerEachCall(backup, """
                [{"id":1,"title_en":"Fed holds rates","content_en":"Per CME data"}]""");

        Map<Long, Translated> out = translator.translate(mainThenBackup(), List.of(row(1, "美联储维持利率")));

        assertThat(out).containsEntry(1L, new Translated("Fed holds rates", "Per CME data", "backup"));
    }

    @Test
    void 主位好着备用一次都不调() {
        answerEachCall(model, """
                [{"id":1,"title_en":"a"}]""");

        translator.translate(mainThenBackup(), List.of(row(1, "甲")));

        verify(backup, never()).call(any(Prompt.class));
    }

    @Test
    void 主位输出不成JSON也换备用() {
        answerEachCall(model, "我不知道怎么翻译");
        answerEachCall(backup, """
                [{"id":1,"title_en":"a"}]""");

        Map<Long, Translated> out = translator.translate(mainThenBackup(), List.of(row(1, "甲")));

        assertThat(out.get(1L)).isEqualTo(new Translated("a", null, "backup"));
    }

    @Test
    void 主备全挂这批留着待译() {
        answerEachCall(model, "throw");
        answerEachCall(backup, "throw");

        assertThat(translator.translate(mainThenBackup(), List.of(row(1, "甲")))).isEmpty();
    }

    @Test
    void 本批里模型漏答的条目按没译成补齐() {
        // 整批解析成功就代表这批过过模型了：缺席＝没译成，照常回填，不留到下轮白烧一次
        answerEachCall(model, """
                [{"id":1,"title_en":"a"}]""");

        Map<Long, Translated> out = translator.translate(onlyMain(), List.of(row(1, "甲"), row(2, "乙")));

        assertThat(out.get(2L)).isEqualTo(new Translated(null, null, "light"));
    }

    @Test
    void JSON外裹着废话也解析得出() {
        // 轻模型常见毛病：明令只输出 JSON 还是要加一句"好的，以下是译文"
        Map<Long, Translated> out = NewsTranslator.parse("""
                好的，以下是译文：
                [{"id":7,"title_en":"Tesla beats"}]
                以上。""", "light");

        assertThat(out.get(7L)).isEqualTo(new Translated("Tesla beats", null, "light"));
    }

    @Test
    void 无JSON数组时抛出让这一批跳过() {
        assertThatThrownBy(() -> NewsTranslator.parse("我不知道怎么翻译", "light"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 缺id的条目不炸() {
        Map<Long, Translated> out = NewsTranslator.parse(
                "[{\"title_en\":\"x\"},{\"id\":2,\"title_en\":\"y\"}]", "light");

        assertThat(out).hasSize(1);
        assertThat(out.get(2L).titleEn()).isEqualTo("y");
    }
}
