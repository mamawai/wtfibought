package com.mawai.wiibagent.chat.tools;

import com.mawai.wiibcommon.enums.AgentLang;
import com.mawai.wiibagent.trader.TraderChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * trader 专家的只读工具集。
 * <p>
 * 这里只钉一件事：<b>工具认人靠的是建叶子时烤进来的 userId，不是模型填的参数</b>。
 * 归属判断本身归 {@code TraderChatServiceTest}，那是另一层的事。
 */
class TraderQueryToolkitTest {

    private final TraderChatService service = mock(TraderChatService.class);

    /**
     * 两个用户各自的工具实例互不串味。烤死的 userId 一旦写成静态/共享字段，
     * 后建的那份会把先建的覆盖掉，两个人看到同一个 trader——这条就是那件事的钉子。
     */
    @Test
    void 各自只读自己那份() {
        TraderQueryToolkit mine = new TraderQueryToolkit(service, 1L, AgentLang.ZH);
        TraderQueryToolkit others = new TraderQueryToolkit(service, 2L, AgentLang.EN);

        mine.traderOverview();
        others.traderOverview();

        verify(service).overview(1L, AgentLang.ZH);
        verify(service).overview(2L, AgentLang.EN);
    }

    /**
     * 工具签名里绝不能出现用户/trader 标识类参数——有的话模型就能自己填一个别人的号。
     * 这条比"传对了 userId"更根本：那条验的是当前实现传对了，这条堵的是接口层面根本给不了。
     */
    @Test
    void 工具不暴露任何身份参数() {
        for (Method m : TraderQueryToolkit.class.getDeclaredMethods()) {
            if (m.getAnnotation(Tool.class) == null) {
                continue;
            }
            assertThat(Arrays.stream(m.getParameters()).map(p -> p.getType().getSimpleName()))
                    .as("工具 %s 的参数", m.getName())
                    .allSatisfy(type -> assertThat(type).isEqualTo("Integer")); // 只有 limit
        }
    }
}
