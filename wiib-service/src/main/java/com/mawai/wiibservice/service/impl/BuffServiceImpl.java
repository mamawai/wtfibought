package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.BuffStatusDTO;
import com.mawai.wiibcommon.dto.UserBuffDTO;
import com.mawai.wiibcommon.entity.Stock;
import com.mawai.wiibcommon.entity.UserBuff;
import com.mawai.wiibcommon.enums.BuffType;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.BuffDrawUtil;
import com.mawai.wiibcommon.util.SpringUtils;
import com.mawai.wiibservice.mapper.UserBuffMapper;
import com.mawai.wiibservice.service.*;
import com.mawai.wiibservice.util.RedisLockUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BuffServiceImpl extends ServiceImpl<UserBuffMapper, UserBuff> implements BuffService {

    private final UserService userService;
    private final StockService stockService;
    private final PositionService positionService;
    private final CacheService cacheService;
    private final RedisLockUtil redisLockUtil;

    @Override
    public BuffStatusDTO getStatus(Long userId) {
        LocalDate today = LocalDate.now();
        String cacheKey = "buff:status:" + userId + ":" + today;

        // 先查缓存
        BuffStatusDTO cached = cacheService.getObject(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 缓存未命中，查DB
        UserBuff todayBuff = baseMapper.selectOne(new LambdaQueryWrapper<UserBuff>()
                .eq(UserBuff::getUserId, userId)
                .eq(UserBuff::getDrawDate, today));

        BuffStatusDTO dto = new BuffStatusDTO();
        dto.setCanDraw(todayBuff == null);
        dto.setTodayBuff(todayBuff != null ? toDTO(todayBuff) : null);

        // 存入缓存，1小时过期
        cacheService.setObject(cacheKey, dto, 1, TimeUnit.HOURS);

        return dto;
    }

    @Override
    public UserBuffDTO draw(Long userId) {
        return redisLockUtil.executeWithLock("buff:draw:" + userId, 10, 3000,
                () -> SpringUtils.getAopProxy(this).doDrawTransactional(userId));
    }

    @Transactional(rollbackFor = Exception.class)
    protected UserBuffDTO doDrawTransactional(Long userId) {
        LocalDate today = LocalDate.now();

        // 检查今日是否已抽
        UserBuff existing = baseMapper.selectOne(new LambdaQueryWrapper<UserBuff>()
                .eq(UserBuff::getUserId, userId)
                .eq(UserBuff::getDrawDate, today));
        if (existing != null) {
            throw new BizException(ErrorCode.BUFF_ALREADY_DRAWN);
        }

        BuffType buffType = BuffDrawUtil.drawBuff();

        String extraData = null;
        switch (buffType.getCategory()) {
            case CASH -> {
                int amount = (int) buffType.getValue();
                userService.updateBalance(userId, BigDecimal.valueOf(amount));
                log.info("用户{}抽中红包{}元", userId, amount);
            }
            case STOCK -> {
                List<Stock> stocks = stockService.list();
                Stock stock = stocks.get(ThreadLocalRandom.current().nextInt(stocks.size()));
                int qty = (int) buffType.getValue();
                positionService.addPosition(userId, stock.getId(), qty, BigDecimal.ZERO, BigDecimal.ZERO);
                extraData = "{\"stockCode\":\"" + stock.getCode() + "\",\"stockName\":\"" + stock.getName() + "\",\"quantity\":" + qty + "}";
                log.info("用户{}抽中股票{}{}股", userId, stock.getCode(), qty);
            }
            default -> {}
        }

        // 保存
        UserBuff buff = new UserBuff();
        buff.setUserId(userId);
        buff.setBuffType(buffType.name());
        buff.setBuffName(buffType.getDisplayName());
        buff.setRarity(buffType.getRarity().name());
        buff.setExtraData(extraData);
        buff.setDrawDate(today);
        buff.setExpireAt(today.plusDays(1).atStartOfDay());
        buff.setIsUsed(buffType.getCategory() != BuffType.Category.DISCOUNT);
        baseMapper.insert(buff);

        // 更新缓存
        String cacheKey = "buff:status:" + userId + ":" + today;
        BuffStatusDTO dto = new BuffStatusDTO();
        dto.setCanDraw(false);
        dto.setTodayBuff(toDTO(buff));
        cacheService.setObject(cacheKey, dto, 1, TimeUnit.HOURS);

        log.info("用户{}抽奖结果: {} ({})", userId, buffType.getDisplayName(), buffType.getRarity().name());
        return toDTO(buff);
    }

    @Override
    public BigDecimal getDiscountRate(Long userId, Long buffId) {
        UserBuff buff = baseMapper.selectById(buffId);
        if (buff == null || buff.getIsUsed() || buff.getExpireAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        if (userId != null && !userId.equals(buff.getUserId())) {
            return null;
        }
        try {
            BuffType buffType = BuffType.valueOf(buff.getBuffType());
            if (buffType.getCategory() == BuffType.Category.DISCOUNT) {
                return BigDecimal.valueOf(buffType.getValue());
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    @Override
    public void markUsed(Long buffId) {
        UserBuff buff = baseMapper.selectById(buffId);
        int affected = baseMapper.markUsed(buffId);
        if (affected > 0) {
            log.info("Buff{}已使用", buffId);
            if (buff != null) {
                cacheService.delete("buff:status:" + buff.getUserId() + ":" + buff.getDrawDate());
            }
        }
    }

    private UserBuffDTO toDTO(UserBuff buff) {
        UserBuffDTO dto = new UserBuffDTO();
        dto.setId(buff.getId());
        dto.setBuffType(buff.getBuffType());
        dto.setBuffName(buff.getBuffName());
        dto.setRarity(buff.getRarity());
        dto.setExtraData(buff.getExtraData());
        dto.setExpireAt(buff.getExpireAt());
        dto.setIsUsed(buff.getIsUsed());
        return dto;
    }
}
