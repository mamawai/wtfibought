package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.entity.Comment;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CommentMapper;
import com.mawai.wiibsim.mapper.CommentNotificationMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除回归。权限是"本人删自己的、管理员删任何人的"；
 * 级联只在根评论方向发生——删根要带走它下面的回复，删子评论不能牵连别人。
 */
class CommentDeleteTest {

    private static final long OWNER = 7L;
    private static final long STRANGER = 99L;

    private CommentMapper commentMapper;
    private CommentService service;

    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        service = new CommentService(commentMapper, mock(CommentNotificationMapper.class),
                mock(UserMapper.class), mock(StringRedisTemplate.class), mock(NotificationPushService.class));
    }

    private void existing(long id, Long rootId, long authorId, int status) {
        Comment c = new Comment();
        c.setId(id);
        c.setRootId(rootId);
        c.setUserId(authorId);
        c.setStatus(status);
        when(commentMapper.selectById(id)).thenReturn(c);
    }

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
    void deletingRootCascadesToChildren() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, OWNER, false);

        verify(commentMapper).softDelete(100L);
        verify(commentMapper).softDeleteChildren(100L);
    }

    @Test
    void deletingChildDoesNotCascade() {
        existing(200L, 100L, OWNER, Comment.STATUS_OK);

        service.delete(200L, OWNER, false);

        verify(commentMapper).softDelete(200L);
        // 200 是子评论，softDeleteChildren(200) 会误伤——虽然它没有子评论，但语义上就不该调
        verify(commentMapper, never()).softDeleteChildren(anyLong());
    }

    @Test
    void strangerDeleteIsRejected() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        assertThrows(BizException.class, () -> service.delete(100L, STRANGER, false));

        verify(commentMapper, never()).softDelete(anyLong());
    }

    @Test
    void adminDeletesOthersComment() {
        existing(100L, null, OWNER, Comment.STATUS_OK);

        service.delete(100L, STRANGER, true);

        verify(commentMapper).softDelete(100L);
    }

    @Test
    void deletingAlreadyDeletedIsRejected() {
        existing(100L, null, OWNER, Comment.STATUS_DELETED);

        assertThrows(BizException.class, () -> service.delete(100L, OWNER, false));
    }
}
