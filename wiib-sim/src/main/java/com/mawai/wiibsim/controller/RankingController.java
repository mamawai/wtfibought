package com.mawai.wiibsim.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibcommon.dto.PositionHistoryDTO;
import com.mawai.wiibsim.dto.PublicTradeDTO;
import com.mawai.wiibsim.dto.UserProfileDTO;
import com.mawai.wiibsim.service.PositionHistoryService;
import com.mawai.wiibsim.service.PublicTradeService;
import com.mawai.wiibsim.service.RankingService;
import com.mawai.wiibsim.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "排行榜接口")
@RestController
@RequestMapping("/api/ranking")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;
    private final UserProfileService userProfileService;
    private final PublicTradeService publicTradeService;
    private final PositionHistoryService positionHistoryService;

    /**
     * 排行榜分页。排名是全局的（先算完整榜才有名次），分页在整榜上切片。
     */
    @GetMapping
    @Operation(summary = "排行榜分页（只含有过成交的用户；sort=ASSETS/TRADING_PROFIT，pageSize 服务端封顶 100）")
    public Result<IPage<RankingDTO>> getRanking(@RequestParam(defaultValue = "ASSETS") String sort,
                                                @RequestParam(defaultValue = "1") int pageNum,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(rankingService.getRankingPage(sort, pageNum, pageSize));
    }

    /**
     * 当前用户在榜上的那一行，名次跟着 sort 维度走，与分页同口径。
     * <p>
     * 榜单页只拿得到当页 20 条，自己排在第几页无从得知，所以单开这条按 userId 直取。
     * 没上榜（从没成交过 / 排在入榜上限之外）返回 data=null，不是错误。
     */
    @GetMapping("/me")
    @Operation(summary = "当前用户的榜单行（未上榜返回 null）")
    public Result<RankingDTO> myRanking(@RequestParam(defaultValue = "ASSETS") String sort,
                                        @CurrentUserId Long userId) {
        return Result.ok(rankingService.findRanking(userId, sort));
    }

    /**
     * 用户详情：榜单行 + 当前持仓。
     * <p>
     * 目标用户关了公开开关就 403（本人除外）。这里的 userId 是<b>要看谁</b>，
     * 跟登录态那个是两回事——门控就是拿这两个比出来的，缺一不可。
     */
    @GetMapping("/users/{targetUserId}")
    @Operation(summary = "排行榜用户详情（对方关闭公开时返回 403）")
    public Result<UserProfileDTO> userProfile(@PathVariable Long targetUserId,
                                              @CurrentUserId Long userId) {
        userProfileService.assertVisible(targetUserId, userId);
        return Result.ok(userProfileService.getProfile(targetUserId));
    }

    /**
     * 用户成交历史分页。
     * 不复用 /api/trades/public 加 userId 参数：那会让人枚举 userId 反查假名；
     * 按人查必须单独走这条路并过隐私门控。
     */
    @GetMapping("/users/{targetUserId}/trades")
    @Operation(summary = "指定用户的成交历史（对方关闭公开时返回 403）")
    public Result<IPage<PublicTradeDTO>> userTrades(@PathVariable Long targetUserId,
                                                    @CurrentUserId Long userId,
                                                    @RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "20") int pageSize) {
        userProfileService.assertVisible(targetUserId, userId);
        return Result.ok(publicTradeService.pageByUser(targetUserId, pageNum, pageSize));
    }

    /**
     * 用户合约仓位历史分页。
     * <p>
     * 跟上面那条成交历史是两种粒度：那个是一笔笔委托，这个把同一仓位的开/加/平合成一条生意，
     * 看的是"这仓最后赚没赚、回报率多少"。门控同上，一条都不能少。
     */
    @GetMapping("/users/{targetUserId}/position-history")
    @Operation(summary = "指定用户的合约仓位历史（对方关闭公开时返回 403）")
    public Result<IPage<PositionHistoryDTO>> userPositionHistory(@PathVariable Long targetUserId,
                                                                 @CurrentUserId Long userId,
                                                                 @RequestParam(defaultValue = "1") int pageNum,
                                                                 @RequestParam(defaultValue = "20") int pageSize) {
        userProfileService.assertVisible(targetUserId, userId);
        return Result.ok(positionHistoryService.page(targetUserId, null, pageNum, pageSize));
    }
}
