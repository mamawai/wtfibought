package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.entity.OptionContract;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface OptionContractService extends IService<OptionContract> {

    /**
     * 生成期权链（围绕参考价上下各N档）
     *
     * @param stockId  标的ID
     * @param refPrice 参考价（昨收）
     * @param sigma    年化波动率
     * @param expireAt 到期时间
     * @param steps    上下各几档
     */
    void generateOptionChain(Long stockId, BigDecimal refPrice, BigDecimal sigma,
                             LocalDateTime expireAt, int steps);

    /**
     * 获取某只股票的可用期权链
     * @param stockId 标的ID
     * @param expireAt 到期时间（精确到分钟）
     * @return 合约列表
     */
    List<OptionContract> getOptionChain(Long stockId, LocalDateTime expireAt);

    /**
     * 获取某只股票当前可交易的所有期权（未到期）
     * @param stockId 标的ID
     * @return 合约列表
     */
    List<OptionContract> getActiveContracts(Long stockId);

    /**
     * 结算到期合约
     * @param contractId 合约ID
     */
    void settleContract(Long contractId);

    /**
     * 获取所有到期待结算的合约
     * @return 合约列表
     */
    List<OptionContract> getExpiredContracts();
}
