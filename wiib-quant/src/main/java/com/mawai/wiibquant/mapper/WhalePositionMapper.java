package com.mawai.wiibquant.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * 池内地址在盯盘币上的仓位变化流水。只在 szi/entryPx 变了才写一行，szi=0 表示已平。
 */
@Mapper
public interface WhalePositionMapper {

    @Data
    class Row {
        private long observedAt;
        private String address;
        private String coin;
        private BigDecimal szi;
        private BigDecimal entryPx;
        private BigDecimal positionValue;
        private Integer leverage;
        private BigDecimal liquidationPx;
        private BigDecimal unrealizedPnl;
    }

    /** 每个 (address, coin) 的最近一行：首轮轮询的对比基线 */
    @Data
    class Latest {
        private String address;
        private String coin;
        private BigDecimal szi;
        private BigDecimal entryPx;
    }

    @Insert("""
            INSERT INTO whale_position (observed_at, address, coin, szi, entry_px, position_value, leverage, liquidation_px, unrealized_pnl)
            VALUES (#{observedAt}, #{address}, #{coin}, #{szi}, #{entryPx}, #{positionValue}, #{leverage}, #{liquidationPx}, #{unrealizedPnl})
            """)
    int insert(Row row);

    @Select("""
            SELECT DISTINCT ON (address, coin) address, coin, szi, entry_px AS entryPx
              FROM whale_position
             ORDER BY address, coin, observed_at DESC, id DESC
            """)
    List<Latest> selectLatest();
}
