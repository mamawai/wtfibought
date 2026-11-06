package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.NoBookFillPricer;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesRiskService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.FairLockRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 交易员的币走内部下单的整条链：feed 写的价就是成交价，默认档放行 4 倍全仓；
 * 标记价超过 30 秒没更新开仓被拒，平仓照样按停住的价成交。
 * 例：PEPE 最新价 0.00001 开多 200 万个（名义 20，4 倍占保证金 5）→ 价格 0.0000125 平掉，盈亏 5.00、平仓手续费 0.01。
 */
class TraderSymbolTradeFlowTest {

    private static final Long UID = 7L;
    private static final String SYM = "PEPEUSDT";

    /** 假 Redis：feed 写什么，下单就读到什么 */
    private final Map<String, String> redis = new HashMap<>();
    private final CacheService cache = mock(CacheService.class);
    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);
    private final FuturesOrderMapper orderMapper = mock(FuturesOrderMapper.class);
    private final CrossMarginService crossMargin = mock(CrossMarginService.class);
    private final FuturesTradingServiceImpl trading = trading();

    private FuturesTradingServiceImpl trading() {
        when(cache.getFuturesPrice(anyString())).thenAnswer(inv -> price("market:futures-price:" + inv.getArgument(0)));
        when(cache.getMarkPrice(anyString())).thenAnswer(inv -> price("market:markprice:" + inv.getArgument(0)));
        when(cache.getMarkPriceAt(anyString())).thenAnswer(inv -> {
            String v = redis.get("market:markprice-at:" + inv.getArgument(0));
            return v == null ? null : Long.valueOf(v);
        });
        UserService userService = mock(UserService.class);
        User user = new User();
        user.setId(UID);
        user.setIsBankrupt(false);
        when(userService.getById(UID)).thenReturn(user);
        return new FuturesTradingServiceImpl(
                userService, mock(UserMapper.class), positionMapper, orderMapper,
                new TradingConfig(), mock(FairLockRegistry.class), cache,
                mock(FuturesPositionIndexService.class), new FuturesLeverageBracketRegistry(), crossMargin,
                new TradeFilterRegistry(mock(BinanceRestClient.class)),
                new MessageCatalog(), new LedgerTx(new TransactionTemplate(mock(PlatformTransactionManager.class))),
                mock(FuturesRiskService.class), NoBookFillPricer.create());
    }

    @Test
    void feed的价开仓成交_标记价停了超过30秒开仓被拒_平仓照旧() {
        feedWrites("0.00001000", System.currentTimeMillis());
        trading.doOpenPosition(UID, open());

        ArgumentCaptor<FuturesPosition> opened = ArgumentCaptor.forClass(FuturesPosition.class);
        verify(positionMapper).insert(opened.capture());
        FuturesPosition pos = opened.getValue();
        assertThat(pos.getEntryPrice()).isEqualByComparingTo("0.00001");
        assertThat(pos.getMargin()).isEqualByComparingTo("5");

        // 标记价停在 31 秒前：开仓拒，不碰库
        feedWrites("0.00001250", System.currentTimeMillis() - 31_000);
        assertThatThrownBy(() -> trading.doOpenPosition(UID, open()))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.CRYPTO_PRICE_UNAVAILABLE.getCode());
        verify(positionMapper, times(1)).insert(any(FuturesPosition.class));

        pos.setId(1L);
        when(positionMapper.selectById(1L)).thenReturn(pos);
        when(positionMapper.casClosePosition(anyLong(), anyString(), any(), any())).thenReturn(1);
        // 全仓平仓前要看账户够不够爆：这里远没到
        when(crossMargin.snapshot(UID)).thenReturn(new CrossMarginService.CrossAccount(new BigDecimal("1000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, java.util.List.of(), Map.of()));
        FuturesCloseRequest close = new FuturesCloseRequest();
        close.setPositionId(1L);
        close.setOrderType("MARKET");
        trading.doClosePosition(UID, close);

        verify(positionMapper).casClosePosition(eq(1L), eq("CLOSED"),
                argThat(p -> p.compareTo(new BigDecimal("0.0000125")) == 0),
                argThat(pnl -> pnl.compareTo(new BigDecimal("5.00")) == 0));
        verify(crossMargin).settle(eq(UID), argThat(d -> d.compareTo(new BigDecimal("4.99")) == 0));
        ArgumentCaptor<FuturesOrder> orders = ArgumentCaptor.forClass(FuturesOrder.class);
        verify(orderMapper, times(2)).insert(orders.capture());
        assertThat(orders.getAllValues()).extracting(FuturesOrder::getOrderSide, FuturesOrder::getStatus)
                .containsExactly(tuple("OPEN_LONG", "FILLED"), tuple("CLOSE_LONG", "FILLED"));
    }

    /** feed 写价：最新价、标记价和标记价的写入时刻 */
    private void feedWrites(String price, long atMs) {
        redis.put("market:futures-price:" + SYM, price);
        redis.put("market:markprice:" + SYM, price);
        redis.put("market:markprice-at:" + SYM, String.valueOf(atMs));
    }

    private BigDecimal price(String key) {
        String v = redis.get(key);
        return v == null ? null : new BigDecimal(v);
    }

    private static FuturesOpenRequest open() {
        FuturesOpenRequest open = new FuturesOpenRequest();
        open.setSymbol(SYM);
        open.setSide("LONG");
        open.setMarginMode(FuturesPosition.CROSS);
        open.setLeverage(4);
        open.setQuantity(new BigDecimal("2000000"));
        open.setOrderType("MARKET");
        return open;
    }
}
