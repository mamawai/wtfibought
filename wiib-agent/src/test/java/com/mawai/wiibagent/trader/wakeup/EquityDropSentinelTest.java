package com.mawai.wiibagent.trader.wakeup;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.mapper.AiTraderMapper;
import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibquant.external.sim.SimTradeClient;
import com.mawai.wiibquant.market.domain.KlineClosedEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 权益回落哨兵：参照点怎么记、回落到比例叫一次、叫过之后从当时的权益重新记 */
class EquityDropSentinelTest {

    /** 2026-07-28 01:00（新加坡时间）这根 5m 的收盘时刻 */
    private static final long CLOSE = 1785171600000L - 1;
    private static final long FIVE_MIN = 300_000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTrader.class);
    }

    private final AiTraderMapper traderMapper = mock(AiTraderMapper.class);
    private final SimTradeClient simTradeClient = mock(SimTradeClient.class);
    private final List<Object> fired = new ArrayList<>();
    private final EquityDropSentinel sentinel = new EquityDropSentinel(traderMapper, simTradeClient, fired::add);
    /** 库里的那一行：参照点写进去什么，下一次就读到什么 */
    private final AiTrader row = trader(7L, "15");

    {
        sentinel.offload = Runnable::run;
        when(traderMapper.selectList(any())).thenReturn(List.of(row));
        when(traderMapper.update(any(), any())).thenAnswer(call -> {
            LambdaUpdateWrapper<AiTrader> w = call.getArgument(1);
            for (Object v : w.getParamNameValuePairs().values()) {
                if (v instanceof BigDecimal peak) {
                    row.setEquityPeak(peak);
                } else if (v instanceof Long at && !at.equals(row.getId())) {
                    row.setEquityPeakAt(at);
                }
            }
            return 1;
        });
    }

    private static AiTrader trader(long id, String dropPct) {
        AiTrader t = new AiTrader();
        t.setId(id);
        t.setSimUserId(id + 1000);
        t.setStatus(AiTrader.STATUS_RUNNING);
        t.setSymbols("BTCUSDT");
        t.setIntervalCode("1h");
        t.setRoundNo(1);
        t.setEquityDropPct(new BigDecimal(dropPct));
        return t;
    }

    /** 第 n 根 5m 收盘，这时的权益是 equity；墙钟在收盘后 1 秒 */
    private void close(int n, String equity) {
        long closeTime = CLOSE + n * FIVE_MIN;
        sentinel.nowMs = () -> closeTime + 1_000;
        when(simTradeClient.getEquity(row.getSimUserId())).thenReturn(new BigDecimal(equity));
        sentinel.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", closeTime));
    }

    private EquityDropTrigger trigger(int i) {
        TriggerFired f = (TriggerFired) fired.get(i);
        assertThat(f.traderId()).isEqualTo(7L);
        return (EquityDropTrigger) f.trigger();
    }

    /** 头一次查只把参照点记下；之后权益更高，参照点跟着抬 */
    @Test
    void referencePointFollowsEquityUp() {
        close(0, "10000");
        assertThat(row.getEquityPeak()).isEqualByComparingTo("10000");
        assertThat(row.getEquityPeakAt()).isEqualTo(CLOSE + 1_000);

        close(1, "10500");
        close(2, "10400");

        assertThat(row.getEquityPeak()).isEqualByComparingTo("10500");
        assertThat(row.getEquityPeakAt()).isEqualTo(CLOSE + FIVE_MIN + 1_000);
        assertThat(fired).isEmpty();
    }

    /** 回落到比例叫一次，参照点从当时的权益重新记；之后从新的参照点再回落到比例，再叫一次 */
    @Test
    void firesOnceAtThePercentageThenStartsOverFromThatEquity() {
        close(0, "10000");
        close(1, "9000");
        assertThat(fired).isEmpty();

        close(2, "8500");
        assertThat(fired).hasSize(1);
        EquityDropTrigger first = trigger(0);
        assertThat(first.peak()).isEqualByComparingTo("10000");
        assertThat(first.peakAt()).isEqualTo(CLOSE + 1_000);
        assertThat(first.equity()).isEqualByComparingTo("8500");
        assertThat(first.dropPct()).isEqualByComparingTo("15");
        assertThat(first.thresholdPct()).isEqualByComparingTo("15");
        assertThat(first.symbol()).isNull();
        assertThat(row.getEquityPeak()).isEqualByComparingTo("8500");

        // 还在低位，离新的参照点不到比例：不重复叫
        close(3, "8400");
        close(4, "7300");
        assertThat(fired).hasSize(1);

        close(5, "7225");
        assertThat(fired).hasSize(2);
        assertThat(trigger(1).peak()).isEqualByComparingTo("8500");
        assertThat(trigger(1).peakAt()).isEqualTo(CLOSE + 2 * FIVE_MIN + 1_000);
        assertThat(row.getEquityPeak()).isEqualByComparingTo("7225");
    }

    /** 休眠时段里不报，参照点也不重新记；回到时段内权益还在比例以下，照样报 */
    @Test
    void sleepWindowNeitherFiresNorStartsOver() {
        row.setWakeWindow("09:00-18:00");
        close(0, "10000");

        close(1, "8000");
        assertThat(fired).isEmpty();
        assertThat(row.getEquityPeak()).isEqualByComparingTo("10000");

        // 8 小时后是新加坡时间 09:00，时段内
        close(96, "8000");
        assertThat(fired).hasSize(1);
        assertThat(trigger(0).peak()).isEqualByComparingTo("10000");
        assertThat(trigger(0).dropPct()).isEqualByComparingTo("20");
    }

    /** 每个币的 5m 收盘各来一次事件，同一刻只查一遍；不是 5m 的不查 */
    @Test
    void sameCloseIsCheckedOnce() {
        close(0, "10000");
        sentinel.onKlineClosed(new KlineClosedEvent(this, "ETHUSDT", "5m", CLOSE));
        sentinel.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "1h", CLOSE + FIVE_MIN));

        verify(simTradeClient, times(1)).getEquity(row.getSimUserId());
    }

    /** 停机后重放的旧 K 线不查 */
    @Test
    void staleCloseIsNotChecked() {
        sentinel.nowMs = () -> CLOSE + 1 + TraderScheduler.STALE_EVENT_MS + 1;

        sentinel.onKlineClosed(new KlineClosedEvent(this, "BTCUSDT", "5m", CLOSE));

        verify(simTradeClient, never()).getEquity(any());
    }

    /** 一个 trader 的权益查不到，不连累别的 */
    @Test
    void oneFailureDoesNotStopTheOthers() {
        AiTrader broken = trader(8L, "15");
        when(traderMapper.selectList(any())).thenReturn(List.of(broken, row));
        when(simTradeClient.getEquity(broken.getSimUserId())).thenThrow(new IllegalStateException("sim down"));

        close(0, "10000");

        assertThat(row.getEquityPeak()).isEqualByComparingTo("10000");
    }
}
