package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mawai.wiibcommon.entity.OptionContract;
import com.mawai.wiibsim.mapper.OptionContractMapper;
import com.mawai.wiibsim.service.OptionContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OptionContractServiceImpl extends ServiceImpl<OptionContractMapper, OptionContract>
        implements OptionContractService {

    private static final BigDecimal STEP_PERCENT = new BigDecimal("0.02");
    private static final int DEFAULT_STEPS = 5;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void generateOptionChain(Long stockId, BigDecimal refPrice, BigDecimal sigma,
                                    LocalDateTime expireAt, int steps) {
        if (steps <= 0) steps = DEFAULT_STEPS;

        BigDecimal stepSize = refPrice.multiply(STEP_PERCENT).setScale(2, RoundingMode.HALF_UP);
        if (stepSize.compareTo(new BigDecimal("0.01")) < 0) {
            stepSize = new BigDecimal("0.01");
        }

        BigDecimal atmStrike = roundToStep(refPrice, stepSize);

        int count = 0;
        for (int i = -steps; i <= steps; i++) {
            BigDecimal strike = atmStrike.add(stepSize.multiply(BigDecimal.valueOf(i)));
            if (strike.compareTo(BigDecimal.ZERO) <= 0) continue;

            strike = strike.setScale(2, RoundingMode.HALF_UP);

            for (String type : new String[]{"CALL", "PUT"}) {
                createContract(stockId, type, strike, expireAt, refPrice, sigma);
                count++;
            }
        }

        log.info("生成期权链 stockId={} refPrice={} steps={} contracts={}", stockId, refPrice, steps, count);
    }


    @Override
    public List<OptionContract> getActiveContracts(Long stockId) {
        LambdaQueryWrapper<OptionContract> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OptionContract::getStockId, stockId)
                .eq(OptionContract::getStatus, "ACTIVE")
                .gt(OptionContract::getExpireAt, LocalDateTime.now())
                .orderByAsc(OptionContract::getExpireAt)
                .orderByAsc(OptionContract::getStrike);
        return baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void settleContract(Long contractId) {
        OptionContract contract = baseMapper.selectById(contractId);
        if (contract == null || !"ACTIVE".equals(contract.getStatus())) {
            return;
        }

        contract.setStatus("SETTLED");
        baseMapper.updateById(contract);
        log.info("合约结算 contractId={}", contractId);
    }

    @Override
    public List<OptionContract> getExpiredContracts() {
        LambdaQueryWrapper<OptionContract> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OptionContract::getStatus, "ACTIVE")
                .le(OptionContract::getExpireAt, LocalDateTime.now());
        return baseMapper.selectList(wrapper);
    }

    private void createContract(Long stockId, String optionType, BigDecimal strike,
                                 LocalDateTime expireAt, BigDecimal refPrice, BigDecimal sigma) {
        OptionContract contract = new OptionContract();
        contract.setStockId(stockId);
        contract.setOptionType(optionType);
        contract.setStrike(strike);
        contract.setExpireAt(expireAt);
        contract.setRefPrice(refPrice);
        contract.setSigma(sigma);
        contract.setStatus("ACTIVE");
        baseMapper.insert(contract);
    }

    private BigDecimal roundToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }
}
