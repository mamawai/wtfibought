package com.mawai.wiibservice.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.entity.ForceOrder;
import com.mawai.wiibservice.mapper.ForceOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ForceOrderService {

    private final ForceOrderMapper forceOrderMapper;

    public void handleForceOrder(String symbol, String side, BigDecimal price,
                                  BigDecimal avgPrice, BigDecimal qty, String status, long tradeTimeMs) {
        ForceOrder order = new ForceOrder();
        order.setSymbol(symbol);
        order.setSide(side);
        order.setPrice(price);
        order.setAvgPrice(avgPrice);
        order.setQuantity(qty);
        order.setAmount(avgPrice.multiply(qty).setScale(2, RoundingMode.HALF_UP));
        order.setStatus(status);
        order.setTradeTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(tradeTimeMs), ZoneId.systemDefault()));
        forceOrderMapper.insert(order);

        log.info("[ForceOrder] {} {} qty={} avgPrice={} amount={}", symbol, side, qty, avgPrice, order.getAmount());
    }

    public List<ForceOrder> getRecent(String symbol, int minutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(minutes);
        return forceOrderMapper.selectList(new LambdaQueryWrapper<ForceOrder>()
                .eq(ForceOrder::getSymbol, symbol)
                .gt(ForceOrder::getTradeTime, cutoff)
                .orderByDesc(ForceOrder::getTradeTime));
    }

    public List<ForceOrder> getLatest(String symbol, int limit) {
        return forceOrderMapper.selectList(new LambdaQueryWrapper<ForceOrder>()
                .eq(symbol != null, ForceOrder::getSymbol, symbol)
                .orderByDesc(ForceOrder::getTradeTime)
                .last("LIMIT " + limit));
    }

    public IPage<ForceOrder> getPage(String symbol, int pageNum, int pageSize) {
        Page<ForceOrder> page = Page.of(pageNum, pageSize);
        return forceOrderMapper.selectPage(page, new LambdaQueryWrapper<ForceOrder>()
                .eq(symbol != null && !symbol.isBlank(), ForceOrder::getSymbol, symbol)
                .orderByDesc(ForceOrder::getTradeTime));
    }
}
