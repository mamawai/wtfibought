package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibsim.dto.PublicTradeDTO;
import com.mawai.wiibsim.service.PublicTradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全站成交接口的匿名性守卫。查询本身对不对（UNION 有没有漏表、翻页重不重）
 * 得连库跑，这里管的是"身份有没有从接口漏出去"这一件事：
 * <ol>
 *   <li>请求参数里不许出现 userId 入口——有了它就能枚举反查假名</li>
 *   <li>对外 DTO 上不许有 userId/username 字段</li>
 *   <li>kind 只认白名单，乱传当不筛</li>
 * </ol>
 */
class PublicTradeControllerTest {

    private PublicTradeService service;
    private PublicTradeController controller;

    @BeforeEach
    void setUp() {
        service = mock(PublicTradeService.class);
        when(service.pageAll(any(), any(), anyInt(), anyInt())).thenReturn(null);
        controller = new PublicTradeController(service);
    }

    /** 抓传给 service 的 kind */
    private String capturedKind() {
        ArgumentCaptor<String> kind = ArgumentCaptor.forClass(String.class);
        verify(service).pageAll(any(), kind.capture(), anyInt(), anyInt());
        return kind.getValue();
    }

    @Test
    void kind白名单内原样透传() {
        controller.publicTrades(null, "FUTURES", 1, 20);
        assertThat(capturedKind()).isEqualTo("FUTURES");
    }

    /**
     * 乱传的 kind 要当"不筛"，不能原样拼进 SQL。
     * 拼进去会匹配不到任何行、安静返个空列表，看页面的人只会以为"真没有成交"。
     */
    @Test
    void kind不在白名单内当作不筛() {
        controller.publicTrades(null, "GAME", 1, 20);
        assertThat(capturedKind()).isNull();
    }

    @Test
    void kind不传即不筛() {
        controller.publicTrades(null, null, 1, 20);
        assertThat(capturedKind()).isNull();
    }

    /**
     * 匿名的第一道：handler 形参只准是这四个——加个 userId 参数就能枚举反查假名。
     * 收"全部形参"不收"挂了 @RequestParam 的"：Spring 兜底解析器把无注解的简单类型形参也当查询参数绑定。
     */
    @Test
    void 请求参数里不许出现userId入口() {
        Set<String> allowed = new TreeSet<>(Set.of("symbol", "kind", "pageNum", "pageSize"));

        List<Method> handlers = Arrays.stream(PublicTradeController.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
        assertThat(handlers).as("一个 handler 都没扫到，本守卫什么都没验").isNotEmpty();

        Set<String> fromRequest = new TreeSet<>();
        for (Method m : handlers) {
            for (Parameter p : m.getParameters()) {
                boolean fromLogin = p.isAnnotationPresent(CurrentUserId.class);
                assertThat(!p.getName().toLowerCase().contains("userid") || fromLogin)
                        .as("%s 的形参 %s 会被当请求参数绑定，等于开放按人筛全站记录、反查假名",
                                m.getName(), p.getName())
                        .isTrue();
                if (!fromLogin) fromRequest.add(p.getName());
            }
        }
        assertThat(fromRequest)
                .as("handler 形参变了：确认新参数不能用来按人筛之后，再加进白名单")
                .isEqualTo(allowed);
    }

    /**
     * 匿名的第二道：对外 DTO 上不许出现身份字段（带身份的 PublicTradeRow 只在 service 内部流转）。
     */
    @Test
    void 对外DTO上没有任何身份字段() {
        Set<String> banned = Set.of("userid", "username", "avatar", "linuxdoid", "googleid", "nickname");

        for (Field f : PublicTradeDTO.class.getDeclaredFields()) {
            if (f.isSynthetic()) continue;
            assertThat(banned).as("PublicTradeDTO.%s 是身份字段，出了接口匿名就没了", f.getName())
                    .doesNotContain(f.getName().toLowerCase());
        }
        // 字段一个都没扫到时上面的循环会静默全绿
        assertThat(PublicTradeDTO.class.getDeclaredFields()).isNotEmpty();
    }
}
