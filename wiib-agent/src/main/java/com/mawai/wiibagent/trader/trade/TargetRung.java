package com.mawai.wiibagent.trader.trade;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibcommon.market.TradeFilterDefaults;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 分批止盈的一档：价位，和这一档平掉仓位的百分之几。
 * open_position / set_take_profit 的入参是它，计划里的止盈快照（take_profits_json）也按它存。
 */
public record TargetRung(
        @JsonPropertyDescription("Take-profit price of this rung") BigDecimal price,
        @JsonPropertyDescription("Percent of the position this rung closes, e.g. 50") BigDecimal percent) {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final TypeReference<List<TargetRung>> LIST = new TypeReference<>() {
    };

    /** 计划里存的止盈快照；没设止盈给空表 */
    public static List<TargetRung> of(AiTraderPlan plan) {
        return plan == null || plan.getTakeProfitsJson() == null ? List.of()
                : MAPPER.readValue(plan.getTakeProfitsJson(), LIST);
    }

    /** 仓位上现挂的止盈单折成档位：占比按仓位当前数量算，由近到远 */
    public static List<TargetRung> of(FuturesPositionDTO pos) {
        if (pos.getTakeProfits() == null) {
            return List.of();
        }
        boolean isLong = "LONG".equals(pos.getSide());
        Comparator<TargetRung> byPrice = Comparator.comparing(TargetRung::price);
        return pos.getTakeProfits().stream()
                .map(tp -> new TargetRung(tp.getPrice(),
                        tp.getQuantity().multiply(HUNDRED).divide(pos.getQuantity(), 0, RoundingMode.HALF_UP)))
                .sorted(isLong ? byPrice : byPrice.reversed())
                .toList();
    }

    /** 存进计划的 JSON；没有档位给 null */
    public static String toJson(List<TargetRung> rungs) {
        return rungs.isEmpty() ? null : MAPPER.writeValueAsString(rungs);
    }

    /** "72.4(50%) / 70.1(50%)" 这种写法；没有档位给空串 */
    public static String text(List<TargetRung> rungs) {
        return rungs.stream()
                .map(r -> plain(r.price()) + "(" + plain(r.percent()) + "%)")
                .collect(Collectors.joining(" / "));
    }

    /**
     * 各档的数量：仓位数量 × 占比，按交易所步长向下取整；占比合计 100% 时最后一档拿走余数，不留零头。
     * 哪一档折出来不足一个步长就是 0，调用方据此拒。
     */
    public static List<BigDecimal> quantities(List<TargetRung> rungs, BigDecimal positionQty, String symbol) {
        BigDecimal covered = rungs.stream().map(TargetRung::percent).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> out = new ArrayList<>();
        BigDecimal used = BigDecimal.ZERO;
        for (int i = 0; i < rungs.size(); i++) {
            BigDecimal qty = i == rungs.size() - 1 && covered.compareTo(HUNDRED) == 0
                    ? positionQty.subtract(used) : share(rungs.get(i).percent(), positionQty, symbol);
            used = used.add(qty);
            out.add(qty);
        }
        return out;
    }

    /** 仓位数量的百分之几是多少个币，按交易所步长向下取整；100% 就是整个仓位 */
    public static BigDecimal share(BigDecimal percent, BigDecimal positionQty, String symbol) {
        if (percent.compareTo(HUNDRED) == 0) {
            return positionQty;
        }
        BigDecimal qty = positionQty.multiply(percent).divide(HUNDRED, 8, RoundingMode.FLOOR);
        TradeFilterDefaults.Filter filter = TradeFilterDefaults.futures(symbol);
        return filter == null ? qty : qty.divide(filter.stepSize(), 0, RoundingMode.FLOOR).multiply(filter.stepSize());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
