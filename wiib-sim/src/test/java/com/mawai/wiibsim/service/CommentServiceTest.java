package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.Comment;
import com.mawai.wiibcommon.entity.Notification;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CommentMapper;
import com.mawai.wiibsim.mapper.NotificationMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发表评论回归。锁死四件容易想当然写错的事：
 * <ol>
 *   <li>禁言到期即自动解禁——靠比时间，不靠定时任务扫表清 muted_until</li>
 *   <li>长度/空白后端必须再拦一道，前端校验绕得过去</li>
 *   <li>回复通知的接收者是被回复者，自己回自己不发（否则自言自语刷满自己的信封）</li>
 *   <li>rootId 只认活着的根评论，指向子评论会变成三层嵌套</li>
 * </ol>
 */
class CommentServiceTest {

    private static final long ME = 7L;
    private static final long OTHER = 9L;
    private static final long NEW_COMMENT_ID = 1001L;

    private CommentMapper commentMapper;
    private NotificationMapper notificationMapper;
    private UserMapper userMapper;
    private ValueOperations<String, String> valueOps;
    private CommentService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        userMapper = mock(UserMapper.class);

        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);   // 默认没超频

        when(userMapper.selectById(any())).thenReturn(new User());   // 默认未禁言
        // 默认被回复者是这串里的人；"回复不相干的人"单独有用例把它翻成 false
        when(commentMapper.existsInThread(anyLong(), anyLong())).thenReturn(true);
        // insert 后 MyBatis-Plus 会把自增主键回填进实体，mock 得自己补上，否则通知拿不到跳转目标
        when(commentMapper.insert(any(Comment.class))).thenAnswer(inv -> {
            inv.getArgument(0, Comment.class).setId(NEW_COMMENT_ID);
            return 1;
        });

        service = new CommentService(commentMapper, notificationMapper, userMapper, redis, mock(NotificationPushService.class));
    }

    private static Comment root(long id) {
        Comment c = new Comment();
        c.setId(id);
        c.setUserId(OTHER);
        c.setRootId(null);
        c.setStatus(Comment.STATUS_OK);
        return c;
    }

    @Test
    void mutedUserRejected() {
        assertThrows(BizException.class,
                () -> CommentService.assertNotMuted(LocalDateTime.now().plusDays(1)));
    }

    @Test
    void expiredMuteAutoReleases() {
        assertDoesNotThrow(() -> CommentService.assertNotMuted(LocalDateTime.now().minusSeconds(1)));
        assertDoesNotThrow(() -> CommentService.assertNotMuted(null));
    }

    @Test
    void contentValidated() {
        assertThrows(BizException.class, () -> CommentService.assertContentValid("   "));
        assertThrows(BizException.class, () -> CommentService.assertContentValid("a".repeat(501)));
        assertDoesNotThrow(() -> CommentService.assertContentValid("正常评论"));
    }

    @Test
    void mutedUserCannotPost() {
        User muted = new User();
        muted.setMutedUntil(LocalDateTime.now().plusDays(3));
        when(userMapper.selectById(any())).thenReturn(muted);

        assertThrows(BizException.class, () -> service.post(ME, "说点什么", null, null));
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void rateLimitRejectsFourthPerMinute() {
        when(valueOps.increment(anyString())).thenReturn(4L);

        assertThrows(BizException.class, () -> service.post(ME, "刷屏", null, null));
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void replyNotifiesTargetUser() {
        when(commentMapper.selectById(100L)).thenReturn(root(100L));

        service.post(ME, "回复一下", 100L, OTHER);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        Notification n = captor.getValue();
        assertEquals(OTHER, n.getUserId());                            // 接收者=被回复的人
        assertEquals(ME, n.getActorId());
        assertEquals(Notification.TYPE_REPLY, n.getType());
        assertEquals(NEW_COMMENT_ID, n.getCommentId());                // 跳转目标=我这条新回复
        assertFalse(n.getIsRead());
    }

    @Test
    void selfReplyDoesNotNotify() {
        when(commentMapper.selectById(100L)).thenReturn(root(100L));

        service.post(ME, "自己补一句", 100L, ME);

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void rootCommentNeverNotifies() {
        // 根评论没有"回复谁"，客户端硬塞 replyToUserId 也不该产生通知
        service.post(ME, "开个新话题", null, OTHER);

        verify(notificationMapper, never()).insert(any(Notification.class));
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getReplyToUserId());
    }

    @Test
    void rejectsReplyTargetingChildComment() {
        Comment child = new Comment();
        child.setId(200L);
        child.setRootId(100L);            // 它自己就是子评论，拿它当 rootId 会变成三层
        child.setStatus(Comment.STATUS_OK);
        when(commentMapper.selectById(200L)).thenReturn(child);

        assertThrows(BizException.class, () -> service.post(ME, "回复子评论", 200L, OTHER));
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void rejectsReplyToDeletedRoot() {
        Comment deleted = root(100L);
        deleted.setStatus(Comment.STATUS_DELETED);
        when(commentMapper.selectById(100L)).thenReturn(deleted);

        assertThrows(BizException.class, () -> service.post(ME, "回复已删", 100L, OTHER));
    }

    @Test
    void permanentMuteStoresYear2099() {
        assertEquals(2099, CommentService.muteUntil(-1).getYear());
    }

    @Test
    void absurdMuteDaysFallBackToPermanent() {
        // plusDays(Integer.MAX_VALUE) 算出公元 5881637 年，Java 不报错但超出 PG timestamp
        // 上限(294276年)，写库直接 500。超过 10 年一律按永久处理
        assertEquals(2099, CommentService.muteUntil(Integer.MAX_VALUE).getYear());
        assertEquals(2099, CommentService.muteUntil(365 * 100).getYear());
    }

    @Test
    void rejectsReplyToOutsiderOfThread() {
        // 不校验的话，随便填个 userId 就能给任意用户推一条"XX 回复了你"，
        // 点进去却是个跟他毫无关系的话题
        when(commentMapper.selectById(100L)).thenReturn(root(100L));
        when(commentMapper.existsInThread(100L, 12345L)).thenReturn(false);

        assertThrows(BizException.class, () -> service.post(ME, "骚扰", 100L, 12345L));

        verify(commentMapper, never()).insert(any(Comment.class));
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    @Test
    void failedPostDoesNotBurnRateQuota() {
        // 限流必须排在校验之后：回复一条已删评论本来就该失败，不该白扣一次额度
        when(commentMapper.selectById(100L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.post(ME, "回复已删", 100L, OTHER));

        verify(valueOps, never()).increment(anyString());
    }

    @Test
    void zeroDaysMuteIsImmediateRelease() {
        // days=0 算出来就是"此刻到期"，等于解禁，不用另开一个解禁接口
        assertFalse(CommentService.muteUntil(0).isAfter(LocalDateTime.now()));
        assertDoesNotThrow(() -> CommentService.assertNotMuted(CommentService.muteUntil(0)));
    }

    @Test
    void muteWritesOnlyMutedUntil() {
        when(userMapper.updateMutedUntil(eq(OTHER), any())).thenReturn(1);

        service.mute(OTHER, 7);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userMapper).updateMutedUntil(eq(OTHER), captor.capture());
        assertTrue(captor.getValue().isAfter(LocalDateTime.now().plusDays(6)));
    }

    @Test
    void mutingUnknownUserIsReported() {
        // 改不到行=userId 打错了，静默成功会让管理员以为封住了
        when(userMapper.updateMutedUntil(anyLong(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.mute(12345L, 1));
    }
}
