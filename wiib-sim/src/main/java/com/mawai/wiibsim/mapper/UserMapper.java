package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 幂等创建 admin 用户（强制 id=1），仅管理员直登模式用。
     * IdType.AUTO 下普通 save 会剥掉 id 交给自增，故用原生 SQL 显式写 id；
     * linux_do_id 用固定哨兵占位（不会与真实 LinuxDo 数字 id 冲突）；
     * 其余 NOT NULL 列走 DB 默认值；ON CONFLICT 保证并发/重复调用安全。
     */
    @Insert("INSERT INTO \"user\" (id, linux_do_id, username, balance) " +
            "VALUES (1, 'local-admin', 'admin', #{balance}) " +
            "ON CONFLICT (id) DO NOTHING")
    int insertAdmin(@Param("balance") BigDecimal balance);

    /** 原子更新可用余额，返回影响行数（0表示余额不足） */
    @Update("UPDATE \"user\" SET balance = balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance + #{amount} >= 0")
    int atomicUpdateBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子冻结余额：可用减少，冻结增加 */
    @Update("UPDATE \"user\" SET balance = balance - #{amount}, frozen_balance = frozen_balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance >= #{amount}")
    int atomicFreezeBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子解冻余额：冻结减少，可用增加 */
    @Update("UPDATE \"user\" SET balance = balance + #{amount}, frozen_balance = frozen_balance - #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND frozen_balance >= #{amount}")
    int atomicUnfreezeBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子扣除冻结余额 */
    @Update("UPDATE \"user\" SET frozen_balance = frozen_balance - #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND frozen_balance >= #{amount}")
    int atomicDeductFrozenBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子增加杠杆借款本金 */
    @Update("UPDATE \"user\" SET margin_loan_principal = COALESCE(margin_loan_principal, 0) + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId}")
    int atomicAddMarginLoanPrincipal(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 确保计息上次日期存在（仅在null时设置） */
    @Update("UPDATE \"user\" SET margin_interest_last_date = COALESCE(margin_interest_last_date, #{today}), updated_at = NOW() " +
            "WHERE id = #{userId}")
    int ensureMarginInterestLastDate(@Param("userId") Long userId, @Param("today") LocalDate today);

    /** 锁定用户行（用于资金归还等强一致更新） */
    @Select("SELECT * FROM \"user\" WHERE id = #{userId} FOR UPDATE")
    User selectByIdForUpdate(@Param("userId") Long userId);

    /** 原子应用现金流入（先还息后还本，剩余入余额） */
    @Update("UPDATE \"user\" SET " +
            "margin_interest_accrued = COALESCE(margin_interest_accrued, 0) - #{paidInterest}, " +
            "margin_loan_principal = COALESCE(margin_loan_principal, 0) - #{paidPrincipal}, " +
            "balance = balance + #{creditedToBalance}, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} " +
            "AND COALESCE(margin_interest_accrued, 0) >= #{paidInterest} " +
            "AND COALESCE(margin_loan_principal, 0) >= #{paidPrincipal}")
    int atomicApplyCashInflow(@Param("userId") Long userId,
                              @Param("paidInterest") BigDecimal paidInterest,
                              @Param("paidPrincipal") BigDecimal paidPrincipal,
                              @Param("creditedToBalance") BigDecimal creditedToBalance);

    /** 原子计息：增加利息并更新计息日期 */
    @Update("UPDATE \"user\" SET margin_interest_accrued = COALESCE(margin_interest_accrued, 0) + #{interestDelta}, " +
            "margin_interest_last_date = #{today}, updated_at = NOW() " +
            "WHERE id = #{userId} AND is_bankrupt = FALSE")
    int atomicAccrueInterest(@Param("userId") Long userId,
                             @Param("interestDelta") BigDecimal interestDelta,
                             @Param("today") LocalDate today);

    /** 标记爆仓并清空资金相关状态 */
    @Update("UPDATE \"user\" SET " +
            "is_bankrupt = TRUE, " +
            "bankrupt_count = COALESCE(bankrupt_count, 0) + 1, " +
            "bankrupt_at = NOW(), " +
            "bankrupt_reset_date = #{resetDate}, " +
            "balance = 0, " +
            "frozen_balance = 0, " +
            "margin_loan_principal = 0, " +
            "margin_interest_accrued = 0, " +
            "margin_interest_last_date = #{today}, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} AND is_bankrupt = FALSE")
    int markBankrupt(@Param("userId") Long userId,
                     @Param("resetDate") LocalDate resetDate,
                     @Param("today") LocalDate today);

    /** 破产恢复（交易日09:00） */
    @Update("UPDATE \"user\" SET " +
            "is_bankrupt = FALSE, " +
            "balance = #{initialBalance}, " +
            "frozen_balance = 0, " +
            "margin_loan_principal = 0, " +
            "margin_interest_accrued = 0, " +
            "margin_interest_last_date = #{today}, " +
            "bankrupt_reset_date = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} AND is_bankrupt = TRUE AND bankrupt_reset_date <= #{today}")
    int resetAfterBankruptcy(@Param("userId") Long userId,
                             @Param("initialBalance") BigDecimal initialBalance,
                             @Param("today") LocalDate today);
}
