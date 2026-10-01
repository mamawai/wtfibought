package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibagent.jev.JevClient;
import com.mawai.wiibagent.jev.JevClient.Answer;
import com.mawai.wiibagent.jev.JevClient.Question;
import com.mawai.wiibagent.jev.JevPlatformConfig;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.OddsJump;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Raw;
import com.mawai.wiibagent.jev.predictor.PredictionStateWriter.Snapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 整点问六道、突变问两道，逐字、按看的那一边出、顺序固定；回答原样带回，p_jev 按那一边换算；缺题、缺数、形态不对按失败
 */
class PredictionJudgeTest {

    private static final JevPredictionConfig CFG = JevPredictionRunnerTest.CFG;
    private final JevClient client = mock(JevClient.class);
    private final PredictionJudge judge = new PredictionJudge(client,
            new JevPlatformConfig("sk-platform", "https://api.typesafe.ai", "jev-latest"), CFG);
    private static final Snapshot SNAP = new Snapshot(Map.of("market", "..."),
            new Raw(1.0, 0.7, PredictionRulesTest.BOOK, 1L, 800, null, null, null, null, null));
    private static final Snapshot JUMP_SNAP = new Snapshot(Map.of("market", "..."),
            new Raw(1.0, 0.7, PredictionRulesTest.BOOK, 1L, 800,
                    new OddsJump(1_000, 3_000, new BigDecimal("0.40"), new BigDecimal("0.58")), 25.0, 80.0, 12.0, -18.0));

    private static Answer noul(double p) {
        return new Answer("noul", p, null, null, null, null);
    }

    private static Answer pattern(String pick, Map<String, Double> probs) {
        return new Answer("choice", null, pick, null, 0.8, probs);
    }

    /** 六道题都答了：会赢 0.8、单边 0.6、在变弱 0.2、成交同向 0.75、逆过又顺回 0.3、最新一步逆着 0.15 */
    private static Map<String, Answer> fullAnswers() {
        Map<String, Answer> a = new LinkedHashMap<>();
        a.put("win", noul(0.8));
        a.put("pattern", pattern("cascade", Map.of("cascade", 0.6, "chop", 0.3, "neither", 0.1)));
        a.put("push_fading", noul(0.2));
        a.put("flow_confirms", noul(0.75));
        a.put("dip_recovered", noul(0.3));
        a.put("latest_against", noul(0.15));
        return a;
    }

    /** 突变两道都答了：会赢 0.8、值得下单 0.75 */
    private static Map<String, Answer> jumpAnswers() {
        Map<String, Answer> a = new LinkedHashMap<>();
        a.put("win", noul(0.8));
        a.put("buy", noul(0.75));
        return a;
    }

    private void answer(Map<String, Answer> answers) {
        when(client.ask(any(), any(), any(), any(), any())).thenReturn(new JevClient.Response("jev-1.14.0", answers, 820));
    }

    @Test
    void 整点问六道题_题按看的那一边出_回答原样带回() {
        answer(fullAnswers());

        PredictionJudge.Judgment up = judge.judge(SNAP, "UP");

        verify(client).ask(eq("https://api.typesafe.ai"), eq("sk-platform"), eq("jev-latest"), eq(SNAP.state()),
                eq(PredictionQuestions.questions("UP")));
        assertThat(up.side()).isEqualTo("UP");
        assertThat(up.win()).isEqualTo(0.8);
        assertThat(up.latestAgainst()).isEqualTo(0.15);
        assertThat(up.buy()).isNull();
        assertThat(up.answers()).isEqualTo(fullAnswers());
        assertThat(up.model()).isEqualTo("jev-1.14.0");
        assertThat(up.inputTokens()).isEqualTo(820);
        // Jev 的上涨概率：看 UP 就是会赢，看 DOWN 是 1 − 会赢
        assertThat(up.pJev()).isEqualTo(0.8);
        assertThat(judge.judge(SNAP, "DOWN").pJev()).isCloseTo(0.2, within(1e-9));
        verify(client).ask(any(), any(), any(), any(), eq(PredictionQuestions.questions("DOWN")));
    }

