package com.mawai.wiibsim.service;

import com.mawai.wiibcommon.dto.CommentDTO;
import com.mawai.wiibcommon.entity.Comment;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.mapper.CommentMapper;
import com.mawai.wiibsim.mapper.NotificationMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 列表与聚焦视图回归。三件事：
 * <ol>
 *   <li>游客(userId=null)的 voted 一律 false 且不碰 Redis——每条评论一个 key，白跑一页几十次没意义</li>
 *   <li>子评论预览要挂回各自的根评论，挂错了就会串评论区</li>
 *   <li>聚焦视图传子评论ID也要能返回它所属的根 + 全部子评论，通知跳转就靠这个</li>
 * </ol>
 */
class CommentQueryTest {

    private static final long ME = 7L;

    private CommentMapper commentMapper;
    private StringRedisTemplate redis;
    private SetOperations<String, String> setOps;
    private CommentService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        commentMapper = mock(CommentMapper.class);
        redis = mock(StringRedisTemplate.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(setOps);
        when(setOps.isMember(anyString(), anyString())).thenReturn(false);

        service = new CommentService(commentMapper, mock(NotificationMapper.class),
                mock(UserMapper.class), redis, mock(NotificationPushService.class));
    }

    private static CommentDTO dto(long id, Long rootId) {
        CommentDTO d = new CommentDTO();
        d.setId(id);
        d.setRootId(rootId);
        return d;
    }

    @Test
    void guestVotedAllFalseWithoutTouchingRedis() {
        when(commentMapper.selectRootPage(anyInt(), anyInt())).thenReturn(List.of(dto(1L, null)));
        when(commentMapper.selectChildPreviews(anyList(), anyInt()))
                .thenReturn(List.of(dto(11L, 1L)));

        List<CommentDTO> roots = service.listRoots(null, 1, 20);

        assertFalse(roots.get(0).getVoted());
        assertFalse(roots.get(0).getChildren().get(0).getVoted());
        verify(redis, never()).opsForSet();
        verify(setOps, never()).isMember(anyString(), anyString());
    }

    @Test
    void loggedInVotedReadsRedisPerComment() {
        when(commentMapper.selectRootPage(anyInt(), anyInt())).thenReturn(List.of(dto(1L, null)));
        when(commentMapper.selectChildPreviews(anyList(), anyInt())).thenReturn(List.of());
        when(setOps.isMember("comment:voted:1", "7")).thenReturn(true);

        List<CommentDTO> roots = service.listRoots(ME, 1, 20);

        assertTrue(roots.get(0).getVoted());
    }

    @Test
    void childPreviewsAttachToOwningRoot() {
        when(commentMapper.selectRootPage(anyInt(), anyInt()))
                .thenReturn(List.of(dto(1L, null), dto(2L, null)));
        when(commentMapper.selectChildPreviews(anyList(), anyInt()))
                .thenReturn(List.of(dto(11L, 1L), dto(12L, 1L), dto(21L, 2L)));

        List<CommentDTO> roots = service.listRoots(null, 1, 20);

        assertEquals(2, roots.get(0).getChildren().size());
        assertEquals(11L, roots.get(0).getChildren().get(0).getId());
        assertEquals(1, roots.get(1).getChildren().size());
        assertEquals(21L, roots.get(1).getChildren().get(0).getId());
    }

    @Test
    void rootWithoutChildrenGetsEmptyListNotNull() {
        when(commentMapper.selectRootPage(anyInt(), anyInt())).thenReturn(List.of(dto(1L, null)));
        when(commentMapper.selectChildPreviews(anyList(), anyInt())).thenReturn(List.of());

        List<CommentDTO> roots = service.listRoots(null, 1, 20);

        assertTrue(roots.get(0).getChildren().isEmpty());
    }

    @Test
    void emptyPageSkipsPreviewQuery() {
        when(commentMapper.selectRootPage(anyInt(), anyInt())).thenReturn(List.of());

        assertTrue(service.listRoots(ME, 5, 20).isEmpty());

        verify(commentMapper, never()).selectChildPreviews(anyList(), anyInt());
    }

