package com.mawai.wiibcommon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体
 * 使用数据库原子操作保证并发安全
 */
@Data
@TableName("\"user\"")
public class User {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** LinuxDo用户ID，OAuth登录标识 */
    private String linuxDoId;

    /** 用户名 */
    private String username;

    /** 头像URL */
    private String avatar;

    /** BCrypt密码哈希（定长60，OAuth用户为空） */
    private String passwordHash;

    /** 注册用的邀请码ID（可追溯，OAuth用户为空） */
    private Long inviteCodeId;

    /** 余额钱包（交易：现货/B股/合约/杠杆，也是全仓保证金池） */
    private BigDecimal balance;

    /** 冻结余额（限价买单冻结的资金，属余额钱包） */
    private BigDecimal frozenBalance;

    /** 游戏钱包（Mines/扑克/21点/预测市场，与全仓风险隔离） */
    private BigDecimal gameBalance;

    /** 杠杆借款本金 */
    private BigDecimal marginLoanPrincipal;

    /** 杠杆应计利息（未支付） */
    private BigDecimal marginInterestAccrued;

    /** 杠杆计息上次日期（用于补记） */
    private LocalDate marginInterestLastDate;

    /** 是否破产（爆仓后禁用交易） */
    private Boolean isBankrupt;

    /** 破产次数 */
    private Integer bankruptCount;

    /** 爆仓时间 */
    private LocalDateTime bankruptAt;

    /** 恢复日期（交易日09:00恢复） */
    private LocalDate bankruptResetDate;

    /** 禁言到期时间，NULL 或已过期=未禁言。到期自动解禁；重置账户刻意不清它 */
    private LocalDateTime mutedUntil;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 获取总资金（可用 + 冻结）
     */
    public BigDecimal getTotalBalance() {
        BigDecimal frozen = frozenBalance != null ? frozenBalance : BigDecimal.ZERO;
        return balance.add(frozen);
    }
}
