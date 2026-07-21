package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.Comment;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CommentMapper;
import com.mawai.wiibsim.mapper.NotificationMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除回归。权限是"本人删自己的、管理员删任何人的"，但两者删法不同：
 * <ul>
 *   <li>本人自删 → 原文被占位符覆盖，不级联、不清通知（占位符点进去看得到文案，不是死链）</li>
 *   <li>管理员删别人 → 软删，根评论级联带走全部子评论，相关通知一并清掉</li>
 * </ul>
 * 级联只在根评论方向发生——删根要带走它下面的回复，删子评论不能牵连别人。
 */
class CommentDeleteTest {

    private static final long OWNER = 7L;
    private static final long STRANGER = 99L;

    private CommentMapper commentMapper;
    private NotificationMapper notificationMapper;
    private CommentService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        notificationMapper = mock(NotificationMapper.class);
        service = new CommentService(commentMapper, notificationMapper,
                mock(UserMapper.class), mock(StringRedisTemplate.class), mock(NotificationPushService.class));
    }

    private void existing(long id, Long rootId, long authorId, int status) {
        existing(id, rootId, authorId, status, false);
    }

    private void existing(long id, Long rootId, long authorId, int status, boolean selfDeleted) {
        Comment c = new Comment();
        c.setId(id);
        c.setRootId(rootId);
        c.setUserId(authorId);
        c.setStatus(status);
        c.setSelfDeleted(selfDeleted);
        when(commentMapper.selectById(id)).thenReturn(c);
    }

    // ==================== 权限 ====================

    @Test
    void ownerCanDelete() {
        assertTrue(CommentService.canDelete(OWNER, OWNER, false));
    }

    @Test
    void adminCanDeleteOthers() {
        assertTrue(CommentService.canDelete(OWNER, STRANGER, true));
    }

    @Test
    void strangerCannot() {
        assertFalse(CommentService.canDelete(OWNER, STRANGER, false));
    }

    @Test
    void strangerDeleteIsRejected() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        assertThrows(BizException.class, () -> service.delete(100L, STRANGER, false));

        verify(commentMapper, never()).softDelete(anyLong());
        verify(commentMapper, never()).selfDelete(anyLong(), anyString());
    }

    @Test
    void deletingAlreadyDeletedIsRejected() {
        existing(100L, null, OWNER, Comment.STATUS_DELETED);

        assertThrows(BizException.class, () -> service.delete(100L, OWNER, false));
    }

    // ==================== 本人自删：占位符覆盖，不级联不清通知 ====================

    @Test
    void selfDeleteOverwritesContentInsteadOfSoftDeleting() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, OWNER, false);

        verify(commentMapper).selfDelete(100L, CommentService.DELETED_PLACEHOLDER);
        verify(commentMapper, never()).softDelete(anyLong());
    }

    @Test
    void selfDeleteKeepsNotifications() {
        // 自删留占位符，点通知进去看得到"该留言已删除"，不是死链，没必要抹掉通知
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, OWNER, false);

        verify(notificationMapper, never()).deleteByCommentId(anyLong());
        verify(notificationMapper, never()).deleteByRootId(anyLong());
    }

    @Test
    void selfDeleteDoesNotCascadeToChildren() {
        // 楼主删自己的主楼只抹自己那段话，底下别人的回复不该跟着消失
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, OWNER, false);

        verify(commentMapper, never()).softDeleteChildren(anyLong());
    }

    @Test
    void repeatedSelfDeleteIsIdempotent() {
        // 并发双击不该弹"评论不存在"，也不该重复写占位符
        existing(100L, null, OWNER, Comment.STATUS_OK, true);

        service.delete(100L, OWNER, false);

        verify(commentMapper, never()).selfDelete(anyLong(), anyString());
    }

    // ==================== 管理员删别人：软删 + 级联 + 清通知 ====================

    @Test
    void adminDeletesOthersComment() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, STRANGER, true);

        verify(commentMapper).softDelete(100L);
    }

    @Test
    void adminDeletingRootCascadesToChildren() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, STRANGER, true);

        verify(commentMapper).softDelete(100L);
        verify(commentMapper).softDeleteChildren(100L);
    }

    @Test
    void adminDeletingChildDoesNotCascade() {
        existing(200L, 100L, OWNER, Comment.STATUS_OK);

        service.delete(200L, STRANGER, true);

        verify(commentMapper).softDelete(200L);
        // 200 是子评论，softDeleteChildren(200) 会误伤——虽然它没有子评论，但语义上就不该调
        verify(commentMapper, never()).softDeleteChildren(anyLong());
    }

    @Test
    void adminDeleteAlsoClearsItsNotifications() {
        // 不清的话，点那条通知只会看到"评论不存在"，而且这条死链永远留在信封里
        existing(200L, 100L, OWNER, Comment.STATUS_OK);

        service.delete(200L, STRANGER, true);

        verify(notificationMapper).deleteByCommentId(200L);
        verify(notificationMapper, never()).deleteByRootId(anyLong());   // 子评论没有下级
    }

    @Test
    void adminDeletingRootAlsoClearsChildrenNotifications() {
        // 删主楼会级联软删它下面所有回复，指向那些回复的通知也要一起清
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, STRANGER, true);

        verify(notificationMapper).deleteByCommentId(100L);
        verify(notificationMapper).deleteByRootId(100L);
    }
}
