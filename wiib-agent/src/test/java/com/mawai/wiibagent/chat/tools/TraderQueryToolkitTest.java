package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.entity.AiTrader;
import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主 agent 的 trader 只读工具集。
 * <p>
 * 这里只钉两件事：<b>认人靠建叶子时烤进来的 userId，不是模型填的参数</b>；查哪个 trader 交给传进来的定位办法。
 * 定位规则本身归 {@code SessionTraderLocatorTest}，查询内容归 {@code TraderChatServiceTest}。
 */
class TraderQueryToolkitTest {

    private static final ToolContext CTX = new ToolContext(Map.of(ToolRunContext.SESSION_KEY, "wb-1-x"));

    private final TraderChatService service = mock(TraderChatService.class);

    /**
     * 两个用户各自的工具实例互不串味。烤死的 userId 一旦写成静态/共享字段，
     * 后建的那份会把先建的覆盖掉，两个人看到同一份名单——这条就是那件事的钉子。
     */
    @Test
    void 各自只读自己那份() {
        TraderQueryToolkit mine = new TraderQueryToolkit(service, 1L, AgentLang.ZH, (name, context) -> null);
        TraderQueryToolkit others = new TraderQueryToolkit(service, 2L, AgentLang.EN, (name, context) -> null);

        mine.traderList();
        others.traderList();

        verify(service).list(1L, AgentLang.ZH);
        verify(service).list(2L, AgentLang.EN);
    }

    /** 点的名字和会话现场原样交给定位办法；定到了才查，定不到就把定位那句原样回给模型 */
    @Test
    void 定位到谁查谁_定不到原样回执() {
        AiTrader alpha = new AiTrader();
        alpha.setName("Alpha");
        List<String> asked = new ArrayList<>();
        TraderQueryToolkit toolkit = new TraderQueryToolkit(service, 1L, AgentLang.ZH, (name, context) -> {
            asked.add(name + "@" + ToolRunContext.sessionId(context));
            return "Alpha".equals(name) ? TraderLocator.Located.of(alpha) : TraderLocator.Located.miss("没定到");
        });
        when(service.positions(alpha, AgentLang.ZH)).thenReturn("Alpha 的持仓");

        assertThat(toolkit.traderPositions("Alpha", CTX)).isEqualTo("Alpha 的持仓");
        assertThat(toolkit.traderPlans("Beta", CTX)).isEqualTo("没定到");
        assertThat(asked).containsExactly("Alpha@wb-1-x", "Beta@wb-1-x");
        verify(service, never()).plans(any(), any());
    }

    /**
     * 工具签名里绝不能出现用户/trader 标识类参数——有的话模型就能自己填一个别人的号。
     * trader 是名字，只在烤死的 userId 名下找；ToolContext 是框架注入的、不进 schema。
     */
    @Test
    void 工具不暴露任何身份参数() {
        for (Method m : TraderQueryToolkit.class.getDeclaredMethods()) {
            if (m.getAnnotation(Tool.class) == null) {
                continue;
            }
            assertThat(Arrays.stream(m.getParameters()).map(Parameter::getName))
                    .as("工具 %s 的参数", m.getName())
                    .allSatisfy(name -> assertThat(name).isIn("limit", "kind", "trader", "context"));
        }
    }
}
