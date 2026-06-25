package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.OptionPositionDTO;
import com.mawai.wiibcommon.entity.OptionContract;
import com.mawai.wiibcommon.entity.OptionPosition;
import com.mawai.wiibsim.mapper.OptionContractMapper;
import com.mawai.wiibsim.mapper.OptionPositionMapper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.service.OptionPositionService;
import com.mawai.wiibsim.service.OptionPricingService;
import com.mawai.wiibsim.service.StockCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionPositionServiceImpl extends ServiceImpl<OptionPositionMapper, OptionPosition>
        implements OptionPositionService {

    private final OptionContractMapper contractMapper;
    private final OptionPricingService pricingService;
    private final CacheService cacheService;
    private final StockCacheService stockCacheService;

    @Override
    public OptionPosition findByUserAndContract(Long userId, Long contractId) {
        LambdaQueryWrapper<OptionPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OptionPosition::getUserId, userId)
                .eq(OptionPosition::getContractId, contractId);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addPosition(Long userId, Long contractId, int quantity, BigDecimal premium) {
        OptionPosition position = findByUserAndContract(userId, contractId);

        if (position == null) {
            position = new OptionPosition();
            position.setUserId(userId);
            position.setContractId(contractId);
            position.setQuantity(quantity);
            position.setFrozenQuantity(0);
            position.setAvgCost(premium);
            baseMapper.insert(position);
        } else {
            BigDecimal oldTotal = position.getAvgCost().multiply(BigDecimal.valueOf(position.getQuantity()));
            BigDecimal newTotal = premium.multiply(BigDecimal.valueOf(quantity));
            int newQty = position.getQuantity() + quantity;
            BigDecimal newAvgCost = oldTotal.add(newTotal).divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);

            int affected = baseMapper.atomicAddQuantity(position.getId(), quantity, newAvgCost);
            if (affected == 0) {
                throw new RuntimeException("更新持仓失败");
            }
        }
        log.info("增加期权持仓 userId={} contractId={} qty={} premium={}", userId, contractId, quantity, premium);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reducePosition(Long userId, Long contractId, int quantity) {
        OptionPosition position = findByUserAndContract(userId, contractId);
        if (position == null || position.getQuantity() < quantity) {
            throw new RuntimeException("持仓不足");
        }

        int affected = baseMapper.atomicAddQuantity(position.getId(), -quantity, position.getAvgCost());
        if (affected == 0) {
            throw new RuntimeException("更新持仓失败");
        }
        log.info("减少期权持仓 userId={} contractId={} qty={}", userId, contractId, quantity);
    }

    @Override
    public List<OptionPositionDTO> getUserPositions(Long userId) {
        LambdaQueryWrapper<OptionPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OptionPosition::getUserId, userId)
                .gt(OptionPosition::getQuantity, 0);
        List<OptionPosition> positions = baseMapper.selectList(wrapper);

        List<OptionPositionDTO> result = new ArrayList<>();
        for (OptionPosition pos : positions) {
            OptionContract contract = contractMapper.selectById(pos.getContractId());
            if (contract == null) continue;

            Map<String, String> stockStatic = stockCacheService.getStockStatic(contract.getStockId());
            BigDecimal spotPrice = cacheService.getCurrentPrice(contract.getStockId());
            if (spotPrice == null && stockStatic != null) {
                spotPrice = new BigDecimal(stockStatic.getOrDefault("prevClose", "0"));
            }

            BigDecimal premium = pricingService.calculatePremium(
                    contract.getOptionType(), spotPrice, contract.getStrike(),
                    contract.getExpireAt(), contract.getSigma());

            BigDecimal marketValue = premium.multiply(BigDecimal.valueOf(pos.getQuantity()));
            BigDecimal cost = pos.getAvgCost().multiply(BigDecimal.valueOf(pos.getQuantity()));
            BigDecimal pnl = marketValue.subtract(cost);

            OptionPositionDTO dto = new OptionPositionDTO();
            dto.setPositionId(pos.getId());
            dto.setContractId(contract.getId());
            dto.setStockId(contract.getStockId());
            dto.setStockCode(stockStatic != null ? stockStatic.get("code") : "");
            dto.setStockName(stockStatic != null ? stockStatic.get("name") : "");
            dto.setOptionType(contract.getOptionType());
            dto.setStrike(contract.getStrike());
            dto.setExpireAt(contract.getExpireAt());
            dto.setQuantity(pos.getQuantity());
            dto.setAvgCost(pos.getAvgCost());
            dto.setCurrentPremium(premium);
            dto.setMarketValue(marketValue);
            dto.setPnl(pnl);
            dto.setSpotPrice(spotPrice);
            result.add(dto);
        }
        return result;
    }

    @Override
    public List<OptionPosition> getPositionsByContract(Long contractId) {
        LambdaQueryWrapper<OptionPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OptionPosition::getContractId, contractId)
                .gt(OptionPosition::getQuantity, 0);
        return baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearPosition(Long positionId) {
        OptionPosition position = baseMapper.selectById(positionId);
        if (position != null) {
            position.setQuantity(0);
            position.setFrozenQuantity(0);
            baseMapper.updateById(position);
        }
    }
}
