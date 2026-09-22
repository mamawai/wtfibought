package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.spring.service.IService;
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
     * 根据用户名查找用户（username 有唯一索引）
     */
    User findByUsername(String username);

    /**
     * 确保 admin 用户（id=1）存在——仅管理员直登模式下调用，幂等
     */
    void ensureAdminUser();

    /**
     * 幂等建/取量化机器人账户（username 唯一）。已存在直接返回、不重复入金；
     * 新建时连带补记初始资金，与建号同一事务。
     */
    User ensureQuantAccount(String username, BigDecimal initialBalance);

    /**
     * 幂等建/取只有游戏钱包的机器人账户（Jev 预测员用）：交易余额 0，初始资金全进 game_balance，
     * 建号与补记初始资金同一事务。已存在直接返回、不重复入金。
     */
    User ensureGameAccount(String username, BigDecimal initialGameBalance);

    /**
     * 补记建号赠送的初始资金。建号走的是 INSERT（balance 是列值，不穿任何 atomic 方法），
     * 记账切面抓不到——不补这一笔，用户开局账本就对不上余额。
     * <p>
     * <b>只给建号与账号重置路径用</b>（OAuth 首登、邀请码注册、ensureQuantAccount、AccountPurgeTx）。
     * 它是凭空造账本行的口子，别拿去当通用入账方法——那会让不变量凭空成立。
     */
    void recordInitialGrant(Long userId, BigDecimal balance);

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

    /**
     * 更新游戏钱包（正数增加，负数减少，游戏模块专用）
     */
    void updateGameBalance(Long userId, BigDecimal amount);

    /**
     * 读游戏钱包余额（非空；用户不存在抛异常）
     */
    BigDecimal getGameBalance(Long userId);

    /**
     * 余额钱包→游戏钱包划转（记流水）
     */
    void transferToGame(Long userId, BigDecimal amount);

    /**
     * 游戏钱包→余额钱包划转（记流水）
     */
    void transferToBalance(Long userId, BigDecimal amount);
}
