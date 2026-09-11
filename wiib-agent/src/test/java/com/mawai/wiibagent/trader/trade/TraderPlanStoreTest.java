package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibagent.i18n.PromptCatalog;
import com.mawai.wiibagent.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * rebind：限价单在两次唤醒之间成交，计划里的 positionId 还是 null，
 * 下一轮唤醒开头拿在场仓位 id 盖上；同键既无持仓也无挂单的归档。
 */
class TraderPlanStoreTest {

    private static final long BOUNDARY = 1785171600000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderPlanMapper mapper = mock(AiTraderPlanMapper.class);
    private final TraderPlanStore store = new TraderPlanStore(mapper, new PromptCatalog());

    private static AiTraderPlan livePlan(String symbol, String side, Long positionId) {
        AiTraderPlan p = new AiTraderPlan();
        p.setId(21L);
        p.setTraderId(7L);
        p.setRoundNo(1);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setStatus(AiTraderPlan.STATUS_LIVE);
        p.setOpenedWakeTime(BOUNDARY - 3600_000L);
        p.setPositionId(positionId);
        return p;
    }

    /** 限价单成交：LIVE 计划无 id 且同键有在场仓位 → 补上仓位 id 落库，计划保留并进 filled 名单 */
    @Test
    void rebindStampsPositionIdOnUnboundLivePlan() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", null)));

        TraderPlanStore.Rebind c = store.rebind(7L, 1,
                Set.of(TraderPlanStore.key("BTCUSDT", "LONG")),
                Map.of(TraderPlanStore.key("BTCUSDT", "LONG"), 42L), BOUNDARY);

        assertThat(c.live()).hasSize(1);
        assertThat(c.live().get(0).getPositionId()).isEqualTo(42L);
        assertThat(c.filled()).containsExactly(c.live().get(0));
        assertThat(c.closed()).isEmpty();
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
    }

    /** 已有仓位 id 的计划不重写：只补 null，不做刷新，省一次每轮白写；也不算成交事件 */
    @Test
    void boundPlanNotRewritten() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        TraderPlanStore.Rebind c = store.rebind(7L, 1,
                Set.of(TraderPlanStore.key("BTCUSDT", "LONG")),
                Map.of(TraderPlanStore.key("BTCUSDT", "LONG"), 42L), BOUNDARY);

        assertThat(c.live()).hasSize(1);
        assertThat(c.filled()).isEmpty();
        assertThat(c.closed()).isEmpty();
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }

    /** 挂单保活的计划没有仓位可绑：liveKeys 有键、映射无值 → 保留但不动 */
    @Test
    void pendingOrderPlanStaysAliveUnbound() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("ETHUSDT", "SHORT", null)));

        TraderPlanStore.Rebind c = store.rebind(7L, 1,
                Set.of(TraderPlanStore.key("ETHUSDT", "SHORT")), Map.of(), BOUNDARY);

        assertThat(c.live()).hasSize(1);
        assertThat(c.live().get(0).getPositionId()).isNull();
        assertThat(c.filled()).isEmpty();
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }

    /** 既有归档语义回归：同键既无持仓也无挂单 → 归档带了结时刻，从存活列表剔除、进 closed 名单 */
    @Test
    void deadKeyPlanArchivedWithClosedTime() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        TraderPlanStore.Rebind c = store.rebind(7L, 1, Set.of(), Map.of(), BOUNDARY);

        assertThat(c.live()).isEmpty();
        assertThat(c.closed()).hasSize(1);
        assertThat(c.closed().get(0).getPositionId()).isEqualTo(42L);
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getClosedWakeTime()).isEqualTo(BOUNDARY);
        // 归档不抹绑定：论点→结局的精确配对靠它
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
    }
}
