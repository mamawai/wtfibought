package com.mawai.wiibsim.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.annotation.RequireAdmin;
import com.mawai.wiibcommon.dto.CommentDTO;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 留言板接口。
 * <p>
 * 三个 GET 允许游客访问（{@code SaTokenConfig} 里把 /api/comments/** 放行了），写操作靠
 * {@code @CurrentUserId} / {@code @RequireAdmin} 自己校验登录态——它们取不到 loginId
 * 会抛 NotLoginException，由全局处理器转 401。
 */
@Tag(name = "留言板")
@RestController
@RequestMapping("/api/comments")
@RequiredArgsConstructor
public class CommentController {

    /** 管理员固定为 userId=1，与 RequireAdminAspect 同一口径 */
    private static final long ADMIN_USER_ID = 1L;

    private final CommentService commentService;

    /**
     * 可选登录：拿得到就返回 userId，游客返回 null。
     * <p>
     * 不能用 {@code @CurrentUserId}——那个注解没有 required 属性，未登录时解析器直接
     * 抛 NotLoginException 转 401，而留言板必须让没登录的人也能看。
     */
    private static Long currentUserIdOrNull() {
        Object id = StpUtil.getLoginIdDefaultNull();
        return id == null ? null : Long.valueOf(id.toString());
    }

    @Data
    public static class PostRequest {
        private String content;
        /** 回复时传所属根评论ID；发根评论传 null */
        private Long rootId;
        /** 回复时传被回复者，用于展示"回复 @xxx" */
        private Long replyToUserId;
    }

    @Data
    public static class MuteRequest {
        private Long userId;
        /** 禁言天数：-1=永久，0=立即解禁 */
        private Integer days;
    }

    @GetMapping
    @Operation(summary = "根评论分页（时间倒序，带子评论预览与本人表态）")
    public Result<List<CommentDTO>> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return Result.ok(commentService.listRoots(currentUserIdOrNull(), page, size));
    }

    @GetMapping("/{rootId}/children")
    @Operation(summary = "子评论分页")
    public Result<List<CommentDTO>> children(@PathVariable long rootId,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return Result.ok(commentService.listChildren(currentUserIdOrNull(), rootId, page, size));
    }

    @GetMapping("/context/{commentId}")
    @Operation(summary = "聚焦视图：该评论所属根评论+其全部子评论（通知跳转用）")
    public Result<CommentDTO> context(@PathVariable long commentId) {
        return Result.ok(commentService.context(currentUserIdOrNull(), commentId));
    }

    @PostMapping
    @Operation(summary = "发表评论或回复")
    public Result<Long> post(@CurrentUserId Long userId, @RequestBody PostRequest req) {
        return Result.ok(commentService.post(userId, req.getContent(),
                req.getRootId(), req.getReplyToUserId()).getId());
    }

    @PostMapping("/{id}/vote")
    @Operation(summary = "赞或踩（一人对一条只能表态一次）")
    public Result<Void> vote(@CurrentUserId Long userId, @PathVariable long id,
                             @RequestParam String type) {
        // 不认识的 type 必须拒掉：默认当成踩的话，前端一个拼写错误就把赞变成了踩
        boolean like = "like".equals(type);
        if (!like && !"dislike".equals(type)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        commentService.vote(userId, id, like);
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除评论（本人或管理员）")
    public Result<Void> delete(@CurrentUserId Long userId, @PathVariable long id) {
        commentService.delete(id, userId, userId.longValue() == ADMIN_USER_ID);
        return Result.ok(null);
    }

    @PostMapping("/mute")
    @RequireAdmin
    @Operation(summary = "禁言用户（管理员）")
    public Result<Void> mute(@RequestBody MuteRequest req) {
        if (req.getUserId() == null || req.getDays() == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        commentService.mute(req.getUserId(), req.getDays());
        return Result.ok(null);
    }
}
