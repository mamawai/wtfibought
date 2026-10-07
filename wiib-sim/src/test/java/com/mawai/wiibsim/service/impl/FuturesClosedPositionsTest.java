package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 已平仓位列表的 closedPnl 是净盈亏：订单流水聚合（各平仓单盈亏 − 开平手续费）再减资金费 */
class FuturesClosedPositionsTest {

    private static final Long UID = 9L;

    private FuturesPositionMapper positionMapper;
    private FuturesOrderMapper orderMapper;
    private FuturesTradingServiceImpl service;

    @BeforeAll
    static void initTableInfoCache() {
        // getClosedPositions 用 LambdaQueryWrapper，脱离 Spring 得手动建 lambda 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesPosition.class);
    }

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        orderMapper = mock(FuturesOrderMapper.class);
        service = new FuturesTradingServiceImpl(
                mock(UserService.class), mock(UserMapper.class), positionMapper, orderMapper,
                new TradingConfig(), mock(FairLockRegistry.class), mock(CacheService.class),
                mock(FuturesPositionIndexService.class), mock(FuturesLeverageBracketRegistry.class),
                mock(CrossMarginService.class), new TradeFilterRegistry(mock(BinanceRestClient.class)),
                new MessageCatalog());
    }

    private static FuturesPosition closed(long id, String lastClosePnl, String funding) {
        FuturesPosition p = new FuturesPosition();
        p.setId(id);
        p.setUserId(UID);
        p.setSymbol("SOLUSDT");
        p.setSide("LONG");
        p.setStatus("CLOSED");
        p.setClosedPnl(lastClosePnl == null ? null : new BigDecimal(lastClosePnl));
        p.setFundingFeeTotal(new BigDecimal(funding));
        return p;
    }

    @Test
    void 分批平仓的净盈亏取订单聚合再减资金费_不是仓位表最后一笔的毛盈亏() {
        // 仓位表只记了最后一笔平仓的 660.30；订单聚合（16.80 + 660.30 − 手续费 98.89）= 578.21
        when(positionMapper.selectList(any())).thenReturn(List.of(closed(1165L, "660.30", "3.20")));
        when(orderMapper.sumRealizedPnlByPositionIds(any()))
                .thenReturn(List.of(Map.<String, Object>of("position_id", 1165L, "amount", new BigDecimal("578.21"))));

        List<FuturesPositionDTO> out = service.getClosedPositions(UID, null, 50);

        assertThat(out).hasSize(1);
        assertThat(out.getFirst().getClosedPnl()).isEqualByComparingTo("575.01");
    }

    @Test
    void 没结算过的仓位净盈亏保持空() {
        // 清零的仓位没有平仓单，closed_pnl 为空
        when(positionMapper.selectList(any())).thenReturn(List.of(closed(7L, null, "0")));
        when(orderMapper.sumRealizedPnlByPositionIds(any()))
                .thenReturn(List.of(Map.<String, Object>of("position_id", 7L, "amount", new BigDecimal("-12.00"))));

        assertThat(service.getClosedPositions(UID, null, 50).getFirst().getClosedPnl()).isNull();
    }

    @Test
    void 订单聚合把强平单算在内() throws NoSuchMethodException {
        // 钉住 SQL 原文：强平单在成交状态列表里
        String sql = String.join(" ", FuturesOrderMapper.class
                .getMethod("sumRealizedPnlByPositionIds", Long[].class)
                .getAnnotation(Select.class).value());

        assertThat(sql).contains("'LIQUIDATED'");
    }
}
