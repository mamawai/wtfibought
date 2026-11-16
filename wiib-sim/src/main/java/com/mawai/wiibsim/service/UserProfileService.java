package com.mawai.wiibsim.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.entity.CryptoPosition;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.dto.ProfilePositionDTO;
import com.mawai.wiibsim.dto.UserProfileDTO;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 排行榜用户详情。只读，把"能不能看"和"看到什么"两件事分开：
 * 门控在 {@link #assertVisible}，取数在下面几个方法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserService userService;
    private final RankingService rankingService;
    private final CryptoPositionService cryptoPositionService;
    private final FuturesPositionMapper futuresPositionMapper;
    private final AssetValuationService assetValuationService;

    /**
     * 隐私门控：目标用户关了 profile_public 就不许看，本人永远放行。
     * 用户不存在也回 FORBIDDEN 不回 USER_NOT_FOUND，不给"这个 id 有没有人"当探针。
     */
    public void assertVisible(Long targetUserId, Long viewerUserId) {
        if (targetUserId.equals(viewerUserId)) return;
        User target = userService.getById(targetUserId);
        // profile_public 列是 NOT NULL DEFAULT TRUE，读到 null 只可能是实体没映上，
        // 那种情况按"不公开"处理更安全——宁可少给看，不能多给看
        if (target == null || !Boolean.TRUE.equals(target.getProfilePublic())) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
    }

    /** 详情主体：榜单行 + 当前持仓。从没成交过的人抛 NOT_FOUND（榜上本来就没有他） */
    public UserProfileDTO getProfile(Long targetUserId) {
        RankingDTO summary = rankingService.findRanking(targetUserId);
        if (summary == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        UserProfileDTO dto = new UserProfileDTO();
        dto.setSummary(summary);
        dto.setSpotPositions(spotPositions(targetUserId));
        dto.setFuturesPositions(futuresPositions(targetUserId));
        return dto;
    }

    /** 现货持仓（含 bStock，两者同表）。价格一次批量取，不逐个 symbol 打 */
    private List<ProfilePositionDTO> spotPositions(Long userId) {
        List<CryptoPosition> positions = cryptoPositionService.getUserPositions(userId);
        if (positions.isEmpty()) return List.of();

        Map<String, BigDecimal> priceMap = cryptoPositionService.fetchCryptoPriceMap();
        List<ProfilePositionDTO> list = new ArrayList<>(positions.size());
        for (CryptoPosition cp : positions) {
            BigDecimal price = priceMap.get(cp.getSymbol());
            ProfilePositionDTO dto = new ProfilePositionDTO();
            dto.setSymbol(cp.getSymbol());
            dto.setQuantity(cp.getTotalQuantity());
            dto.setEntryPrice(cp.getAvgCost());
            dto.setCurrentPrice(price);
            if (price != null) {
                BigDecimal value = price.multiply(cp.getTotalQuantity());
                dto.setValue(value);
                dto.setProfit(value.subtract(cp.getAvgCost().multiply(cp.getTotalQuantity())));
            }
            // 缺价时 value/profit 留 null，前端显示"—"。填 0 会被当成"这仓真的一文不值"
            list.add(dto);
        }
        return list;
    }

    /** 合约持仓：估值口径与排行榜/资产页共用 AssetValuationService，避免第三套算法 */
    private List<ProfilePositionDTO> futuresPositions(Long userId) {
        List<FuturesPosition> positions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>()
                        .eq(FuturesPosition::getUserId, userId)
                        .eq(FuturesPosition::getStatus, "OPEN"));

        List<ProfilePositionDTO> list = new ArrayList<>(positions.size());
        for (FuturesPosition fp : positions) {
            BigDecimal mark = assetValuationService.resolveFuturesPrice(fp.getSymbol());
            ProfilePositionDTO dto = new ProfilePositionDTO();
            dto.setSymbol(fp.getSymbol());
            dto.setQuantity(fp.getQuantity());
            dto.setEntryPrice(fp.getEntryPrice());
            dto.setCurrentPrice(mark);
            dto.setSide(fp.getSide());
            dto.setLeverage(fp.getLeverage());
            dto.setMarginMode(fp.getMarginMode());
            // 缺价时这两个静态方法按"保留保证金、浮盈亏记0"处理，与排行榜同口径
            dto.setValue(AssetValuationService.futuresPositionValue(fp, mark));
            dto.setProfit(AssetValuationService.futuresUnrealizedPnl(fp, mark));
            list.add(dto);
        }
        return list;
    }
}
