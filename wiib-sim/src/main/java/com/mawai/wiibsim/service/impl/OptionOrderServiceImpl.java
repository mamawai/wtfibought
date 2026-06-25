package com.mawai.wiibsim.service.impl;
import com.mawai.wiibcommon.cache.CacheService;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.dto.OptionOrderDTO;
import com.mawai.wiibcommon.dto.OptionOrderResultDTO;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.OptionOrderMapper;
import com.mawai.wiibsim.mapper.OptionSettlementMapper;
import com.mawai.wiibsim.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionOrderServiceImpl extends ServiceImpl<OptionOrderMapper, OptionOrder>
        implements OptionOrderService {

    private final UserService userService;
    private final OptionContractService contractService;
    private final OptionPositionService positionService;
    private final OptionPricingService pricingService;
    private final CacheService cacheService;
    private final StockCacheService stockCacheService;
    private final TradingConfig tradingConfig;
    private final OptionSettlementMapper settlementMapper;
    private final PlatformTransactionManager txManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OptionOrderResultDTO buyToOpen(Long userId, Long contractId, int quantity) {
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        OptionContract contract = contractService.getById(contractId);
        if (contract == null || !"ACTIVE".equals(contract.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合约不存在或已失效");
        }
        if (contract.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合约已到期");
        }

        BigDecimal spotPrice = getSpotPrice(contract.getStockId());
        BigDecimal premium = pricingService.calculatePremium(
                contract.getOptionType(), spotPrice, contract.getStrike(),
                contract.getExpireAt(), contract.getSigma());

        BigDecimal amount = premium.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = tradingConfig.calculateCommission(amount);
        BigDecimal totalCost = amount.add(commission);

        if (user.getBalance().compareTo(totalCost) < 0) {
            throw new BizException(ErrorCode.BALANCE_NOT_ENOUGH);
        }

        userService.updateBalance(userId, totalCost.negate());
        positionService.addPosition(userId, contractId, quantity, premium);

        OptionOrder order = new OptionOrder();
        order.setUserId(userId);
        order.setContractId(contractId);
        order.setOrderSide("BTO");
        buildOrder(quantity, spotPrice, premium, amount, commission, order);

        log.info("期权买入开仓 userId={} contractId={} qty={} premium={} amount={} commission={}",
                userId, contractId, quantity, premium, amount, commission);

        return buildOrderResultResponse(order);
    }

    private void buildOrder(int quantity, BigDecimal spotPrice, BigDecimal premium,
                            BigDecimal amount, BigDecimal commission, OptionOrder order) {
        order.setOrderType("MARKET");
        order.setQuantity(quantity);
        order.setFilledPrice(premium);
        order.setFilledAmount(amount);
        order.setCommission(commission);
        order.setUnderlyingPrice(spotPrice);
        order.setStatus("FILLED");
        baseMapper.insert(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OptionOrderResultDTO sellToClose(Long userId, Long contractId, int quantity) {
        if (tradingConfig.isNotInTradingHours()) {
            throw new BizException(ErrorCode.NOT_IN_TRADING_HOURS);
        }

        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (Boolean.TRUE.equals(user.getIsBankrupt())) {
            throw new BizException(ErrorCode.USER_BANKRUPT);
        }

        OptionPosition position = positionService.findByUserAndContract(userId, contractId);
        if (position == null || position.getQuantity() < quantity) {
            throw new BizException(ErrorCode.POSITION_NOT_ENOUGH);
        }

        OptionContract contract = contractService.getById(contractId);
        if (contract == null || !"ACTIVE".equals(contract.getStatus())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合约不存在或已失效");
        }
        if (contract.getExpireAt().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "合约已到期");
        }

        BigDecimal spotPrice = getSpotPrice(contract.getStockId());
        BigDecimal premium = pricingService.calculatePremium(
                contract.getOptionType(), spotPrice, contract.getStrike(),
                contract.getExpireAt(), contract.getSigma());

        BigDecimal amount = premium.multiply(BigDecimal.valueOf(quantity));
        BigDecimal commission = tradingConfig.calculateCommission(amount);
        BigDecimal netAmount = amount.subtract(commission);

        positionService.reducePosition(userId, contractId, quantity);
        userService.updateBalance(userId, netAmount);

        OptionOrder order = new OptionOrder();
        order.setUserId(userId);
        order.setContractId(contractId);
        order.setOrderSide("STC");
        buildOrder(quantity, spotPrice, premium, amount, commission, order);

        log.info("期权卖出平仓 userId={} contractId={} qty={} premium={} amount={} commission={} net={}",
                userId, contractId, quantity, premium, amount, commission, netAmount);

        return buildOrderResultResponse(order);
    }

    @Override
    public IPage<OptionOrderDTO> getUserOrders(Long userId, String status, int pageNum, int pageSize) {
        Page<OptionOrderDTO> page = new Page<>(pageNum, pageSize);
        return baseMapper.selectUserOrdersPage(page, userId, status);
    }

    @Override
    public void processExpirySettlement() {
        List<OptionContract> expiredContracts = contractService.getExpiredContracts();
        if (expiredContracts.isEmpty()) return;

        log.info("开始处理{}个到期合约结算", expiredContracts.size());
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);
        int success = 0, fail = 0;

        for (OptionContract contract : expiredContracts) {
            try {
                txTemplate.executeWithoutResult(status -> settleContract(contract));
                success++;
            } catch (Exception e) {
                fail++;
                log.error("结算合约失败 contractId={}", contract.getId(), e);
            }
        }

        log.info("到期合约结算完成 total={} success={} fail={}", expiredContracts.size(), success, fail);
    }

    private void settleContract(OptionContract contract) {
        BigDecimal settlementPrice = getSpotPrice(contract.getStockId());
        BigDecimal intrinsic = pricingService.calculateIntrinsicValue(
                contract.getOptionType(), settlementPrice, contract.getStrike());

        List<OptionPosition> positions = positionService.getPositionsByContract(contract.getId());

        for (OptionPosition pos : positions) {
            if (pos.getQuantity() <= 0) continue;

            BigDecimal settlementAmount = intrinsic.multiply(BigDecimal.valueOf(pos.getQuantity()));

            if (settlementAmount.compareTo(BigDecimal.ZERO) > 0) {
                userService.updateBalance(pos.getUserId(), settlementAmount);
            }

            OptionSettlement settlement = new OptionSettlement();
            settlement.setUserId(pos.getUserId());
            settlement.setContractId(contract.getId());
            settlement.setPositionId(pos.getId());
            settlement.setQuantity(pos.getQuantity());
            settlement.setStrike(contract.getStrike());
            settlement.setSettlementPrice(settlementPrice);
            settlement.setIntrinsicValue(intrinsic);
            settlement.setSettlementAmount(settlementAmount);
            settlementMapper.insert(settlement);

            positionService.clearPosition(pos.getId());

            log.info("期权结算 userId={} contractId={} qty={} intrinsic={} amount={}",
                    pos.getUserId(), contract.getId(), pos.getQuantity(), intrinsic, settlementAmount);
        }

        contractService.settleContract(contract.getId());
    }

    private BigDecimal getSpotPrice(Long stockId) {
        BigDecimal price = cacheService.getCurrentPrice(stockId);
        if (price == null) {
            Map<String, String> stockStatic = stockCacheService.getStockStatic(stockId);
            if (stockStatic != null) {
                price = new BigDecimal(stockStatic.getOrDefault("prevClose", "0"));
            }
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(), "无法获取标的价格");
        }
        return price;
    }

    private OptionOrderResultDTO buildOrderResultResponse(OptionOrder order) {
        OptionOrderResultDTO dto = new OptionOrderResultDTO();
        dto.setOrderId(order.getId());
        dto.setStatus(order.getStatus());
        dto.setFilledPrice(order.getFilledPrice());
        dto.setFilledAmount(order.getFilledAmount());
        dto.setCommission(order.getCommission());
        return dto;
    }

}
