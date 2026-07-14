package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;

import java.math.BigDecimal;

/**
 * 用户服务接口
 * 提供用户余额管理，支持乐观锁和冻结机制
 */
public interface UserService extends IService<User> {

    /**
     * 根据LinuxDo ID查找用户
     */
    User findByLinuxDoId(String linuxDoId);

    /**
     * 确保 admin 用户（id=1）存在——仅管理员直登模式下调用，幂等
     */
    void ensureAdminUser();

    /**
     * 获取用户资产概览
     */
    UserDTO getUserPortfolio(Long userId);

    /**
     * 更新用户余额（带乐观锁重试）
     *
     * @param userId 用户ID
     * @param amount 变动金额（正数增加，负数减少）
     */
    void updateBalance(Long userId, BigDecimal amount);

    /**
     * 冻结余额（限价买单时调用）
     * 从可用余额转移到冻结余额
     *
     * @param userId 用户ID
     * @param amount 冻结金额
     */
    void freezeBalance(Long userId, BigDecimal amount);

    /**
     * 解冻余额（订单取消/过期时调用）
     * 从冻结余额转移回可用余额
     *
     * @param userId 用户ID
     * @param amount 解冻金额
     */
    void unfreezeBalance(Long userId, BigDecimal amount);

    /**
     * 扣除冻结余额（限价买单成交时调用）
     * 直接扣除冻结余额，不返还可用余额
     *
     * @param userId 用户ID
     * @param amount 扣除金额
     */
    void deductFrozenBalance(Long userId, BigDecimal amount);
}