    @Test
    void 突变问两道题_回答原样带回() {
        answer(jumpAnswers());

        PredictionJudge.Judgment down = judge.judge(JUMP_SNAP, "DOWN");

        verify(client).ask(any(), any(), any(), any(), eq(PredictionQuestions.jumpQuestions("DOWN", CFG)));
        assertThat(down.win()).isEqualTo(0.8);
        assertThat(down.buy()).isEqualTo(0.75);
        assertThat(down.latestAgainst()).isNull();
        assertThat(down.answers()).isEqualTo(jumpAnswers());
        assertThat(down.pJev()).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void 题目顺序固定_选项和判据按写的顺序() {
        assertThat(PredictionQuestions.jumpQuestions("UP", CFG).keySet()).containsExactly("win", "buy");
        Map<String, Question> q = PredictionQuestions.questions("UP");
        assertThat(q.keySet()).containsExactly("win", "pattern", "push_fading", "flow_confirms", "dip_recovered", "latest_against");
        @SuppressWarnings("unchecked")
        Map<String, String> options = (Map<String, String>) q.get("pattern").criteria();
        assertThat(options.keySet()).containsExactly("cascade", "chop", "neither");
        @SuppressWarnings("unchecked")
        Map<String, String> winCriteria = (Map<String, String>) q.get("win").criteria();
        assertThat(winCriteria.keySet()).containsExactly("true", "false");
        assertThat(q.get("pattern").type()).isEqualTo("choice");
        assertThat(q.get("win").type()).isEqualTo("noul");
    }

    @Test
    void 题目逐字_看UP() {
        Map<String, Question> q = PredictionQuestions.questions("UP");
        assertQuestion(q.get("win"),
                "UP will win this round. UP wins if BTC's average price over the final minute is at or above the opening average. "
                        + "`btc_now` and `lead` say where BTC stands, `story` lists what BTC did in each step since the open, oldest first, "
                        + "`odds_history` lists what the market priced each side at after each step, and `clock` says how much time is left.",
                "UP wins this round.", "DOWN wins this round.");
        assertThat(q.get("pattern").instructions()).isEqualTo("What kind of move is BTC in right now, judging by `story`? "
                + "`story` lists what BTC did in each step since the open, oldest first.");
        assertThat(q.get("pattern").criteria()).isEqualTo(Map.of(
                "cascade", "A one-way cascade: price keeps pushing in one direction with little bounce, and trading pushes the same way.",
                "chop", "Chopping in a range: price swings back and forth around a level, and moves against it get taken back.",
                "neither", "No clear pattern, or price is barely moving."));
        assertQuestion(q.get("push_fading"),
                "UP is losing strength: BTC's latest step is weaker in UP's favour than its earlier steps. "
                        + "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.",
                "In the latest step BTC barely moved or moved against UP, after earlier steps moved clearly in UP's favour.",
                "In the latest step BTC moved in UP's favour at least as strongly as in the earlier steps.");
        assertQuestion(q.get("flow_confirms"),
                "Right now trading on Binance is pushing in UP's favour. The latest step in `story` says who is ahead among "
                        + "Binance takers; buyers favour UP and sellers favour DOWN.",
                "Buyers are ahead among Binance takers in the latest step.",
                "Takers are balanced, or sellers are ahead.");
        assertQuestion(q.get("dip_recovered"),
                "During this round BTC moved against UP in an earlier step and moved in UP's favour again in a later step. "
                        + "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.",
                "An earlier step moved against UP, and a later step moved in UP's favour.",
                "No step moved against UP, or no later step moved back in UP's favour.");
        assertQuestion(q.get("latest_against"),
                "In the latest step BTC moved against UP. "
                        + "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.",
                "The latest step in `story` is a fall, which goes against UP.",
                "The latest step in `story` is a rise or barely moved.");
    }

    @Test
    void 题目逐字_看DOWN是镜像() {
        Map<String, Question> q = PredictionQuestions.questions("DOWN");
        assertQuestion(q.get("win"),
                "DOWN will win this round. DOWN wins if BTC's average price over the final minute is below the opening average. "
                        + "`btc_now` and `lead` say where BTC stands, `story` lists what BTC did in each step since the open, oldest first, "
                        + "`odds_history` lists what the market priced each side at after each step, and `clock` says how much time is left.",
                "DOWN wins this round.", "UP wins this round.");
        assertThat(q.get("pattern")).isEqualTo(PredictionQuestions.questions("UP").get("pattern"));
        assertQuestion(q.get("push_fading"),
                "DOWN is losing strength: BTC's latest step is weaker in DOWN's favour than its earlier steps. "
                        + "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.",
                "In the latest step BTC barely moved or moved against DOWN, after earlier steps moved clearly in DOWN's favour.",
                "In the latest step BTC moved in DOWN's favour at least as strongly as in the earlier steps.");
        assertQuestion(q.get("flow_confirms"),
                "Right now trading on Binance is pushing in DOWN's favour. The latest step in `story` says who is ahead among "
                        + "Binance takers; buyers favour UP and sellers favour DOWN.",
                "Sellers are ahead among Binance takers in the latest step.",
                "Takers are balanced, or buyers are ahead.");
        assertQuestion(q.get("dip_recovered"),
                "During this round BTC moved against DOWN in an earlier step and moved in DOWN's favour again in a later step. "
                        + "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.",
                "An earlier step moved against DOWN, and a later step moved in DOWN's favour.",
                "No step moved against DOWN, or no later step moved back in DOWN's favour.");
        assertQuestion(q.get("latest_against"),
                "In the latest step BTC moved against DOWN. "
                        + "`story` lists what BTC did in each step since the open, oldest first; a rise favours UP and a fall favours DOWN.",
                "The latest step in `story` is a rise, which goes against DOWN.",
                "The latest step in `story` is a fall or barely moved.");
    }

    @Test
    void 突变两道题逐字_会赢题末尾多一句jump_值得下单题写明怎么盯怎么选_不带买卖字眼_看DOWN是镜像() {
        String rest = "and if neither happens nothing is done. If a side gets picked this way, it will win this round. A pick is final "
                + "and cannot be changed; a side already picked is not picked again. `jump` says how far into the round the jump came "
                + "and `clock` how much time is left; `position` says which side is already picked in this round; `binance_now` and "
                + "`chainlink_vs_binance` say what BTC did on Binance and whether the settlement price has caught up; `takers_now` "
                + "says who is ahead among Binance takers.";
        String yes = "A side picked after this jump wins the round.";
        String no = "A side picked after this jump loses the round.";
        Map<String, Question> up = PredictionQuestions.jumpQuestions("UP", CFG);
        assertQuestion(up.get("win"),
                "UP will win this round. UP wins if BTC's average price over the final minute is at or above the opening average. "
                        + "`btc_now` and `lead` say where BTC stands, `story` lists what BTC did in each step since the open, oldest first, "
                        + "`jump` says how the market's prices just moved, and `clock` says how much time is left.",
                "UP wins this round.", "DOWN wins this round.");
        assertQuestion(up.get("buy"),
                "After UP's jump in `jump`, UP's price is watched for the next 15 seconds: "
                        + "if it rises another 10¢ the pick is UP, if it gives back the whole jump the pick is DOWN, " + rest,
                yes, no);

        Map<String, Question> down = PredictionQuestions.jumpQuestions("DOWN", CFG);
        assertQuestion(down.get("win"),
                "DOWN will win this round. DOWN wins if BTC's average price over the final minute is below the opening average. "
                        + "`btc_now` and `lead` say where BTC stands, `story` lists what BTC did in each step since the open, oldest first, "
                        + "`jump` says how the market's prices just moved, and `clock` says how much time is left.",
                "DOWN wins this round.", "UP wins this round.");
        assertQuestion(down.get("buy"),
                "After DOWN's jump in `jump`, DOWN's price is watched for the next 15 seconds: "
                        + "if it rises another 10¢ the pick is DOWN, if it gives back the whole jump the pick is UP, " + rest,
                yes, no);
    }

    @Test
    void 值得下单题里盯多久_又走多少_吐回多少跟着配置走() {
        // 盯 20 秒、又走 8¢、吐回跳幅的一半
        JevPredictionConfig cfg = new JevPredictionConfig(List.of(60), new BigDecimal("0.15"), new BigDecimal("0.30"),
                new BigDecimal("0.50"), 5000, new BigDecimal("5"), new BigDecimal("500"),
                20_000, new BigDecimal("0.08"), new BigDecimal("0.5"), 0.50,
                new BigDecimal("0.85"), new BigDecimal("0.96"), 0.70, 0.10, 0, new BigDecimal("0.03"), 5000);

        assertThat(PredictionQuestions.jumpQuestions("UP", cfg).get("buy").instructions())
                .contains("for the next 20 seconds: if it rises another 8¢ the pick is UP, "
                        + "if it gives back 50% of the jump the pick is DOWN,");
    }

    private static void assertQuestion(Question q, String instructions, String yes, String no) {
        assertThat(q.type()).isEqualTo("noul");
        assertThat(q.instructions()).isEqualTo(instructions);
        assertThat(q.criteria()).isEqualTo(Map.of("true", yes, "false", no));
    }

    @Test
    void 缺题_缺数_形态不对_都按失败抛() {
        Map<String, Answer> missing = fullAnswers();
        missing.remove("latest_against");
        answer(missing);
        assertThatThrownBy(() -> judge.judge(SNAP, "UP")).hasMessageContaining("缺题 latest_against");

        Map<String, Answer> noNumber = fullAnswers();
        noNumber.put("push_fading", new Answer("noul", null, null, null, null, null));
        answer(noNumber);
        assertThatThrownBy(() -> judge.judge(SNAP, "UP")).hasMessageContaining("没给 push_fading");

        Map<String, Answer> noPick = fullAnswers();
        noPick.put("pattern", pattern(null, Map.of("chop", 0.9)));
        answer(noPick);
        assertThatThrownBy(() -> judge.judge(SNAP, "UP")).hasMessageContaining("形态选择不对");

        Map<String, Answer> badPick = fullAnswers();
        badPick.put("pattern", pattern("trend", Map.of("trend", 0.9)));
        answer(badPick);
        assertThatThrownBy(() -> judge.judge(SNAP, "UP")).hasMessageContaining("形态选择不对");

        Map<String, Answer> noPickP = fullAnswers();
        noPickP.put("pattern", pattern("chop", Map.of("cascade", 0.2)));
        answer(noPickP);
        assertThatThrownBy(() -> judge.judge(SNAP, "UP")).hasMessageContaining("形态选择不对");

        // 突变两道也一样
        Map<String, Answer> noBuy = jumpAnswers();
        noBuy.remove("buy");
        answer(noBuy);
        assertThatThrownBy(() -> judge.judge(JUMP_SNAP, "UP")).hasMessageContaining("缺题 buy");

        Map<String, Answer> noNumberBuy = jumpAnswers();
        noNumberBuy.put("buy", new Answer("noul", null, null, null, null, null));
        answer(noNumberBuy);
        assertThatThrownBy(() -> judge.judge(JUMP_SNAP, "UP")).hasMessageContaining("没给 buy");
    }
}
