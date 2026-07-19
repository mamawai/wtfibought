package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.WalletTransferRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.WalletTransfer;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 双钱包划转：余额钱包（交易/全仓保证金池） ↔ 游戏钱包（娱乐，与全仓风险隔离） */
@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final UserService userService;
    private final CrossMarginService crossMarginService;

    @PostMapping("/transfer")
    public Result<Map<String, Object>> transfer(@CurrentUserId Long userId,
                                                @RequestBody WalletTransferRequest request) {
        User user = userService.getById(userId);
        if (user == null) throw new BizException(ErrorCode.USER_NOT_FOUND);
        if (Boolean.TRUE.equals(user.getIsBankrupt())) throw new BizException(ErrorCode.USER_BANKRUPT);

        if (WalletTransfer.TO_GAME.equals(request.getDirection())) {
            // 转出=全仓池流出：转到踩维持保证金线的直接拒（转出后下个tick就爆仓，提醒解决不了）
            crossMarginService.assertOutflowAllowed(userId, request.getAmount());
            userService.transferToGame(userId, request.getAmount());
        } else if (WalletTransfer.TO_BALANCE.equals(request.getDirection())) {
            userService.transferToBalance(userId, request.getAmount());
        } else {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        User updated = userService.getById(userId);
        return Result.ok(Map.of(
                "balance", updated.getBalance(),
                "gameBalance", updated.getGameBalance() == null ? BigDecimal.ZERO : updated.getGameBalance()));
    }

    /**
     * 划转影响预览。无全仓仓位或转入方向：restricted=false 随便转；
     * 有全仓仓位的转出：给出转出后净值/各仓位新预估强平价/最大可转金额，踩线则 allowed=false。
     */
    @GetMapping("/transfer/preview")
    public Result<Map<String, Object>> preview(@CurrentUserId Long userId,
                                               @RequestParam String direction,
                                               @RequestParam BigDecimal amount) {
        Map<String, Object> resp = new HashMap<>();
        boolean outflow = WalletTransfer.TO_GAME.equals(direction);
        if (!outflow || !crossMarginService.hasCrossPositions(userId)) {
            resp.put("restricted", false);
            resp.put("allowed", true);
            return Result.ok(resp);
        }

        CrossMarginService.CrossAccount account = crossMarginService.snapshot(userId);
        BigDecimal equityAfter = account.equity().subtract(amount);
        boolean allowed = equityAfter.compareTo(account.maintenanceMargin()) > 0;

        // 划转后的账户视角：余额少了 amount，其余不变；各仓位强平价按此重估
        CrossMarginService.CrossAccount after = new CrossMarginService.CrossAccount(
                account.balance().subtract(amount), account.unrealizedPnl(), account.usedMargin(),
                account.pendingReserved(), account.maintenanceMargin(), account.positions());
        List<Map<String, Object>> positions = account.positions().stream()
                .<Map<String, Object>>map(pos -> Map.of(
                        "positionId", (Object) pos.getId(),
                        "symbol", pos.getSymbol(),
                        "side", pos.getSide(),
                        "estLiqPrice", crossMarginService.estimateLiqPrice(pos, after)))
                .toList();

        resp.put("restricted", true);
        resp.put("allowed", allowed);
        resp.put("maxTransferable", crossMarginService.maxOutflow(userId));
        resp.put("equityAfter", equityAfter);
        resp.put("maintenanceMargin", account.maintenanceMargin());
        resp.put("positions", positions);
        return Result.ok(resp);
    }
}
