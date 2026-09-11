package com.mawai.wiibagent.news;

import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibquant.market.domain.news.NewsFlash;
import com.mawai.wiibagent.news.NewsTagger.Tagged;
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
 * 打标输出解析 + 同批产出译文。三条底线：
 * <ul>
 *   <li>词表是封闭集：词表外的产出必须静默丢弃——图标挂错 K 线比不挂更误导</li>
 *   <li>没给译文就留空：绝不写回原文冒充译文，否则取用侧再也分不清"没译成"和"本来就这样"</li>
 *   <li>译文缺席不许连累打标：模型只给了 tags，标签照样落库</li>
 * </ul>
 */
class NewsTaggerTest {

    private static final List<String> VOCAB = List.of("OIL", "GOLD", "BTC", "COIN", "MSTR", "TSLA", "NVDA");

    private final ChatModel model = mock(ChatModel.class);
    private final NewsTagger tagger = new NewsTagger(new PromptCatalog());

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

    // ==================== 同批产出译文 ====================

    @Test
    void 一次调用同时拿到标签与译文() {
        answerEachCall("""
                [{"id":1,"tags":["BTC"],"title_en":"Fed holds rates","content_en":"Per CME data"}]""");

        Map<Long, Tagged> out = tagger.tag(model, List.of(flash(1, "美联储维持利率")), VOCAB);

        assertThat(out).containsEntry(1L, new Tagged("BTC", "Fed holds rates", "Per CME data"));
    }

    @Test
    void 模型没给译文时译文留空而打标结果不丢() {
        answerEachCall("""
                [{"id":1,"tags":["BTC"]},{"id":2,"tags":["GOLD"],"title_en":"  ","content_en":""}]""");

        Map<Long, Tagged> out = tagger.tag(model, List.of(flash(1, "甲"), flash(2, "乙")), VOCAB);

        // 标签照常落库；译文两条都是 null——空白译文＝没译成，不许回填中文原文
        assertThat(out.get(1L)).isEqualTo(new Tagged("BTC", null, null));
        assertThat(out.get(2L)).isEqualTo(new Tagged("GOLD", null, null));
    }

    @Test
    void 提示词整篇英文且带上词表与快讯行() {
        List<String> prompts = answerEachCall("[]");

        tagger.tag(model, List.of(flash(1, "美联储维持利率")), VOCAB);

        assertThat(prompts).singleElement().satisfies(p -> assertThat(p)
                .contains("translate them into English")     // 译文目标写死英文，与用户语言无关
                .contains("OIL, GOLD, BTC")                  // 封闭词表注进去了
                .contains("id=1 TITLE: 美联储维持利率"));      // 拼接小标签跟提示词语言走
    }

    @Test
    void 正文超长的那条不留正文译文() {
        // 送进去的只是正文头部，模型给的 content_en 也只覆盖到那里：半截译文比中文原文更糟
        String longBody = "<p>" + "行情简报".repeat(300) + "</p>";
        answerEachCall("""
                [{"id":1,"tags":["BTC"],"title_en":"Market wrap","content_en":"Market wrap says..."}]""");

        Map<Long, Tagged> out = tagger.tag(model,
                List.of(new NewsFlash(1L, "行情简报", longBody, "https://x/1", "2026-08-12 14:03:00")), VOCAB);

        assertThat(out).containsEntry(1L, new Tagged("BTC", "Market wrap", null));
    }

    // ==================== 批切分与批级失败 ====================

    @Test
    void 超过一批的快讯切成多次调用() {
        List<NewsFlash> flashes = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            flashes.add(flash(i, "第" + i + "条"));
        }
        List<String> prompts = answerEachCall(
                """
                [{"id":1,"tags":["BTC"]},{"id":2,"tags":[]},{"id":3,"tags":[]},{"id":4,"tags":[]},{"id":5,"tags":[]}]""",
                """
                [{"id":6,"tags":["GOLD"]},{"id":7,"tags":[]}]""");

