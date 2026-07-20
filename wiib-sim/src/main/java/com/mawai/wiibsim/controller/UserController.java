package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.AssetSnapshotDTO;
import com.mawai.wiibcommon.dto.CategoryAveragesDTO;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
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

    @GetMapping("/category-averages")
    @Operation(summary = "获取各分类日收益排名百分比")
    public Result<CategoryAveragesDTO> getCategoryAverages(@CurrentUserId Long userId, @RequestParam(defaultValue = "30") int days) {
        return Result.ok(assetSnapshotService.getCategoryAverages(userId, days));
    }
}
