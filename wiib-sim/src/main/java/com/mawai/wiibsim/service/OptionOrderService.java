package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.OptionOrderDTO;
import com.mawai.wiibcommon.dto.OptionOrderResultDTO;
import com.mawai.wiibcommon.entity.OptionOrder;

public interface OptionOrderService extends IService<OptionOrder> {

    /**
     * 买入开仓（BTO）
     * @param userId 用户ID
     * @param contractId 合约ID
     * @param quantity 数量
     * @return 订单信息
     */
    OptionOrderResultDTO buyToOpen(Long userId, Long contractId, int quantity);

    /**
     * 卖出平仓（STC）
     * @param userId 用户ID
     * @param contractId 合约ID
     * @param quantity 数量
     * @return 订单信息
     */
    OptionOrderResultDTO sellToClose(Long userId, Long contractId, int quantity);

    /**
     * 查询用户期权订单
     * @param userId 用户ID
     * @param status 状态筛选
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 订单列表
     */
    IPage<OptionOrderDTO> getUserOrders(Long userId, String status, int pageNum, int pageSize);

    /**
     * 处理到期结算
     */
    void processExpirySettlement();
}
