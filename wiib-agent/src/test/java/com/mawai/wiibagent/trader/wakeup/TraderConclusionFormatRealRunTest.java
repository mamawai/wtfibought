package com.mawai.wiibagent.trader.wakeup;

import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.trader.DecisionText;
import com.mawai.wiibagent.trader.TraderModelFactory;
import com.mawai.wiibagent.trader.prompt.TraderPromptAssembler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 2（决策总分结构）的真跑验收：新收尾格式（总评 + 按币分段）交给真模型一轮，
 * 看它服从不服从——格式失守率是进阶段 3 的关口。两门语言各跑一次。
 * <p>
 * 不跑完整唤醒回路（那需要 sim 子账户与真 trader 行）：用真实的系统提示词组装 + 真实开场白 +
 * 手造账户状态，单次调用无工具。验收对象只有一个：模型收到新模板后，结论块是否按
 * [SYMBOL] 分段、每个可交易币各一段。
 * <p>
 * 会烧真 token（2 次调用），默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=TraderConclusionFormatRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(properties = {
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class TraderConclusionFormatRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(TraderConclusionFormatRealRunTest.class);

    /** 挑 1 号：真跑要烧的那份 BYOK 配在它名下 */
    private static final long ADMIN_USER_ID = 1L;
    private static final List<String> SYMBOLS = List.of("BTCUSDT", "ETHUSDT");

    @Autowired
    private TraderModelFactory modelFactory;
    @Autowired
    private TraderPromptAssembler promptAssembler;
    @Autowired
    private TraderWakeupRunner runner;
    @Autowired
    private PromptCatalog prompts;

    @Test
    void 新收尾格式两门语言真模型各服从一轮() {
        AiTrader trader = trader();
        long boundary = System.currentTimeMillis() / 3_600_000L * 3_600_000L;
        for (AgentLang lang : AgentLang.values()) {
            String system = promptAssembler.assemble(trader, lang);
            // 账户走开场白的观察包（system 里没有账户 JSON），这里手拼账户段一样的形状
            String observation = "\n" + prompts.get(lang, "trader.wake.accountHeader") + "\n"
                    + accountState(lang, boundary) + "\n";
            String snapshot = prompts.get(lang, "trader.wake.snapshotRow", Map.of(
                    "symbol", "BTCUSDT", "price", "100000", "funding", "0.0001")) + "\n"
                    + prompts.get(lang, "trader.wake.snapshotRow", Map.of(
                    "symbol", "ETHUSDT", "price", "3000", "funding", "0.0001")) + "\n";
            // 无工具的单次调用：真实提示词会让模型先调工具求证，这里明说工具不可用、直接收束——
            // 验收对象只是收尾格式的服从，不是 ReAct 回路本身
            String instruction = runner.routineInstruction(trader, boundary, observation, snapshot, null, lang, "")
                    + (lang == AgentLang.ZH
                    ? "\n（本轮行情工具不可用：直接基于上文注入的账户状态与行情快照收束决策，照常按固定格式收尾。）"
                    : "\n(Market tools are unavailable this round - converge on your decision from the injected account state and snapshot above, and close with the fixed format as usual.)");

            String output = modelFactory.modelFor(trader)
                    .call(new Prompt(List.of(new SystemMessage(system), new UserMessage(instruction))))
                    .getResult().getOutput().getText();
            log.info("[FormatRealRun] {} 输出：\n{}", lang.code(), output);

            String mark = prompts.get(lang, "trader.mark.conclusion");
            assertThat(output).as("%s 结论块标记", lang.code()).contains(mark);
            List<DecisionText.ConclusionSegment> segments =
                    DecisionText.splitSegments(output.substring(output.lastIndexOf(mark) + mark.length()));
            assertThat(segments.stream().map(DecisionText.ConclusionSegment::symbol))
                    .as("%s 每个可交易币各一段", lang.code())
                    .containsExactlyInAnyOrderElementsOf(SYMBOLS);
        }
    }

    /** 不落库的假 trader：只为组装提示词与解析端点（BYOK 走 userId=1 的默认端点） */
    private static AiTrader trader() {
        AiTrader t = new AiTrader();
        t.setId(999_999L);
        t.setUserId(ADMIN_USER_ID);
        t.setSymbols(String.join(",", SYMBOLS));
        t.setIntervalCode("1h");
        return t;
    }

    /** 手造账户状态：一笔带计划的 BTC 多仓 + 空仓的 ETH——逼出"持仓段 + 观望段"两种分段 */
    private String accountState(AgentLang lang, long boundary) {
        FuturesPositionDTO pos = new FuturesPositionDTO();
        pos.setId(42L);
        pos.setSymbol("BTCUSDT");
        pos.setSide("LONG");
        pos.setQuantity(new BigDecimal("0.01"));
        pos.setEntryPrice(new BigDecimal("99000"));
        pos.setUnrealizedPnl(new BigDecimal("10"));
        pos.setCreatedAt(LocalDateTime.now().minusHours(3));

        AiTraderPlan plan = new AiTraderPlan();
        plan.setSymbol("BTCUSDT");
        plan.setSide("LONG");
        plan.setPlayType("BREAKOUT");
        plan.setSignalsUsed("1h 放量突破前高 98800");
        plan.setInvalidationCondition("1h 收盘跌回 98500 下方");
        plan.setEntryPrice(new BigDecimal("99000"));
        plan.setStopLossPrice(new BigDecimal("97800"));
        plan.setOpenedWakeTime(boundary - 3 * 3_600_000L);

        return TraderWakeupRunner.accountStateJson(prompts, lang, new BigDecimal("10000"),
                List.of(pos), List.of(), List.of(plan), boundary);
    }
}
