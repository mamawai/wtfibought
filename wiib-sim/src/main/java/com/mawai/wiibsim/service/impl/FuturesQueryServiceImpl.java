package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.dto.FuturesPositionDTO;
import com.mawai.wiibcommon.entity.FuturesOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesPrices;
import com.mawai.wiibsim.service.FuturesQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibsim.service.impl.FuturesHelper.buildOrderResponse;
import static com.mawai.wiibsim.service.impl.FuturesHelper.calculatePnl;

@Slf4j
@Service
@RequiredArgsConstructor
public class FuturesQueryServiceImpl implements FuturesQueryService {

    /** 订单列表单页封顶；K 线上的成交标记一次要拉 200 笔 */
    private static final int ORDER_PAGE_MAX = 200;

    private final FuturesPositionMapper positionMapper;
    private final FuturesOrderMapper orderMapper;
    private final TradingConfig tradingConfig;
    private final FuturesPositionIndexService positionIndexService;
    private final FuturesLeverageBracketRegistry bracketRegistry;
    private final CrossMarginService crossMarginService;
    private final FuturesPrices prices;

    // ==================== 查询仓位 ====================

    @Override
    public List<FuturesPositionDTO> getUserPositions(Long userId, String symbol) {
        LambdaQueryWrapper<FuturesPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesPosition::getUserId, userId);
        wrapper.eq(FuturesPosition::getStatus, "OPEN");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesPosition::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesPosition::getCreatedAt);

        List<FuturesPosition> positions = positionMapper.selectList(wrapper);
        List<FuturesPositionDTO> result = new ArrayList<>();

        // 全仓强平价依赖账户整体状态，快照每用户算一次全列表共用
        CrossMarginService.CrossAccount crossAccount = positions.stream().anyMatch(FuturesPosition::isCross)
                ? crossMarginService.snapshot(userId) : null;

        // 持仓期已实现盈亏（开/加仓手续费+部分平仓净额）从订单流水一次聚合，全列表共用
        Map<Long, BigDecimal> realizedMap = sumRealizedByPosition(positions);

        for (FuturesPosition pos : positions) {
            try {
                BigDecimal markPrice = prices.mark(pos.getSymbol());
                FuturesPositionDTO dto = buildPositionDTO(pos, markPrice, crossAccount);
                dto.setRealizedPnl(realizedMap.getOrDefault(pos.getId(), BigDecimal.ZERO));
                result.add(dto);
            } catch (Exception e) {
                log.warn("查询仓位价格失败 posId={}", pos.getId(), e);
            }
        }

        return result;
    }

    private Map<Long, BigDecimal> sumRealizedByPosition(List<FuturesPosition> positions) {
        if (positions.isEmpty()) return Map.of();
        Long[] ids = positions.stream().map(FuturesPosition::getId).toArray(Long[]::new);
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : orderMapper.sumRealizedPnlByPositionIds(ids)) {
            map.put(((Number) row.get("position_id")).longValue(), (BigDecimal) row.get("amount"));
        }
        return map;
    }

    /** 每个仓位开仓和加仓成交的数量合计 */
    private Map<Long, BigDecimal> openedQuantityByPosition(List<FuturesPosition> positions) {
        if (positions.isEmpty()) return Map.of();
        Long[] ids = positions.stream().map(FuturesPosition::getId).toArray(Long[]::new);
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : orderMapper.sumOpenedQuantityByPositionIds(ids)) {
            map.put(((Number) row.get("position_id")).longValue(), (BigDecimal) row.get("amount"));
        }
        return map;
    }

    /** 了结每个仓位的那张平仓单的状态 */
    private Map<Long, String> closedByPosition(List<FuturesPosition> positions) {
        if (positions.isEmpty()) return Map.of();
        Long[] ids = positions.stream().map(FuturesPosition::getId).toArray(Long[]::new);
        Map<Long, String> map = new HashMap<>();
        for (Map<String, Object> row : orderMapper.lastCloseStatusByPositionIds(ids)) {
            map.put(((Number) row.get("position_id")).longValue(), (String) row.get("status"));
        }
        return map;
    }

    @Override
    public List<FuturesPositionDTO> getClosedPositions(Long userId, String symbol, int limit) {
        LambdaQueryWrapper<FuturesPosition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesPosition::getUserId, userId);
        wrapper.in(FuturesPosition::getStatus, "CLOSED", "LIQUIDATED");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesPosition::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesPosition::getUpdatedAt);
        wrapper.last("LIMIT " + Math.clamp(limit, 1, 500));
        List<FuturesPosition> rows = positionMapper.selectList(wrapper);
        // 净盈亏按订单流水聚合
        Map<Long, BigDecimal> realizedMap = sumRealizedByPosition(rows);
        Map<Long, String> closedByMap = closedByPosition(rows);
        Map<Long, BigDecimal> openedMap = openedQuantityByPosition(rows);
        // 已平仓只出静态字段，不走 buildPositionDTO 的实时价计算
        return rows.stream().map(pos -> {
            FuturesPositionDTO dto = new FuturesPositionDTO();
            dto.setId(pos.getId());
            dto.setUserId(pos.getUserId());
            dto.setSymbol(pos.getSymbol());
            dto.setSide(pos.getSide());
            dto.setMarginMode(pos.getMarginMode());
            dto.setLeverage(pos.getLeverage());
            dto.setQuantity(pos.getQuantity());
            dto.setOpenedQuantity(openedMap.get(pos.getId()));
            dto.setEntryPrice(pos.getEntryPrice());
            dto.setMargin(pos.getMargin());
            dto.setFundingFeeTotal(pos.getFundingFeeTotal());
            // 全平不清保护单列表（只有部分平仓会改写），随行带出：了结时在岗的那组档位
            dto.setStopLosses(pos.getStopLosses());
            dto.setTakeProfits(pos.getTakeProfits());
            dto.setStatus(pos.getStatus());
            dto.setClosedPrice(pos.getClosedPrice());
            // 了结方式看了结它的那张平仓单：止损止盈按盘口成交，成交价可能没越过触发价，不能拿价去猜
            dto.setClosedBy(closedByMap.get(pos.getId()));
            // 净盈亏 = 各平仓单盈亏 − 开平手续费 − 资金费；清零的仓位没有平仓单，保持 null
            dto.setClosedPnl(pos.getClosedPnl() == null ? null
                    : realizedMap.getOrDefault(pos.getId(), BigDecimal.ZERO).subtract(pos.getFundingFeeTotal()));
            dto.setMemo(pos.getMemo());
            dto.setCreatedAt(pos.getCreatedAt());
            dto.setUpdatedAt(pos.getUpdatedAt());
            return dto;
        }).toList();
    }

    private FuturesPositionDTO buildPositionDTO(FuturesPosition pos, BigDecimal markPrice,
                                                CrossMarginService.CrossAccount crossAccount) {
        FuturesPositionDTO dto = new FuturesPositionDTO();
        dto.setId(pos.getId());
        dto.setUserId(pos.getUserId());
        dto.setSymbol(pos.getSymbol());
        dto.setSide(pos.getSide());
        dto.setMarginMode(pos.getMarginMode());
        dto.setLeverage(pos.getLeverage());
        dto.setQuantity(pos.getQuantity());
        dto.setEntryPrice(pos.getEntryPrice());
        dto.setMargin(pos.getMargin());
        dto.setFundingFeeTotal(pos.getFundingFeeTotal());
        dto.setStopLosses(pos.getStopLosses());
        dto.setTakeProfits(pos.getTakeProfits());
        dto.setStatus(pos.getStatus());
        dto.setClosedPrice(pos.getClosedPrice());
        dto.setClosedPnl(pos.getClosedPnl());
        dto.setMemo(pos.getMemo());
        dto.setCreatedAt(pos.getCreatedAt());
        dto.setUpdatedAt(pos.getUpdatedAt());

        dto.setCurrentPrice(prices.latest(pos.getSymbol()));
        dto.setMarkPrice(markPrice);
        BigDecimal positionValue = markPrice.multiply(pos.getQuantity()).setScale(2, RoundingMode.HALF_UP);
        dto.setPositionValue(positionValue);

        BigDecimal unrealizedPnl = calculatePnl(pos.getSide(), pos.getEntryPrice(), markPrice, pos.getQuantity());
        dto.setUnrealizedPnl(unrealizedPnl);

        BigDecimal unrealizedPnlPct = pos.getMargin().compareTo(BigDecimal.ZERO) > 0
                ? unrealizedPnl.divide(pos.getMargin(), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;
        dto.setUnrealizedPnlPct(unrealizedPnlPct);

        BigDecimal effectiveMargin = pos.getMargin().add(unrealizedPnl);
        dto.setEffectiveMargin(effectiveMargin);

        BigDecimal maintenanceMargin = bracketRegistry.calcMaintenanceMargin(
                pos.getSymbol(), positionValue);
        dto.setMaintenanceMargin(maintenanceMargin);

        // 逐仓强平价是静态的；全仓强平价随余额和其他仓位浮盈亏动态变化，按当下账户状态估算
        BigDecimal liquidationPrice = pos.isCross() && crossAccount != null
                ? crossMarginService.estimateLiqPrice(pos, crossAccount)
                : positionIndexService.calcStaticLiqPrice(pos.getSymbol(), pos.getSide(), pos.getEntryPrice(),
                        pos.getMargin(), pos.getQuantity());
        dto.setLiquidationPrice(liquidationPrice);

        BigDecimal entryValue = pos.getEntryPrice().multiply(pos.getQuantity());
        BigDecimal fundingFeePerCycle = entryValue.multiply(tradingConfig.getFutures().getFundingRate());
        dto.setFundingFeePerCycle(fundingFeePerCycle);

        return dto;
    }

    // ==================== 查询订单 ====================

    @Override
    public FuturesOrderResponse getOrder(Long userId, Long orderId) {
        FuturesOrder order = orderMapper.selectById(orderId);
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        return buildOrderResponse(order);
    }

    @Override
    public List<FuturesOrderResponse> getPendingOrders(Long userId, String symbol) {
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getUserId, userId).eq(FuturesOrder::getStatus, "PENDING");
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesOrder::getSymbol, symbol);
        }
        return orderMapper.selectList(wrapper).stream().map(FuturesHelper::buildOrderResponse).toList();
    }

    @Override
    public List<FuturesOrderResponse> getGroupOrders(Long userId, String groupId) {
        return orderMapper.selectList(new LambdaQueryWrapper<FuturesOrder>()
                        .eq(FuturesOrder::getUserId, userId).eq(FuturesOrder::getGroupId, groupId)
                        .orderByAsc(FuturesOrder::getId))
                .stream().map(FuturesHelper::buildOrderResponse).toList();
    }

    @Override
    public IPage<FuturesOrderResponse> getUserOrders(Long userId, String status, int pageNum, int pageSize, String symbol) {
        Page<FuturesOrder> page = new Page<>(Math.max(pageNum, 1), Math.clamp(pageSize, 1, ORDER_PAGE_MAX));
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getUserId, userId);
        if (status != null && !status.isEmpty()) {
            wrapper.eq(FuturesOrder::getStatus, status);
        }
        if (symbol != null && !symbol.isEmpty()) {
            wrapper.eq(FuturesOrder::getSymbol, symbol);
        }
        wrapper.orderByDesc(FuturesOrder::getCreatedAt);
        orderMapper.selectPage(page, wrapper);
        return page.convert(FuturesHelper::buildOrderResponse);
    }

    @Override
    public List<FuturesOrderResponse> getLatestOrders() {
        LambdaQueryWrapper<FuturesOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FuturesOrder::getStatus, "FILLED")
                .orderByDesc(FuturesOrder::getCreatedAt)
                .last("LIMIT 20");
        return orderMapper.selectList(wrapper).stream()
                .map(o -> {
                    // 全站流不带人，和 /trades/public 一个口径
                    FuturesOrderResponse r = buildOrderResponse(o);
                    r.setUserId(null);
                    return r;
                })
                .toList();
    }
}
