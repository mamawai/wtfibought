package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.entity.UserWorkbenchPref;
import com.mawai.wiibagent.mapper.UserWorkbenchPrefMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 用户偏好：没行按默认；上限钳在 1..硬上限；首次保存插入、之后更新 */
class WorkbenchPrefServiceTest {

    private final UserWorkbenchPrefMapper mapper = mock(UserWorkbenchPrefMapper.class);
    private final WorkbenchPrefService service = new WorkbenchPrefService(mapper, 3, 5);

    @Test
    void 没行就是默认() {
        assertThat(service.of(1L)).isEqualTo(new WorkbenchPrefService.Prefs(true, 3));
        assertThat(service.hardLimit()).isEqualTo(5);
    }

    @Test
    void 保存钳在上限内_首次插入之后更新() {
        WorkbenchPrefService.Prefs saved = service.save(1L, false, 9);

        assertThat(saved).isEqualTo(new WorkbenchPrefService.Prefs(false, 5));
        ArgumentCaptor<UserWorkbenchPref> row = ArgumentCaptor.forClass(UserWorkbenchPref.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getUserId()).isEqualTo(1L);
        assertThat(row.getValue().getSubAgentsEnabled()).isFalse();
        assertThat(row.getValue().getSubAgentLimit()).isEqualTo(5);
        verify(mapper, never()).updateById(any(UserWorkbenchPref.class));

        // 已有行：读回来的就是存的；再存走更新，下限钳到 1
        when(mapper.selectById(1L)).thenReturn(row.getValue());
        assertThat(service.of(1L)).isEqualTo(new WorkbenchPrefService.Prefs(false, 5));
        assertThat(service.save(1L, true, 0)).isEqualTo(new WorkbenchPrefService.Prefs(true, 1));
        verify(mapper).updateById(any(UserWorkbenchPref.class));
    }

    @Test
    void 不传上限按默认() {
        assertThat(service.save(2L, true, null)).isEqualTo(new WorkbenchPrefService.Prefs(true, 3));
    }
}
