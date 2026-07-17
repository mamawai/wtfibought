package com.mawai.wiibsim.controller;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.entity.InviteCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.mapper.InviteCodeMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "邀请码管理")
@RestController
@RequestMapping("/api/admin/invite-code")
@RequiredArgsConstructor
@RequireAdmin // 仅管理员(userId=1)可访问
public class InviteCodeController {

    private final InviteCodeMapper inviteCodeMapper;

    @PostMapping("/generate")
    @Operation(summary = "生成邀请码（可配次数和批量个数）")
    public Result<List<InviteCode>> generate(@RequestBody GenerateRequest request) {
        int maxUses = request.getMaxUses() != null ? request.getMaxUses() : 1;
        int count = request.getCount() != null ? request.getCount() : 1;
        if (maxUses < 1 || maxUses > 10000) throw new BizException("次数需在1-10000之间");
        if (count < 1 || count > 100) throw new BizException("批量个数需在1-100之间");
        List<InviteCode> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            InviteCode ic = new InviteCode();
            ic.setCode(RandomUtil.randomStringUpper(8));
            ic.setMaxUses(maxUses);
            ic.setUsedCount(0);
            ic.setEnabled(true);
            inviteCodeMapper.insert(ic);
            created.add(ic);
        }
        return Result.ok(created);
    }

    @GetMapping("/list")
    @Operation(summary = "邀请码列表（按创建倒序）")
    public Result<List<InviteCode>> list() {
        return Result.ok(inviteCodeMapper.selectList(
                new LambdaQueryWrapper<InviteCode>().orderByDesc(InviteCode::getId)));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "作废邀请码")
    public Result<Void> disable(@PathVariable Long id) {
        inviteCodeMapper.disable(id);
        return Result.ok();
    }

    @Data
    public static class GenerateRequest {
        /** 每码最大可用次数，默认1 */
        private Integer maxUses;
        /** 一次生成几个码，默认1 */
        private Integer count;
    }
}
