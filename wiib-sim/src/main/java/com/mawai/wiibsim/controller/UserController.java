package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.AccountResetService;
import com.mawai.wiibsim.service.AssetSnapshotService;
import com.mawai.wiibsim.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/**
 * 用户Controller
 */
@Tag(name = "用户接口")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AssetSnapshotService assetSnapshotService;
    private final AccountResetService accountResetService;

    @Data
    public static class ResetRequest {
        /** 必须逐字等于自己的用户名，前端弹窗强制输入，防误点 */
        private String confirmUsername;
    }

    @PostMapping("/reset")
    @Operation(summary = "重置账户到初始状态（清空交易与游戏数据，每周一次）")
    public Result<Void> resetAccount(@CurrentUserId Long userId, @RequestBody ResetRequest request) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        accountResetService.resetWithGuard(userId, user.getUsername(), request.getConfirmUsername());
        return Result.ok(null);
    }

    @GetMapping("/portfolio")
    @Operation(summary = "获取用户资产概览")
    public Result<UserDTO> getUserPortfolio(@CurrentUserId Long userId) {
        return Result.ok(userService.getUserPortfolio(userId));
    }

    @GetMapping("/asset-realtime")
    @Operation(summary = "获取用户实时资产快照(含日收益)")
    public Result<AssetSnapshotDTO> getRealtimeSnapshot(@CurrentUserId Long userId) {
        return Result.ok(assetSnapshotService.getRealtimeSnapshot(userId));
    }

    @GetMapping("/asset-history")
    @Operation(summary = "获取用户资产历史快照(含日收益)")
    public Result<List<AssetSnapshotDTO>> getAssetHistory(@CurrentUserId Long userId, @RequestParam(defaultValue = "30") int days) {
        return Result.ok(assetSnapshotService.getHistory(userId, days));
    }

    /** 首页月度盈亏网格：一次拿一个月，翻月就再问一次，不用为了看三月去换算 days */
    @GetMapping("/asset-daily")
    @Operation(summary = "获取用户指定月份的逐日资产快照(含日收益)")
    public Result<List<AssetSnapshotDTO>> getAssetDaily(@CurrentUserId Long userId, @RequestParam String month) {
        return Result.ok(assetSnapshotService.getMonthly(userId, YearMonth.parse(month)));
    }

    @GetMapping("/category-averages")
    @Operation(summary = "各分类累计盈亏排名百分比")
    public Result<CategoryAveragesDTO> getCategoryAverages(@CurrentUserId Long userId) {
        return Result.ok(assetSnapshotService.getCategoryAverages(userId));
    }

    @Data
    public static class ProfilePublicRequest {
        /** true=允许别人看自己的持仓与交易历史 */
        private Boolean profilePublic;
    }

    @GetMapping("/profile-public")
    @Operation(summary = "查询自己的详情页公开开关")
    public Result<Boolean> getProfilePublic(@CurrentUserId Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        // 列是 NOT NULL DEFAULT TRUE，读到 null 只可能是实体没映上；开关的语义默认是开
        return Result.ok(!Boolean.FALSE.equals(user.getProfilePublic()));
    }

    @PostMapping("/profile-public")
    @Operation(summary = "设置自己的详情页公开开关（关掉后别人点不进你的持仓与交易历史，仍照常上排行榜）")
    public Result<Void> setProfilePublic(@CurrentUserId Long userId, @RequestBody ProfilePublicRequest request) {
        if (request.getProfilePublic() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        // 不能用 updateById 整行写回：User 的资金字段是 updateStrategy=NEVER，
        // 但 muted_until 这类非资金列会被读取时刻的旧值覆盖掉。只更这一列
        userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getProfilePublic, request.getProfilePublic())
                .update();
        return Result.ok(null);
    }

    @Data
    public static class LangRequest {
        /** AgentLang 的码：zh / en，别的值一律拒 */
        private String lang;
    }

    /** agent 提示词语言：只在配置页改，与界面语言（前端 localStorage）互不影响 */
    @GetMapping("/lang")
    @Operation(summary = "读 agent 提示词语言（zh/en）")
    public Result<String> getLang(@CurrentUserId Long userId) {
        return Result.ok(AgentLang.of(userService.getById(userId).getLang()).code());
    }

    @PutMapping("/lang")
    @Operation(summary = "设置 agent 提示词语言（zh/en，只影响后端 AI 的提示词与回答）")
    public Result<Void> setLang(@CurrentUserId Long userId, @RequestBody LangRequest request) {
        AgentLang lang = AgentLang.find(request.getLang())
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_ERROR));
        // 同 setProfilePublic：不能 updateById 整行写回，只更这一列
        userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getLang, lang.code())
                .update();
        return Result.ok(null);
    }
}
