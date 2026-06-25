package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.OptionPositionDTO;
import com.mawai.wiibcommon.entity.OptionPosition;

import java.math.BigDecimal;
import java.util.List;

public interface OptionPositionService extends IService<OptionPosition> {

    /**
     * 查找用户某合约的持仓
     */
    OptionPosition findByUserAndContract(Long userId, Long contractId);

    /**
     * 增加持仓
     * @param userId 用户ID
     * @param contractId 合约ID
     * @param quantity 数量
     * @param premium 权利金（成本）
     */
    void addPosition(Long userId, Long contractId, int quantity, BigDecimal premium);

    /**
     * 减少持仓
     * @param userId 用户ID
     * @param contractId 合约ID
     * @param quantity 数量
     */
    void reducePosition(Long userId, Long contractId, int quantity);

    /**
     * 获取用户所有期权持仓
     * @param userId 用户ID
     * @return 持仓列表（含合约详情）
     */
    List<OptionPositionDTO> getUserPositions(Long userId);

    /**
     * 获取某合约的所有持仓（用于到期结算）
     * @param contractId 合约ID
     * @return 持仓列表
     */
    List<OptionPosition> getPositionsByContract(Long contractId);

    /**
     * 清空持仓（到期结算后）
     * @param positionId 持仓ID
     */
    void clearPosition(Long positionId);
}
