package com.mawai.wiibagent.learning;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import com.mawai.wiibagent.trader.TradePairing;
import com.mawai.wiibagent.trader.prompt.PlayStatsAssembler;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.entity.AiTraderDecision;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单笔复盘的真跑验收：拿一个真实交易员最近两笔平仓满 24 小时的单子，各跑一遍完整的单笔复盘（真素材、真工具、真模型），
 * 正文、归类行和调过的工具打进日志给人读；断言只守输出契约：有正文、有【归类】段、归类行只写这一笔的仓位编号。
 * 不落库：DEBRIEF 行只拿在手里看，这两笔上线后照常会被复盘。
 * <p>
 * 要连真库（两个新列已加）、sim 和本地 5m K 线；烧的是这个交易员主人的 key（每笔最多 16 次模型调用），默认跳过。
 * 行情工具在本机被 Binance 地域封锁时查不到数，要在服务器上跑。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 WIIB_DEBRIEF_TRADER_ID=交易员id mvn test -pl wiib-agent -am -DskipTests=false \
 *   -Dtest=DebriefRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest(properties = {
        "strategy.runtime.enabled=false",
        "strategy.execution.enabled=false",
        "agent.analysis.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
@EnabledIfEnvironmentVariable(named = "WIIB_DEBRIEF_TRADER_ID", matches = "\\d+")
class DebriefRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(DebriefRealRunTest.class);

    /** 一次看几笔 */
    private static final int TRADES = 2;

    @Autowired
    private DebriefRunner runner;
    @Autowired
    private AiTraderMapper traderMapper;
    @Autowired
    private AiTraderPlanMapper planMapper;
    @Autowired
    private SimTradeClient simTradeClient;
    @Autowired
    private ExperienceOpParser parser;

    @Test
    void 真实单子的单笔复盘交回正文和归类段() {
        AiTrader trader = traderMapper.selectById(Long.parseLong(System.getenv("WIIB_DEBRIEF_TRADER_ID")));
        long now = System.currentTimeMillis();
        Map<Long, AiTraderPlan> planByPos = planMapper.selectList(new LambdaQueryWrapper<AiTraderPlan>()
                        .eq(AiTraderPlan::getTraderId, trader.getId())
                        .eq(AiTraderPlan::getRoundNo, trader.getRoundNo())
                        .isNotNull(AiTraderPlan::getPositionId))
                .stream().collect(Collectors.toMap(AiTraderPlan::getPositionId, p -> p, (a, _) -> a));
        // sim 按平仓时刻倒序给：取平仓满 24 小时里最近的几笔
        List<DebriefRunner.Due> dues = simTradeClient.getClosedPositions(trader.getSimUserId(), PlayStatsAssembler.FETCH_LIMIT)
                .stream()
                .filter(p -> planByPos.containsKey(p.getId()) && DebriefRunner.ready(TradePairing.msOf(p.getUpdatedAt()), now))
                .limit(TRADES)
                .map(p -> new DebriefRunner.Due(p, planByPos.get(p.getId())))
                .toList();
        assertThat(dues).as("这个交易员有平仓满 24 小时的单子").isNotEmpty();

        for (DebriefRunner.Due due : dues) {
            long positionId = due.position().getId();
            AiTraderDecision d = runner.debriefRow(trader, due);
            log.info("[DebriefRealRun] 仓位 {} status={} error={} 模型调用{}次 工具{}次\n工具：{}\n输出：\n{}",
                    positionId, d.getStatus(), d.getError(), d.getModelCalls(), d.getToolCalls(),
                    d.getActionsJson(), d.getReasoning());

            // OK 就是有输出、也认出了【归类】段
            assertThat(d.getStatus()).as("仓位 %s：%s", positionId, d.getError()).isEqualTo(AiTraderDecision.STATUS_OK);
            assertThat(parser.debriefBody(d.getReasoning())).as("仓位 %s 有正文", positionId).isNotBlank();
            assertThat(parser.debriefClassifies(d.getReasoning())).as("仓位 %s 的归类行只写这一笔", positionId)
                    .allSatisfy(c -> assertThat(c.marks()).allMatch(m -> m.positionId() == positionId));
        }
    }
}