    @Test
    void contextOnChildReturnsItsRootWithAllChildren() {
        Comment child = new Comment();
        child.setId(200L);
        child.setRootId(100L);
        child.setStatus(Comment.STATUS_OK);
        when(commentMapper.selectById(200L)).thenReturn(child);
        when(commentMapper.selectDtoById(100L)).thenReturn(dto(100L, null));
        when(commentMapper.selectChildren(anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(dto(200L, 100L), dto(201L, 100L)));

        CommentDTO root = service.context(null, 200L);

        assertEquals(100L, root.getId());
        assertEquals(2, root.getChildren().size());
        verify(commentMapper).selectChildren(100L, 0, 200);   // 整串对话，但封顶 200 条
    }

    @Test
    void cappedContextReportsCountItActuallyReturned() {
        // 封顶 200 条时若仍报真实总数，前端 rest=总数-200>0 会冒出"加载更多回复"，
        // 而聚焦视图下点它是从第1页重新分页的，反而把 200 条替换成 10 条
        Comment root = new Comment();
        root.setId(100L);
        root.setStatus(Comment.STATUS_OK);
        when(commentMapper.selectById(100L)).thenReturn(root);

        CommentDTO dto = dto(100L, null);
        dto.setChildCount(250);                       // 真实总数远大于封顶
        when(commentMapper.selectDtoById(100L)).thenReturn(dto);
        when(commentMapper.selectChildren(anyLong(), anyInt(), anyInt()))
                .thenReturn(IntStream.range(0, 200).mapToObj(i -> dto(1000L + i, 100L)).toList());

        CommentDTO result = service.context(null, 100L);

        assertEquals(200, result.getChildren().size());
        assertEquals(200, result.getChildCount());    // 对齐后 rest=0，按钮不出现
    }

    @Test
    void uncappedContextKeepsRealChildCount() {
        Comment root = new Comment();
        root.setId(100L);
        root.setStatus(Comment.STATUS_OK);
        when(commentMapper.selectById(100L)).thenReturn(root);

        CommentDTO dto = dto(100L, null);
        dto.setChildCount(3);
        when(commentMapper.selectDtoById(100L)).thenReturn(dto);
        when(commentMapper.selectChildren(anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of(dto(11L, 100L), dto(12L, 100L), dto(13L, 100L)));

        assertEquals(3, service.context(null, 100L).getChildCount());
    }

    @Test
    void contextOnRootReturnsItself() {
        Comment root = new Comment();
        root.setId(100L);
        root.setRootId(null);
        root.setStatus(Comment.STATUS_OK);
        when(commentMapper.selectById(100L)).thenReturn(root);
        when(commentMapper.selectDtoById(100L)).thenReturn(dto(100L, null));
        when(commentMapper.selectChildren(anyLong(), anyInt(), anyInt())).thenReturn(List.of());

        assertEquals(100L, service.context(null, 100L).getId());
    }

    @Test
    void contextOnDeletedCommentRejected() {
        Comment gone = new Comment();
        gone.setId(100L);
        gone.setStatus(Comment.STATUS_DELETED);
        when(commentMapper.selectById(100L)).thenReturn(gone);

        assertThrows(BizException.class, () -> service.context(null, 100L));
    }

    @Test
    void hugePageNumberDoesNotOverflowIntoNegativeOffset() {
        // page*size 用 int 乘会溢出成负数，PG 收到负 OFFSET 直接报错；
        // 而列表接口对游客放行，等于一个匿名 GET 就能刷 500
        when(commentMapper.selectRootPage(anyInt(), anyInt())).thenReturn(List.of());

        service.listRoots(null, 107374184, 20);
        service.listRoots(null, Integer.MAX_VALUE, 50);
        service.listRoots(null, Integer.MIN_VALUE, 20);

        ArgumentCaptor<Integer> offsets = ArgumentCaptor.forClass(Integer.class);
        verify(commentMapper, times(3)).selectRootPage(offsets.capture(), anyInt());
        offsets.getAllValues().forEach(off -> assertTrue(off >= 0, "OFFSET 不能为负，实际 " + off));
    }

    @Test
    void previewQueryTakesActualRootIdsNotAWindow() {
        // 早先预览查询自己按 offset/limit 复算窗口，两次查询之间有人发帖就会错位，
        // 当页最后一条根评论静默丢掉预览。现在直接吃上一步查到的 ID
        when(commentMapper.selectRootPage(anyInt(), anyInt()))
                .thenReturn(List.of(dto(1L, null), dto(2L, null)));
        when(commentMapper.selectChildPreviews(anyList(), anyInt())).thenReturn(List.of());

        service.listRoots(null, 1, 20);

        verify(commentMapper).selectChildPreviews(List.of(1L, 2L), 2);
    }

    @Test
    void pageParamsSanitized() {
        when(commentMapper.selectRootPage(anyInt(), anyInt())).thenReturn(List.of());

        service.listRoots(null, 0, -5);   // 手搓请求传的负数不能变成负 OFFSET 让 SQL 报错

        verify(commentMapper).selectRootPage(0, 1);
    }
}
