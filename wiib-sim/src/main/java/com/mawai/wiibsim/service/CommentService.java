package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.CommentDTO;
import com.mawai.wiibcommon.entity.Comment;
import com.mawai.wiibcommon.entity.CommentNotification;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CommentMapper;
import com.mawai.wiibsim.mapper.CommentNotificationMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 留言板。全站一个板，两层结构：{@code rootId} 为空是根评论，否则是该根下的子评论；
 * 回复子评论时 rootId 仍指向根，只用 replyToUserId 记被回复者，所以永远不会有第三层。
 * <p>
 * 三个约定值得先说清楚：
 * <ul>
 *   <li>赞踩去重靠 Redis Set，DB 里只留计数、不落记录表。键过期即遗忘，留言板能接受</li>
 *   <li>禁言比的是时间，到期自动解禁，不需要定时任务扫表清 muted_until</li>
 *   <li>通知只在"赞"和"回复"两种事件上产生，踩不通知，自己操作自己也不通知</li>
 * </ul>
 * 写方法都直接由 Controller 调用，没有 @Transactional 方法互相自调用的情况——
 * 那种写法会绕过 Spring 代理让事务静默失效。
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final int MAX_LEN = 500;
    private static final int MAX_PER_MINUTE = 3;
    /** 列表页每条根评论带几条子评论预览 */
    private static final int PREVIEW_SIZE = 2;
    private static final int MAX_PAGE_SIZE = 50;

    private static final String RATE_PREFIX = "comment:rate:";
    private static final String VOTE_PREFIX = "comment:voted:";
    private static final Duration VOTE_TTL = Duration.ofDays(30);
    /** 永久禁言的存法：一个远到不用管的日期，省掉给 muted_until 单独加"是否永久"标志位 */
    private static final LocalDateTime PERMANENT_MUTE = LocalDateTime.of(2099, 1, 1, 0, 0);

    private final CommentMapper commentMapper;
    private final CommentNotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate redis;
    private final NotificationPushService pushService;

    // ---------------- 纯校验（静态，方便直接单测） ----------------

    /** 禁言到期即自动解禁 */
    public static void assertNotMuted(LocalDateTime mutedUntil) {
        if (mutedUntil != null && mutedUntil.isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.COMMENT_MUTED);
        }
    }

    /** 前端也校验，但前端校验绕得过去，后端必须再拦一道 */
    public static void assertContentValid(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_LEN) {
            throw new BizException(ErrorCode.COMMENT_CONTENT_INVALID);
        }
    }

    /** 赞和踩共用一个 Set：一人对一条评论只能表态一次，投过赞就不能再投踩 */
    public static String voteKey(long commentId) {
        return VOTE_PREFIX + commentId;
    }

    /** 本人删自己的，或管理员删任何人的 */
    public static boolean canDelete(long authorId, long operatorId, boolean isAdmin) {
        return isAdmin || authorId == operatorId;
    }

    /**
     * 禁言到期时间。负数=永久；0 算出来正好是"此刻到期"，
     * 也就是解禁——管理员误封想撤销时传 0 即可，不用单开一个解禁接口。
     */
    static LocalDateTime muteUntil(int days) {
        return days < 0 ? PERMANENT_MUTE : LocalDateTime.now().plusDays(days);
    }

    // ---------------- 写 ----------------

    /**
     * 发表评论或回复。
     * <p>
     * 回复时 rootId 传所属根评论ID（不是被回复的那条子评论ID），replyToUserId 传被回复者，
     * 前端据此展示"回复 @xxx"。评论落库与通知插入在同一事务里。
     */
    @Transactional(rollbackFor = Exception.class)
    public Comment post(long userId, String content, Long rootId, Long replyToUserId) {
        User user = userMapper.selectById(userId);
        assertNotMuted(user == null ? null : user.getMutedUntil());
        assertContentValid(content);
        assertRateOk(userId);

        if (rootId != null) {
            // 只认活着的根评论：指向子评论会长出第三层，指向已删的会挂在坟头上
            Comment root = commentMapper.selectById(rootId);
            if (root == null || root.getStatus() != Comment.STATUS_OK || root.getRootId() != null) {
                throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
            }
        }

        // 根评论没有"回复谁"，客户端硬塞也不认，免得凭空生出通知
        Long replyTo = rootId == null ? null : replyToUserId;

        Comment c = new Comment();
        c.setUserId(userId);
        c.setRootId(rootId);
        c.setReplyToUserId(replyTo);
        c.setContent(content.trim());
        c.setLikeCount(0);
        c.setDislikeCount(0);
        c.setStatus(Comment.STATUS_OK);
        commentMapper.insert(c);

        if (replyTo != null && replyTo.longValue() != userId) {
            // 跳转目标是我这条新回复：对方点通知要看到"谁回了我什么"
            insertNotification(replyTo, userId, CommentNotification.TYPE_REPLY, c.getId());
        }
        return c;
    }

    /**
     * 赞或踩。先 SADD 抢去重位再改计数：抢不到说明表过态，直接拒。
     * <p>
     * 顺序不能反。反过来（先改 DB 再记 Redis）在并发下会放过重复投票；
     * 现在这个顺序的代价只是"DB 事务失败时白占一票"，留言板场景下认了。
     */
    @Transactional(rollbackFor = Exception.class)
    public void vote(long userId, long commentId, boolean like) {
        Comment c = commentMapper.selectById(commentId);
        if (c == null || c.getStatus() != Comment.STATUS_OK) {
            // 先校验再 SADD，否则这一票会被永久记在一条已删评论上
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }

        String key = voteKey(commentId);
        Long added = redis.opsForSet().add(key, String.valueOf(userId));
        if (added == null || added == 0L) {
            throw new BizException(ErrorCode.COMMENT_ALREADY_VOTED);
        }
        redis.expire(key, VOTE_TTL);

        commentMapper.incrementVote(commentId, like);

        // 踩不通知；赞自己的也不通知
        if (like && c.getUserId().longValue() != userId) {
            insertNotification(c.getUserId(), userId, CommentNotification.TYPE_LIKE, commentId);
        }
    }

    /** 软删。删根评论时一条 UPDATE 级联软删其全部子评论；删子评论不牵连别人。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long commentId, long operatorId, boolean isAdmin) {
        Comment c = commentMapper.selectById(commentId);
        if (c == null || c.getStatus() != Comment.STATUS_OK) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        if (!canDelete(c.getUserId(), operatorId, isAdmin)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        commentMapper.softDelete(commentId);
        if (c.getRootId() == null) {
            commentMapper.softDeleteChildren(commentId);
        }
    }

    /** 禁言（管理员）。改不到行说明 userId 打错了，得让管理员看见，别静默成功。 */
    public void mute(long userId, int days) {
        if (userMapper.updateMutedUntil(userId, muteUntil(days)) == 0) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
    }

    // ---------------- 读 ----------------

    /** 根评论分页：每条带子评论总数、最早 2 条预览、当前用户的表态 */
    public List<CommentDTO> listRoots(Long userId, int page, int size) {
        int limit = clampSize(size);
        int offset = offsetOf(page, limit);
        List<CommentDTO> roots = commentMapper.selectRootPage(offset, limit);
        if (roots.isEmpty()) {
            return roots;
        }

        // 预览查询内部会用同样的 offset/limit 复算一遍分页窗口，两边必须传一样的值
        Map<Long, List<CommentDTO>> previewsByRoot = commentMapper
                .selectChildPreviews(offset, limit, PREVIEW_SIZE)
                .stream()
                .collect(Collectors.groupingBy(CommentDTO::getRootId));
        for (CommentDTO root : roots) {
            root.setChildren(previewsByRoot.getOrDefault(root.getId(), List.of()));
        }

        fillVoted(userId, roots);
        return roots;
    }

    /** 子评论分页（前端点"查看全部 N 条回复"用） */
    public List<CommentDTO> listChildren(Long userId, long rootId, int page, int size) {
        int limit = clampSize(size);
        List<CommentDTO> children = commentMapper.selectChildren(rootId, offsetOf(page, limit), limit);
        fillVoted(userId, children);
        return children;
    }

    /**
     * 聚焦视图：给一个评论ID，返回它所属的根评论 + 该根下的全部子评论。
     * <p>
     * 通知跳转靠它。评论列表是分页的，没法算"目标在第几页第几条"；直接把整组捞出来，
     * 无论目标是根评论还是三十页之前的某条回复，都是一次查询命中。
     */
    public CommentDTO context(Long userId, long commentId) {
        Comment c = commentMapper.selectById(commentId);
        if (c == null || c.getStatus() != Comment.STATUS_OK) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 目标是子评论就上浮到根。根被删时子评论已级联软删，所以能走到这儿根一定还在
        long rootId = c.getRootId() == null ? c.getId() : c.getRootId();

        CommentDTO root = commentMapper.selectDtoById(rootId);
        if (root == null) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND);
        }
        // 聚焦视图要全量子评论，LIMIT 给到 int 上限等于不限
        root.setChildren(commentMapper.selectChildren(rootId, 0, Integer.MAX_VALUE));

        fillVoted(userId, List.of(root));
        return root;
    }

    // ---------------- 内部 ----------------

    private void insertNotification(long receiverId, long actorId, int type, long commentId) {
        CommentNotification n = new CommentNotification();
        n.setUserId(receiverId);
        n.setActorId(actorId);
        n.setType(type);
        n.setCommentId(commentId);
        n.setIsRead(false);
        notificationMapper.insert(n);
        // 落库归落库，推送等事务提交后再发：回滚了还推的话对方角标加了、点开却是空的
        pushService.pushUnreadAfterCommit(receiverId);
    }

    /** 每分钟 3 条。计数键 TTL 60s，滑不滑窗对留言板无所谓。 */
    private void assertRateOk(long userId) {
        String key = RATE_PREFIX + userId;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, Duration.ofMinutes(1));
        }
        if (n != null && n > MAX_PER_MINUTE) {
            throw new BizException(ErrorCode.COMMENT_TOO_FREQUENT);
        }
    }

    /**
     * 填当前用户对本页各评论的表态，前端据此置灰按钮。
     * <p>
     * 游客直接全 false 且完全不碰 Redis。登录用户是每条评论一次 SISMEMBER——
     * 一条评论一个 Set，天然没法批量查（SMISMEMBER 是一个 key 查多个 member，正好反过来）。
     * 一页最多 20 根 + 40 条预览，本地 Redis 这点往返不值得为它上 pipeline。
     */
    private void fillVoted(Long userId, List<CommentDTO> comments) {
        String member = userId == null ? null : String.valueOf(userId);
        for (CommentDTO c : comments) {
            markVoted(c, member);
            if (c.getChildren() != null) {
                for (CommentDTO child : c.getChildren()) {
                    markVoted(child, member);
                }
            }
        }
    }

    private void markVoted(CommentDTO dto, String member) {
        if (member == null) {
            dto.setVoted(false);
            return;
        }
        dto.setVoted(Boolean.TRUE.equals(redis.opsForSet().isMember(voteKey(dto.getId()), member)));
    }

    /** 手搓请求可能传 0 或负数，兜住免得算出负 OFFSET 让 SQL 直接报错 */
    private static int clampSize(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    private static int offsetOf(int page, int size) {
        return Math.max(0, page - 1) * size;
    }
}
