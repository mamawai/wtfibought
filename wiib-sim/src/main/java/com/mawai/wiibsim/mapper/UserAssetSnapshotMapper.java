package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mawai.wiibcommon.entity.UserAssetSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface UserAssetSnapshotMapper extends BaseMapper<UserAssetSnapshot> {

    @Select("SELECT * FROM user_asset_snapshot WHERE user_id = #{userId} AND snapshot_date >= #{startDate} ORDER BY snapshot_date ASC")
    List<UserAssetSnapshot> listByUserAndDateRange(@Param("userId") Long userId, @Param("startDate") LocalDate startDate);

    @Select("SELECT * FROM user_asset_snapshot WHERE user_id = #{userId} AND snapshot_date = #{date}")
    UserAssetSnapshot selectByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Insert("""
            INSERT INTO user_asset_snapshot (user_id, snapshot_date, total_assets, profit, profit_pct,
              bstock_profit, crypto_profit, commodity_profit, prediction_profit, game_profit, created_at)
            VALUES (#{userId}, #{snapshotDate}, #{totalAssets}, #{profit}, #{profitPct},
              #{bstockProfit}, #{cryptoProfit}, #{commodityProfit}, #{predictionProfit}, #{gameProfit}, #{createdAt})
            ON CONFLICT (user_id, snapshot_date) DO UPDATE SET
              total_assets = EXCLUDED.total_assets, profit = EXCLUDED.profit, profit_pct = EXCLUDED.profit_pct,
              bstock_profit = EXCLUDED.bstock_profit, crypto_profit = EXCLUDED.crypto_profit,
              commodity_profit = EXCLUDED.commodity_profit,
              prediction_profit = EXCLUDED.prediction_profit, game_profit = EXCLUDED.game_profit
            """)
    void upsert(UserAssetSnapshot snapshot);
}
