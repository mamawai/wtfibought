package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.entity.Settlement;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资金结算服务
 */
public interface SettlementService extends IService<Settlement> {

    /**
     * 创建待结算记录（卖出时调用）
     */
    void createSettlement(Long userId, Long orderId, BigDecimal amount);

    /**
     * 执行结算（定时任务调用，T+1到账）
     */
    void processSettlements();

    /**
     * 查询用户待结算列表
     */
    List<Settlement> getPendingSettlements(Long userId);

    /**
     * 查询所有待结算列表
     */
    List<Settlement> getAllPendingSettlements();
}
