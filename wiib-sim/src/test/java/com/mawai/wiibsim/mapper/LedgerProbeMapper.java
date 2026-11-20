package com.mawai.wiibsim.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

/**
 * 账本不变量探针（测试专用）：某钱包全部流水累加，应等于 user 表当前该列的值。
 * <p>
 * 只对<b>账本上线后建号</b>的用户成立——期初基准是建号时补的那条 INITIAL_GRANT。
 * 存量用户的期初值既没记录也不回填（账本只记上线之后的变动），拿它们对账每个钱包各差
 * "上线那一刻该列的值"，那是口径不是漏账。用法见 UserLedgerRealRunTest#assertInvariant。
 * <p>
 * 放在 com.mawai.wiibsim.mapper 包下：{@code @MapperScan} 按包名扫描，
 * 测试源码目录下的同名包会一起被扫到——别挪走。
 */
@Mapper
public interface LedgerProbeMapper {

    @Select("SELECT COALESCE(SUM(delta), 0) FROM user_ledger WHERE user_id = #{userId} AND wallet = #{wallet}")
    BigDecimal sumDeltaByWallet(@Param("userId") Long userId, @Param("wallet") String wallet);
}
