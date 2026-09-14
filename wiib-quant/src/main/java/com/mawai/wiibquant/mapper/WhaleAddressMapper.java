package com.mawai.wiibquant.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * Hyperliquid 大户地址池读写。与 {@link EconCalendarMapper} 同款口径：不建 entity 不继承 BaseMapper，
 * 按地址 upsert，查询按用方要的形状投影。
 */
@Mapper
public interface WhaleAddressMapper {

    /** 已知账户：每日认证的起点（主地址要重新展开，子账户要补查，role 决定门 2 查不查、vault 碰不碰） */
    @Data
    class Known {
        private String address;
        /** 子账户的主地址；主地址为 null */
        private String parentAddress;
        /** user / subAccount / vault；null=还没查过 */
        private String role;
    }

    /** 落库行：认证结论 */
    @Data
    class Row {
        private String address;
        private String parentAddress;
        private String role;
        private boolean inPool;
        private BigDecimal accountValue;
        private Integer positionCount;
        private BigDecimal trackedMaxPosition;
        /** SMALL / TOO_MANY_POSITIONS / VAULT / OVER_CAP；null=合格 */
        private String rejectReason;
        private Long firstSeenAt;
        /** 合格才给；不合格给 null 留旧值 */
        private Long qualifiedAt;
    }

    @Select("SELECT address, parent_address AS parentAddress, role FROM whale_address")
    List<Known> selectKnown();

    @Select("SELECT COUNT(*) FROM whale_address")
    int count();

    /** 轮询名单 */
    @Select("SELECT address FROM whale_address WHERE in_pool ORDER BY account_value DESC")
    List<String> selectPoolAddresses();

    /** 按地址 upsert：首次见到才写 first_seen_at；role 与 parent 只在有值时覆盖；qualified_at 不合格时留旧值 */
    @Insert("""
            INSERT INTO whale_address (address, parent_address, role, in_pool, account_value, position_count,
                                       tracked_max_position, reject_reason, first_seen_at, qualified_at)
            VALUES (#{address}, #{parentAddress}, #{role}, #{inPool}, #{accountValue}, #{positionCount},
                    #{trackedMaxPosition}, #{rejectReason}, #{firstSeenAt}, #{qualifiedAt})
            ON CONFLICT (address) DO UPDATE SET
                parent_address = COALESCE(EXCLUDED.parent_address, whale_address.parent_address),
                role = COALESCE(EXCLUDED.role, whale_address.role),
                in_pool = EXCLUDED.in_pool,
                account_value = EXCLUDED.account_value,
                position_count = EXCLUDED.position_count,
                tracked_max_position = EXCLUDED.tracked_max_position,
                reject_reason = EXCLUDED.reject_reason,
                qualified_at = COALESCE(EXCLUDED.qualified_at, whale_address.qualified_at),
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(Row row);
}
