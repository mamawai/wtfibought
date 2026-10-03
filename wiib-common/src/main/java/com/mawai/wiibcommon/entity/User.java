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

    /** 余额钱包（交易：现货/B股/合约/杠杆，也是全仓保证金池）。
        updateStrategy=NEVER：资金字段禁止随实体更新——否则 updateById 会把读取时刻的旧值
        整行写回，覆盖掉期间发生的交易（AuthServiceImpl 登录更新资料时踩过）。
        资金只能走 UserMapper 的原子方法。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal balance;

    /** 冻结余额（限价买单冻结的资金，属余额钱包） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal frozenBalance;

    /** 游戏钱包（预测市场用，与全仓风险隔离） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal gameBalance;

    /** 杠杆借款本金 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal marginLoanPrincipal;

    /** 杠杆应计利息（未支付） */
    @TableField(updateStrategy = FieldStrategy.NEVER)
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

    /**
     * 是否允许别人查看自己的持仓与交易历史（排行榜点进来的详情页）。默认开启。
     * 关掉只挡详情页，仍照常上排行榜——榜上只有总资产和收益率，那是排名本身的含义。
     */
    private Boolean profilePublic;

    /**
     * agent 提示词语言（{@link com.mawai.wiibcommon.enums.AgentLang} 的 code：zh/en），NULL=跟随中文。
     * 建号时取当时的界面语言，之后只在配置页改；界面语言在前端 localStorage，两边互不影响。
     */
    private String lang;

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
