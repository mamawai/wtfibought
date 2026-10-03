package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资金方法改 @Select + UPDATE...RETURNING 的范式（账本要拿变动后余额，影响行数不够用）。
 * 已由 UserLedgerRealRunTest 真跑钉死，全部原子资金方法已按这三条改完，以后新增的照抄：
 * <ol>
 *   <li><b>必须 @Options(flushCache=TRUE, useCache=false)</b>。挂 @Select 但实为 UPDATE，
 *       默认会吃缓存：同事务内第二次同参调用直接返上次的值、SQL 不发 DB，钱静默没扣。
 *       二级缓存目前全局没开，useCache 是给"哪天开了"兜底，免得回头再改一遍。</li>
 *   <li><b>返回 null = 没改成（WHERE 没匹配到行）</b>。前提是 RETURNING 的列本身非空——
 *       列可空的话 null 就有二义性（分不清"没匹配到行"还是"匹配了但列是 NULL"），
 *       那种列不能直接这么用。现有资金列都是 NOT NULL，安全。</li>
 *   <li><b>多列返 record 时，RETURNING 的列序必须与 record 组件顺序一一对应</b>。
 *       MyBatis 走构造器自动映射（argNameBasedConstructorAutoMapping 默认 false），
 *       是<b>按列序依次填组件</b>，不看列名、mapUnderscoreToCamelCase 也不参与。
 *       组件又全是 BigDecimal，类型检查兜不住——列序写反不报错，直接把余额和冻结余额对调后写进账本。
 *       改 RETURNING 列序或改 record 组件顺序，必须同时改另一边。</li>
 * </ol>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 冻结/解冻返回：可用与冻结两个钱包的变动后新值。null=条件不满足，一行没改。
     * <p>
     * 组件顺序钉死为 (balance, frozenBalance)，对应 RETURNING balance, frozen_balance。
     * 两个都是 BigDecimal，写反了编译和运行都不报错，只是账本里可用/冻结对调——见类注释第 3 条。
     */
    record BalanceFrozen(BigDecimal balance, BigDecimal frozenBalance) {}

    /**
     * 双向划转返回：余额钱包与游戏钱包的变动后新值。null=转出方不够。
     * <p>
     * 组件顺序钉死为 (balance, gameBalance)，对应 RETURNING balance, game_balance。
     */
    record BalanceGame(BigDecimal balance, BigDecimal gameBalance) {}

    /**
     * 现金流入（先还息、再还本、剩下入余额）返回：三列的变动后新值。null=条件不满足。
     * <p>
     * 组件顺序钉死为 (marginInterestAccrued, marginLoanPrincipal, balance)，
     * 对应 RETURNING margin_interest_accrued, margin_loan_principal, balance。
     */
    record CashInflow(BigDecimal marginInterestAccrued, BigDecimal marginLoanPrincipal, BigDecimal balance) {}

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

    /** 同步自增序列到当前最大 id：insertAdmin 显式写 id 不推进序列，新库不同步会让下一次自增插入撞 id=1 */
    @Select("SELECT setval(pg_get_serial_sequence('\"user\"', 'id'), (SELECT COALESCE(MAX(id), 1) FROM \"user\"))")
    Long syncIdSequence();

    /**
     * 重置到初始账户（自助重置用）。
     * SET 子句刻意不含 muted_until——否则被禁言的用户点一下重置就解禁了；
     * 也不动 username/avatar/linux_do_id/invite_code_id 等身份字段。
     */
    @Update("UPDATE \"user\" SET " +
            "balance = #{initialBalance}, " +
            "frozen_balance = 0, " +
            "game_balance = 0, " +
            "margin_loan_principal = 0, " +
            "margin_interest_accrued = 0, " +
            "margin_interest_last_date = NULL, " +
            "is_bankrupt = FALSE, " +
            "bankrupt_count = 0, " +
            "bankrupt_at = NULL, " +
            "bankrupt_reset_date = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId}")
    int resetToInitial(@Param("userId") long userId, @Param("initialBalance") BigDecimal initialBalance);

    /** 禁言到期时间（留言板管理用）。只动这一列，且 resetToInitial 刻意不复位它——禁言要扛过重置 */
    @Update("UPDATE \"user\" SET muted_until = #{mutedUntil}, updated_at = NOW() WHERE id = #{userId}")
    int updateMutedUntil(@Param("userId") long userId, @Param("mutedUntil") LocalDateTime mutedUntil);

    /**
     * 原子更新可用余额，返回变动后余额；null=余额不足没改成。
     * RETURNING 是同条语句的返回子句，不是二次查询——写的仍是 balance = balance + delta
     * 相对更新、条件仍由 DB 判定，并发语义与改造前完全一致。账本靠这个返回值拿 balance_after。
     * <p>
     * flushCache 必须开：这条挂 @Select 但实为 UPDATE。MyBatis 对 select 默认吃一级缓存，
     * 同事务内第二次同参调用会直接返上次的缓存值、SQL 根本不发给 DB——钱静默没扣，
     * 而返回值还装作扣成了。原来挂 @Update 时每次都自动清缓存，不存在这问题，改 @Select 才冒出来。
     * 已实测复现（见 同事务内重复扣款每次都真发SQL 用例）。资金语句一律不许走缓存。
     * <p>
     * balance 是 NOT NULL 列，所以 null 只可能是"没匹配到行"，无二义性（见类注释第 2 条）。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET balance = balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance + #{amount} >= 0 " +
            "RETURNING balance")
    BigDecimal atomicUpdateBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 原子冻结余额：可用减少，冻结增加。返回两个钱包的变动后新值；null=可用不足。
     * <p>
     * RETURNING 列序 balance, frozen_balance == BalanceFrozen 组件顺序，改一边必须同时改另一边。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET balance = balance - #{amount}, frozen_balance = frozen_balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance >= #{amount} " +
            "RETURNING balance, frozen_balance")
    BalanceFrozen atomicFreezeBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 原子解冻余额：冻结减少，可用增加。返回两个钱包的变动后新值；null=冻结不足。
     * <p>
     * RETURNING 列序 balance, frozen_balance == BalanceFrozen 组件顺序，改一边必须同时改另一边。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET balance = balance + #{amount}, frozen_balance = frozen_balance - #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND frozen_balance >= #{amount} " +
            "RETURNING balance, frozen_balance")
    BalanceFrozen atomicUnfreezeBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子扣除冻结余额，返回变动后冻结余额；null=冻结余额不足 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET frozen_balance = frozen_balance - #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND frozen_balance >= #{amount} " +
            "RETURNING frozen_balance")
    BigDecimal atomicDeductFrozenBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子更新游戏钱包，返回变动后游戏钱包余额；null=游戏钱包不足 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET game_balance = game_balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} AND game_balance + #{amount} >= 0 " +
            "RETURNING game_balance")
    BigDecimal atomicUpdateGameBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /**
     * 余额钱包→游戏钱包，单条SQL原子划转；net=扣掉手续费后的实际到账。返回两钱包新值；null=余额不足。
     * <p>
     * RETURNING 列序 balance, game_balance == BalanceGame 组件顺序，改一边必须同时改另一边。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET balance = balance - #{amount}, game_balance = game_balance + #{net}, updated_at = NOW() " +
            "WHERE id = #{userId} AND balance >= #{amount} " +
            "RETURNING balance, game_balance")
    BalanceGame atomicTransferToGame(@Param("userId") Long userId, @Param("amount") BigDecimal amount, @Param("net") BigDecimal net);

    /**
     * 游戏钱包→余额钱包，单条SQL原子划转；net=扣掉手续费后的实际到账。返回两钱包新值；null=游戏钱包不足。
     * <p>
     * RETURNING 列序 balance, game_balance == BalanceGame 组件顺序。注意 SET 是先 game 后 balance、
     * RETURNING 是先 balance 后 game——只有 RETURNING 的列序参与映射，别照着 SET 的顺序去"对齐"。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET game_balance = game_balance - #{amount}, balance = balance + #{net}, updated_at = NOW() " +
            "WHERE id = #{userId} AND game_balance >= #{amount} " +
            "RETURNING balance, game_balance")
    BalanceGame atomicTransferToBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount, @Param("net") BigDecimal net);

    /**
     * 全仓结算专用：直接加减余额，允许扣成负数（穿仓缺口由破产流程接管），其他场景禁用。
     * 没有余额条件，只要用户在就返回变动后余额；null 只可能是用户不存在。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET balance = balance + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} RETURNING balance")
    BigDecimal atomicSettleBalance(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 原子增加杠杆借款本金，返回变动后本金；null=用户不存在 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET margin_loan_principal = margin_loan_principal + #{amount}, updated_at = NOW() " +
            "WHERE id = #{userId} RETURNING margin_loan_principal")
    BigDecimal atomicAddMarginLoanPrincipal(@Param("userId") Long userId, @Param("amount") BigDecimal amount);

    /** 确保计息上次日期存在（仅在null时设置） */
    @Update("UPDATE \"user\" SET margin_interest_last_date = COALESCE(margin_interest_last_date, #{today}), updated_at = NOW() " +
            "WHERE id = #{userId}")
    int ensureMarginInterestLastDate(@Param("userId") Long userId, @Param("today") LocalDate today);

    /**
     * 本金还清后清空计息起算点，下次借款由 ensureMarginInterestLastDate 重新写成借款日。
     * 不清的话起算点会停在还清前最后一次计息那天——还清期间计息任务按本金>0过滤，扫不到该用户，
     * 没人推进它，下次借款就把中间没欠钱的空档天数一起算成利息。
     * 本金条件放 WHERE：与新借款并发时本金已变正，此时不该抹掉新写入的起算点。
     */
    @Update("UPDATE \"user\" SET margin_interest_last_date = NULL, updated_at = NOW() " +
            "WHERE id = #{userId} AND margin_loan_principal = 0")
    int clearMarginInterestLastDate(@Param("userId") Long userId);

    /**
     * 锁定用户行（用于资金归还等强一致更新）。
     * <p>
     * 这条是真 SELECT，但同样必须禁缓存，理由和上面那批 UPDATE...RETURNING 不一样：
     * 它的价值不在返回值，在<b>取行锁</b>——而锁是"把 SQL 发给 DB"的副作用。
     * 同事务内若更早处已用同一 userId 调过一次，MyBatis 一级缓存会直接返旧对象，
     * FOR UPDATE 语句根本不发 DB，<b>锁没取到，调用方却以为自己拿着锁</b>，
     * 后面基于旧值算出来的 delta 也是脏的。这种"静默失效"比报错难查得多。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("SELECT * FROM \"user\" WHERE id = #{userId} FOR UPDATE")
    User selectByIdForUpdate(@Param("userId") Long userId);

    /**
     * 原子应用现金流入（先还息后还本，剩余入余额）。返回三列变动后新值；null=还款额超过欠款或用户不存在。
     * <p>
     * RETURNING 列序 margin_interest_accrued, margin_loan_principal, balance == CashInflow 组件顺序，
     * 三列全是 BigDecimal，写反了不报错只错账，改一边必须同时改另一边。
     */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET " +
            "margin_interest_accrued = margin_interest_accrued - #{paidInterest}, " +
            "margin_loan_principal = margin_loan_principal - #{paidPrincipal}, " +
            "balance = balance + #{creditedToBalance}, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} " +
            "AND margin_interest_accrued >= #{paidInterest} " +
            "AND margin_loan_principal >= #{paidPrincipal} " +
            "RETURNING margin_interest_accrued, margin_loan_principal, balance")
    CashInflow atomicApplyCashInflow(@Param("userId") Long userId,
                                     @Param("paidInterest") BigDecimal paidInterest,
                                     @Param("paidPrincipal") BigDecimal paidPrincipal,
                                     @Param("creditedToBalance") BigDecimal creditedToBalance);

    /** 原子计息：增加利息并更新计息日期，返回变动后应计利息；null=用户不存在或已破产（破产不计息） */
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    @Select("UPDATE \"user\" SET margin_interest_accrued = margin_interest_accrued + #{interestDelta}, " +
            "margin_interest_last_date = #{today}, updated_at = NOW() " +
            "WHERE id = #{userId} AND is_bankrupt = FALSE " +
            "RETURNING margin_interest_accrued")
    BigDecimal atomicAccrueInterest(@Param("userId") Long userId,
                                    @Param("interestDelta") BigDecimal interestDelta,
                                    @Param("today") LocalDate today);

    /** 标记爆仓并清空资金相关状态 */
    @Update("UPDATE \"user\" SET " +
            "is_bankrupt = TRUE, " +
            "bankrupt_count = bankrupt_count + 1, " +
            "bankrupt_at = NOW(), " +
            "bankrupt_reset_date = #{resetDate}, " +
            "balance = 0, " +
            "frozen_balance = 0, " +
            "game_balance = 0, " +
            "margin_loan_principal = 0, " +
            "margin_interest_accrued = 0, " +
            "margin_interest_last_date = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} AND is_bankrupt = FALSE")
    int markBankrupt(@Param("userId") Long userId,
                     @Param("resetDate") LocalDate resetDate);

    /** 破产恢复（交易日09:00） */
    @Update("UPDATE \"user\" SET " +
            "is_bankrupt = FALSE, " +
            "balance = #{initialBalance}, " +
            "frozen_balance = 0, " +
            "game_balance = 0, " +
            "margin_loan_principal = 0, " +
            "margin_interest_accrued = 0, " +
            "margin_interest_last_date = NULL, " +
            "bankrupt_reset_date = NULL, " +
            "updated_at = NOW() " +
            "WHERE id = #{userId} AND is_bankrupt = TRUE AND bankrupt_reset_date <= #{today}")
    int resetAfterBankruptcy(@Param("userId") Long userId,
                             @Param("initialBalance") BigDecimal initialBalance,
                             @Param("today") LocalDate today);
}
