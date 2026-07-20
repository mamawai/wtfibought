package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.Comment;
import com.mawai.wiibcommon.entity.CommentNotification;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CommentMapper;
import com.mawai.wiibsim.mapper.CommentNotificationMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 赞踩回归。
 * <p>
 * 赞和踩共用一个 Set：一人对一条评论只能表态一次，投过赞就不能再投踩——拆成两个 key
 * 会让人赞完再踩，两个计数一起涨。顺序必须是先 SADD 再动 DB：反过来（先 DB 后 Redis）
 * 在并发下会放过重复投票，那个比"DB 失败丢一票"更糟。
 */
class CommentVoteTest {

    private static final long ME = 7L;
    private static final long AUTHOR = 9L;
    private static final long COMMENT_ID = 42L;

    private CommentMapper commentMapper;
    private CommentNotificationMapper notificationMapper;
    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private CommentService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        notificationMapper = mock(CommentNotificationMapper.class);

        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.add(anyString(), anyString())).thenReturn(1L);   // 默认首次表态

        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment(AUTHOR, Comment.STATUS_OK));

        service = new CommentService(commentMapper, notificationMapper, mock(UserMapper.class), redis, mock(NotificationPushService.class));
    }

    private static Comment comment(long authorId, int status) {
        Comment c = new Comment();
        c.setId(COMMENT_ID);
        c.setUserId(authorId);
        c.setStatus(status);
        return c;
    }

    @Test
    void likeAndDislikeShareOneKey() {
        assertEquals("comment:voted:42", CommentService.voteKey(42L));
    }

    @Test
    void addsToSetBeforeTouchingDb() {
        service.vote(ME, COMMENT_ID, true);

        // 顺序断言：去重位没抢到就不该改计数，所以 SADD 必须在自增之前
        InOrder order = inOrder(setOps, commentMapper);
        order.verify(setOps).add("comment:voted:42", "7");
        order.verify(commentMapper).incrementVote(COMMENT_ID, true);
    }

    @Test
    void secondVoteRejectedAndCountUntouched() {
        when(setOps.add(anyString(), anyString())).thenReturn(0L);   // SADD 返回 0 = 表过态了

        assertThrows(BizException.class, () -> service.vote(ME, COMMENT_ID, false));

        verify(commentMapper, never()).incrementVote(anyLong(), anyBoolean());
        verify(notificationMapper, never()).insert(any(CommentNotification.class));
    }

    @Test
    void likeNotifiesAuthor() {
        service.vote(ME, COMMENT_ID, true);

        ArgumentCaptor<CommentNotification> captor = ArgumentCaptor.forClass(CommentNotification.class);
        verify(notificationMapper).insert(captor.capture());
        CommentNotification n = captor.getValue();
        assertEquals(AUTHOR, n.getUserId());
        assertEquals(ME, n.getActorId());
        assertEquals(CommentNotification.TYPE_LIKE, n.getType());
        assertEquals(COMMENT_ID, n.getCommentId());   // 跳转目标=作者自己那条被赞的评论
    }

    @Test
    void dislikeDoesNotNotify() {
        service.vote(ME, COMMENT_ID, false);

        verify(commentMapper).incrementVote(COMMENT_ID, false);
        verify(notificationMapper, never()).insert(any(CommentNotification.class));
    }

    @Test
    void likingOwnCommentDoesNotNotify() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment(ME, Comment.STATUS_OK));

        service.vote(ME, COMMENT_ID, true);

        verify(commentMapper).incrementVote(COMMENT_ID, true);
        verify(notificationMapper, never()).insert(any(CommentNotification.class));
    }

    @Test
    void votingDeletedCommentRejectedWithoutSpendingVote() {
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment(AUTHOR, Comment.STATUS_DELETED));

        assertThrows(BizException.class, () -> service.vote(ME, COMMENT_ID, true));

        // 校验在 SADD 之前，否则这一票会被永久记在一条已删评论上
        verify(setOps, never()).add(anyString(), anyString());
    }

    @Test
    void voteKeyGetsThirtyDayTtl() {
        // 不设 TTL 这些 Set 会永久堆在 Redis 里；30 天后记录消失是设计上接受的
        service.vote(ME, COMMENT_ID, true);

        verify(redis).expire("comment:voted:42", Duration.ofDays(30));
    }
}
