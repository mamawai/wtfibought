package com.mawai.wiibsim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.OptionOrderDTO;
import com.mawai.wiibcommon.entity.OptionOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

@Mapper
public interface OptionOrderMapper extends BaseMapper<OptionOrder> {

    @Select({
            "<script>",
            "SELECT",
            "  oo.id AS orderId,",
            "  COALESCE(s.name, '') AS stockName,",
            "  oc.option_type AS optionType,",
            "  oo.order_side AS orderSide,",
            "  oo.status AS status,",
            "  oc.strike AS strike,",
            "  oo.quantity AS quantity,",
            "  oo.filled_price AS filledPrice,",
            "  oo.filled_amount AS filledAmount,",
            "  oo.commission AS commission,",
            "  oc.expire_at AS expireAt",
            "FROM option_order oo",
            "LEFT JOIN option_contract oc ON oc.id = oo.contract_id",
            "LEFT JOIN stock s ON s.id = oc.stock_id",
            "WHERE oo.user_id = #{userId}",
            "<if test='status != null and status != \"\"'>",
            "  AND oo.status = #{status}",
            "</if>",
            "ORDER BY oo.created_at DESC",
            "</script>"
    })
    IPage<OptionOrderDTO> selectUserOrdersPage(Page<OptionOrderDTO> page,
                                              @Param("userId") Long userId,
                                              @Param("status") String status);

    @Update("UPDATE option_order SET status = #{newStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = #{expectedStatus}")
    int casUpdateStatus(@Param("orderId") Long orderId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus);

    @Update("UPDATE option_order SET status = 'FILLED', filled_price = #{filledPrice}, " +
            "filled_amount = #{filledAmount}, underlying_price = #{underlyingPrice}, " +
            "commission = #{commission}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND status = 'PENDING'")
    int casUpdateToFilled(@Param("orderId") Long orderId,
                          @Param("filledPrice") BigDecimal filledPrice,
                          @Param("filledAmount") BigDecimal filledAmount,
                          @Param("underlyingPrice") BigDecimal underlyingPrice,
                          @Param("commission") BigDecimal commission);

    @Update("UPDATE option_order SET status = 'CANCELLED', updated_at = NOW() " +
            "WHERE user_id = #{userId} AND status = 'PENDING'")
    int cancelPendingOrdersByUserId(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(filled_amount + COALESCE(commission, 0)), 0) FROM option_order " +
            "WHERE user_id = #{userId} AND order_side = 'BTO' AND status = 'FILLED'")
    BigDecimal sumBtoFilledAmount(@Param("userId") Long userId);

    @Select("SELECT COALESCE(SUM(filled_amount - COALESCE(commission, 0)), 0) FROM option_order " +
            "WHERE user_id = #{userId} AND order_side = 'STC' AND status = 'FILLED'")
    BigDecimal sumStcFilledAmount(@Param("userId") Long userId);
}