        Map<Long, Tagged> out = tagger.tag(model, flashes, VOCAB);

        assertThat(prompts).hasSize(2);          // 5 条一批
        assertThat(out).hasSize(7);
        assertThat(out.get(1L).tags()).isEqualTo("BTC");
        assertThat(out.get(6L).tags()).isEqualTo("GOLD");
    }

    @Test
    void 某一批挂了只丢那一批其余照常落库() {
        List<NewsFlash> flashes = new ArrayList<>();
        for (long i = 1; i <= 7; i++) {
            flashes.add(flash(i, "第" + i + "条"));
        }
        answerEachCall("throw", """
                [{"id":6,"tags":["GOLD"]},{"id":7,"tags":[]}]""");

        Map<Long, Tagged> out = tagger.tag(model, flashes, VOCAB);

        // 前 5 条不在产出里＝本轮别落库（落了就再没有打标机会），下轮重试自愈
        assertThat(out.keySet()).containsExactlyInAnyOrder(6L, 7L);
    }

    @Test
    void 本批里模型漏答的条目按空标补齐() {
        // 整批解析成功就代表这批过过打标了：缺席＝判定无标，照常落库，不留到下轮白烧一次
        answerEachCall("""
                [{"id":1,"tags":["BTC"]}]""");

        Map<Long, Tagged> out = tagger.tag(model, List.of(flash(1, "甲"), flash(2, "乙")), VOCAB);

        assertThat(out.get(2L)).isEqualTo(new Tagged("", null, null));
    }

    // ==================== 解析 ====================

    @Test
    void 正常解析多条多标() {
        Map<Long, Tagged> tags = NewsTagger.parse("""
                [{"id":1,"tags":["BTC","GOLD"]},{"id":2,"tags":[]},{"id":3,"tags":["OIL"]}]""", VOCAB);

        assertThat(tags.get(1L).tags()).isEqualTo("BTC,GOLD");
        assertThat(tags.get(2L).tags()).isEmpty();
        assertThat(tags.get(3L).tags()).isEqualTo("OIL");
    }

    @Test
    void 词表外标签静默丢弃() {
        // 模型自作主张给了 ETH 和 SPX：不在词表就不存在，剩下的合法标保留
        Map<Long, Tagged> tags = NewsTagger.parse(
                "[{\"id\":1,\"tags\":[\"ETH\",\"BTC\",\"SPX\"]}]", VOCAB);

        assertThat(tags.get(1L).tags()).isEqualTo("BTC");
    }

    @Test
    void 小写与空白归一后再对词表() {
        Map<Long, Tagged> tags = NewsTagger.parse(
                "[{\"id\":1,\"tags\":[\" btc \",\"gold\"]}]", VOCAB);

        assertThat(tags.get(1L).tags()).isEqualTo("BTC,GOLD");
    }

    @Test
    void JSON外裹着废话也解析得出() {
        // 轻模型常见毛病：明令只输出 JSON 还是要加一句"好的，以下是分类结果"
        Map<Long, Tagged> tags = NewsTagger.parse("""
                好的，以下是分类结果：
                [{"id":7,"tags":["TSLA"],"title_en":"Tesla beats"}]
                以上。""", VOCAB);

        assertThat(tags.get(7L)).isEqualTo(new Tagged("TSLA", "Tesla beats", null));
    }

    @Test
    void 无JSON数组时抛出让这一批跳过() {
        assertThatThrownBy(() -> NewsTagger.parse("我不知道怎么分类", VOCAB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 缺id或重复标签的条目不炸() {
        Map<Long, Tagged> tags = NewsTagger.parse(
                "[{\"tags\":[\"BTC\"]},{\"id\":2,\"tags\":[\"BTC\",\"BTC\"]}]", VOCAB);

        assertThat(tags).hasSize(1);
        assertThat(tags.get(2L).tags()).isEqualTo("BTC");
    }
}
