package com.mawai.wiibagent.trader.trade;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibagent.mapper.AiTraderWatchMapper;
import com.mawai.wiibagent.trader.wakeup.WakeWindow;
import com.mawai.wiibcommon.entity.AiTraderWatch;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 盯盘条件存取：整组替换把该币旧的标 REPLACED、新的插成 ARMED、广播变更；各类型只落自己那几列；命中是 CAS */
class TraderWatchStoreTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderWatch.class);
    }

    private final AiTraderWatchMapper mapper = mock(AiTraderWatchMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TraderWatchStore store = new TraderWatchStore(mapper, events);

    @Test
    void replaceRetiresOldAndInsertsNew() {
        store.replace(7L, 1, "BTCUSDT", List.of(
                new WatchSpec("TOUCH_ABOVE", 65000.0, null, null, "突破前高看追多"),
                new WatchSpec("CLOSE_BELOW", 62000.0, "1h", null, "区间失守"),
                new WatchSpec("AT_TIME", null, null, "2026-07-27 20:20", "CPI 前看一眼")), 1785171600000L);

        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<AiTraderWatch> rows = ArgumentCaptor.forClass(AiTraderWatch.class);
        verify(mapper, times(3)).insert(rows.capture());
        AiTraderWatch touch = rows.getAllValues().get(0);
        assertThat(touch.getKind()).isEqualTo(AiTraderWatch.TOUCH_ABOVE);
        assertThat(touch.getLevel()).isEqualByComparingTo("65000");
        assertThat(touch.getPeriod()).isNull();
        assertThat(touch.getAtTime()).isNull();
        assertThat(touch.getStatus()).isEqualTo(AiTraderWatch.STATUS_ARMED);
        assertThat(touch.getCreatedWakeTime()).isEqualTo(1785171600000L);
        assertThat(touch.getNote()).isEqualTo("突破前高看追多");
        assertThat(rows.getAllValues().get(1).getPeriod()).isEqualTo("1h");
        AiTraderWatch timed = rows.getAllValues().get(2);
        assertThat(timed.getLevel()).isNull();
        assertThat(timed.getAtTime()).isEqualTo(
                ZonedDateTime.of(2026, 7, 27, 20, 20, 0, 0, WakeWindow.ZONE).toInstant().toEpochMilli());
        verify(events).publishEvent(new WatchesChanged(7L));
    }

    /** 标"已告诉过"：没 id 不碰库，有 id 才更新 */
    @Test
    void markWokenSkipsEmptyIds() {
        store.markWoken(List.of(), 1785171600000L);
        verify(mapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));

        store.markWoken(List.of(31L, 32L), 1785171600000L);
        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    /** 命中是对 ARMED 的 CAS：第二个到的（并发 tick / 刚被替换）拿不到 */
    @Test
    void fireIsCasOnArmed() {
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 0);

        assertThat(store.fire(31L, 1785171600000L, new BigDecimal("63120"))).isTrue();
        assertThat(store.fire(31L, 1785171600000L, new BigDecimal("63120"))).isFalse();
    }

    /** 传空只清掉，不插任何行 */
    @Test
    void emptyListOnlyClears() {
        store.replace(7L, 1, "BTCUSDT", List.of(), 1785171600000L);

        verify(mapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(mapper, never()).insert(any(AiTraderWatch.class));
    }

    /** 复盘窗口查询：布下时刻或命中时刻落在 (from, to] 任一个就算 */
    @Test
    void inWindowMatchesCreatedOrFired() {
        store.inWindow(7L, 1, 1785110400000L, 1785196800000L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<AiTraderWatch>> q = ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectList(q.capture());
        String sql = q.getValue().getSqlSegment();
        assertThat(sql).contains("created_wake_time >").contains("created_wake_time <=")
                .contains("OR").contains("fired_at >").contains("fired_at <=");
        assertThat(q.getValue().getParamNameValuePairs()).containsValue(7L).containsValue(1785110400000L).containsValue(1785196800000L);
    }
}
