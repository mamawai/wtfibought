package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.PositionDTO;
import com.mawai.wiibcommon.entity.Position;

import java.math.BigDecimal;
import java.util.List;

/**
 * 持仓服务接口
 * 提供持仓管理，支持乐观锁和冻结机制
 */
public interface PositionService extends IService<Position> {

    /**
     * 获取用户指定股票的持仓
     */
    Position findByUserAndStock(Long userId, Long stockId);

    /**
     * 获取用户所有持仓
     */
    List<PositionDTO> getUserPositions(Long userId);

    /**
     * 增加持仓（买入时调用，带乐观锁重试）
     */
    void addPosition(Long userId, Long stockId, int quantity, BigDecimal price, BigDecimal discount);

    /**
     * 减少持仓（卖出时调用，带乐观锁重试）
     */
    void reducePosition(Long userId, Long stockId, int quantity);

    /**
     * 计算用户持仓总市值
     */
    BigDecimal calculateTotalMarketValue(Long userId);

    /**
     * 冻结持仓（限价卖单时调用）
     * 从可用持仓转移到冻结持仓
     *
     * @param userId 用户ID
     * @param stockId 股票ID
     * @param quantity 冻结数量
     */
    void freezePosition(Long userId, Long stockId, int quantity);

    /**
     * 解冻持仓（订单取消/过期时调用）
     * 从冻结持仓转移回可用持仓
     *
     * @param userId 用户ID
     * @param stockId 股票ID
     * @param quantity 解冻数量
     */
    void unfreezePosition(Long userId, Long stockId, int quantity);

    /**
     * 扣除冻结持仓（限价卖单成交时调用）
     * 直接扣除冻结持仓，不返还可用持仓
     *
     * @param userId 用户ID
     * @param stockId 股票ID
     * @param quantity 扣除数量
     */
    void deductFrozenPosition(Long userId, Long stockId, int quantity);
}
