package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.dto.NotificationDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.mapper.CommentNotificationMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 评论通知。全部要求登录（未挂在 SaToken 放行名单里，游客本来也没有通知）。
 */
@Tag(name = "评论通知")
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final CommentNotificationMapper notificationMapper;

    @GetMapping
    @Operation(summary = "最近50条通知（前端按 type+commentId 合并展示）")
    public Result<List<NotificationDTO>> recent(@CurrentUserId Long userId) {
        return Result.ok(notificationMapper.recent(userId));
    }

    @GetMapping("/unread")
    @Operation(summary = "未读数（信封角标）")
    public Result<Long> unread(@CurrentUserId Long userId) {
        return Result.ok(notificationMapper.countUnread(userId));
    }

    @PostMapping("/read-all")
    @Operation(summary = "全部标已读（点开信封面板即调）")
    public Result<Void> readAll(@CurrentUserId Long userId) {
        notificationMapper.markAllRead(userId);
        return Result.ok(null);
    }
}
