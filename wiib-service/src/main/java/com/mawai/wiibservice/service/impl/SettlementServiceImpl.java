package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.Settlement;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.mapper.SettlementMapper;
import com.mawai.wiibservice.service.MarginAccountService;
import com.mawai.wiibservice.service.SettlementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl extends ServiceImpl<SettlementMapper, Settlement> implements SettlementService {

    private final MarginAccountService marginAccountService;

    @Override
    public void createSettlement(Long userId, Long orderId, BigDecimal amount) {
        Settlement settlement = new Settlement();
        settlement.setUserId(userId);
        settlement.setOrderId(orderId);
        settlement.setAmount(amount);
        settlement.setSettleTime(nextTradingDay(LocalDateTime.now()));
        settlement.setStatus("PENDING");
        baseMapper.insert(settlement);
        log.info("创建待结算 userId={} orderId={} amount={} settleTime={}",
                userId, orderId, amount, settlement.getSettleTime());
    }

    @Override
    public void processSettlements() {
        LocalDateTime now = LocalDateTime.now();
        // 先查询最早的待结算记录
        LambdaQueryWrapper<Settlement> checkWrapper = new LambdaQueryWrapper<>();
        checkWrapper.eq(Settlement::getStatus, "PENDING")
                .orderByAsc(Settlement::getSettleTime)
                .last("LIMIT 1");

        Settlement earliest = baseMapper.selectOne(checkWrapper);

        // 如果没有待结算，或最早的还没到时间，跳过
        if (earliest == null) {
            log.info("无待结算记录");
            return;
        }

        if (earliest.getSettleTime().isAfter(now)) {
            log.info("最早待结算时间为 {}，尚未到期", earliest.getSettleTime());
            return;
        }

        // 有到期的，执行批量查询和结算
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Settlement::getStatus, "PENDING")
                .le(Settlement::getSettleTime, now);

        List<Settlement> pendingList = baseMapper.selectList(wrapper);

        for (Settlement s : pendingList) {
            try {
                SpringUtils.getAopProxy(this).processOneSettlement(s);
            } catch (Exception e) {
                log.error("结算失败 settlementId={}", s.getId(), e);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected void processOneSettlement(Settlement s) {
        LambdaUpdateWrapper<Settlement> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Settlement::getId, s.getId())
                .eq(Settlement::getStatus, "PENDING")
                .set(Settlement::getStatus, "SETTLED");

        int affected = baseMapper.update(null, updateWrapper);
        if (affected == 0) {
            return;
        }

        marginAccountService.applyCashInflow(s.getUserId(), s.getAmount(), "SETTLEMENT");

        log.info("结算完成 settlementId={} userId={} amount={}",
                s.getId(), s.getUserId(), s.getAmount());
    }

    @Override
    public List<Settlement> getPendingSettlements(Long userId) {
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Settlement::getUserId, userId)
                .eq(Settlement::getStatus, "PENDING")
                .orderByAsc(Settlement::getSettleTime);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public List<Settlement> getAllPendingSettlements() {
        LambdaQueryWrapper<Settlement> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Settlement::getStatus, "PENDING");
        return baseMapper.selectList(wrapper);
    }

    private LocalDateTime nextTradingDay(LocalDateTime now) {
        LocalDateTime settleTime = now.plusDays(1);
        DayOfWeek dayOfWeek = settleTime.getDayOfWeek();
        if (dayOfWeek == DayOfWeek.SATURDAY) {
            return settleTime.plusDays(2);
        }
        if (dayOfWeek == DayOfWeek.SUNDAY) {
            return settleTime.plusDays(1);
        }
        return settleTime;
    }
}
