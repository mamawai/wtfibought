package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账单接口的入参净化与出参形态。SQL 到底筛没筛 user_id、游标翻页真不真的往前翻，
 * mock 掉 mapper 是验不了的——那部分在 UserLedgerRealRunTest 末尾那组真跑用例里。
 * 本类管三件 mapper 之前/之后的事：
 * <ol>
 *   <li>limit 封顶与下限兜底（传下去的到底是几）</li>
 *   <li>bizType 传给 SQL 的是枚举名而不是中文 label</li>
 *   <li>中文 label 真的进了 JSON，而 bizType 仍是那个平铺的枚举名</li>
 * </ol>
 * 外加一条反射守卫：请求参数里不许出现 userId 入口。
 */
class LedgerControllerTest {

    private static final long ME = 42L;

    private UserLedgerMapper ledgerMapper;
    private LedgerController controller;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(UserLedgerMapper.class);
        when(ledgerMapper.selectByCursor(anyLong(), any(), any(), anyInt())).thenReturn(List.of());
        controller = new LedgerController(ledgerMapper);
    }

    /** 抓 selectByCursor 实际收到的 limit */
    private int capturedLimit() {
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(ledgerMapper).selectByCursor(anyLong(), any(), any(), limit.capture());
        return limit.getValue();
    }

    /**
     * 封顶刻意写死 100 而不是引 LedgerController 里那个常量：引常量的话谁把封顶改成 10 万，
     * 这条照样绿——封顶就白设了。改封顶就得来这儿显式改一次。
     */
    @Test
    void limit封顶挡住一把拉全表() {
        controller.list(ME, null, null, Integer.MAX_VALUE);
        assertThat(capturedLimit()).isEqualTo(100);
    }

    @Test
    void 封顶之内的limit原样传下去() {
        controller.list(ME, null, null, 30);
        assertThat(capturedLimit()).isEqualTo(30);
    }

    /** PG 对负数 LIMIT 直接报错，兜不住就是一个手搓请求刷 500 */
    @Test
    void limit为负数兜到1() {
        controller.list(ME, null, null, -1);
        assertThat(capturedLimit()).isEqualTo(1);
    }

    /**
     * 传给 SQL 的必须是枚举名——biz_type 列里存的就是这个字符串。
     * 谁顺手改成 getLabel()/toString()，筛选会静默返回空列表（中文匹配不上任何一行）。
     */
    @Test
    void bizType传给SQL的是枚举名不是中文label() {
        controller.list(ME, LedgerBizType.FUTURES_OPEN_MARGIN, null, 30);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(ledgerMapper).selectByCursor(anyLong(), type.capture(), any(), anyInt());
        assertThat(type.getValue()).isEqualTo("FUTURES_OPEN_MARGIN");
    }

    /** 不传类型时得是 null，SQL 里那个 &lt;if&gt; 才整条不拼——传空串会拼成 biz_type='' 把全部筛掉 */
    @Test
    void bizType不传则不筛类型() {
        controller.list(ME, null, null, 30);

        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(ledgerMapper).selectByCursor(anyLong(), type.capture(), any(), anyInt());
        assertThat(type.getValue()).isNull();
    }

    /** 游标原样透传，controller 不许自作聪明加减 1（那会让翻页漏一条或重一条） */
    @Test
    void beforeId原样透传() {
        controller.list(ME, null, 777L, 30);

        ArgumentCaptor<Long> before = ArgumentCaptor.forClass(Long.class);
        verify(ledgerMapper).selectByCursor(anyLong(), any(), before.capture(), anyInt());
        assertThat(before.getValue()).isEqualTo(777L);
    }

    /** 查的是登录态那个人，不是别人 */
    @Test
    void userId原样透传给SQL() {
        controller.list(ME, null, null, 30);

        ArgumentCaptor<Long> uid = ArgumentCaptor.forClass(Long.class);
        verify(ledgerMapper).selectByCursor(uid.capture(), any(), any(), anyInt());
        assertThat(uid.getValue()).isEqualTo(ME);
    }

    /**
     * 中文 label 必须以平铺字段进 JSON。
     * <p>
     * 这条同时挡两种回退：①有人删掉 UserLedger#getBizTypeLabel，label 又变成前端拿不到的空头支票；
     * ②有人给 LedgerBizType 挂 @JsonFormat(shape=OBJECT)——实测那会序列化成
     * {@code "bizType":{"label":"合约开仓保证金"}}，<b>枚举名整个消失</b>（Jackson 按 bean 序列化枚举，
     * name() 不带 get 前缀不算属性），前端连筛选参数该传什么都拿不到了。
     * <p>
     * 用 tools.jackson（Jackson 3）为了跟生产同 major。手搓的 mapper 只验 bean 属性形态，
     * 不代表 web 层实际配置——web 层加 mapper 定制本用例照样绿。
     */
    @Test
    void 中文label平铺进JSON而bizType仍是枚举名() {
        // Jackson 3 的 JavaTime 支持是内建的，不用再挂 module
        JsonMapper json = JsonMapper.builder().build();

        UserLedger row = new UserLedger();
        row.setId(1L);
        row.setUserId(ME);
        row.setWallet(LedgerWallet.BALANCE);
        row.setBizType(LedgerBizType.FUTURES_OPEN_MARGIN);
        row.setDelta(new BigDecimal("-100.00"));
        row.setBalanceAfter(new BigDecimal("900.00"));
        row.setCreatedAt(LocalDateTime.of(2026, 7, 27, 10, 0));

        String s = json.writeValueAsString(row);

        // 全用 contains 而不是比整串：Jackson 3 默认按属性名字母序输出（Jackson 2 是声明序），
        // 钉整串等于把一个跟本用例无关的 mapper 默认值也钉死
        assertThat(s).contains("\"bizType\":\"FUTURES_OPEN_MARGIN\"");
        assertThat(s).contains("\"bizTypeLabel\":\"合约开仓保证金\"");
        // wallet 没有 label 也不需要一份：前端只认枚举名
        assertThat(s).contains("\"wallet\":\"BALANCE\"");
        assertThat(s).doesNotContain("walletLabel");
    }

    /** bizType 理论上不该为 null（列是 NOT NULL），但 label 别因此变成 NPE 源 */
    @Test
    void bizType为null时label为null不抛() {
        assertThat(new UserLedger().getBizTypeLabel()).isNull();
    }

    /**
     * 只能查自己的：handler 上除 {@code @CurrentUserId} 外形参只准是这三个——加 userId 参数
     * 等于任何登录用户都能翻别人的账单。
     * 收"除 @CurrentUserId 外的全部形参"不按注解收：Spring 兜底解析器把无注解的简单类型形参
     * 也当查询参数绑定，按注解收一个都收不到。白名单比"名字不许含 userid"严（uid/targetId 绕不过）。
     * <p>
     * 拦得住"加参数"，<b>拦不住"改语义"</b>——有人把 beforeId 的含义偷偷改成"要查谁的"，
     * 名字没变、白名单不动，这条全绿。那种只能靠审查。
     * <p>
     * 参数名靠 class 文件的 MethodParameters（spring-boot-starter-parent 默认开 -parameters）。
     */
    @Test
    void 请求参数里不许出现userId入口() {
        Set<String> allowed = new TreeSet<>(Set.of("bizType", "beforeId", "limit"));

        List<Method> handlers = Arrays.stream(LedgerController.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                .toList();
        // 一个 handler 都没扫到时这条会静默全绿，等于守卫失效
        assertThat(handlers).as("一个 handler 都没扫到，本守卫什么都没验").isNotEmpty();

        Set<String> fromRequest = new TreeSet<>();
        for (Method m : handlers) {
            for (Parameter p : m.getParameters()) {
                boolean fromLogin = p.isAnnotationPresent(CurrentUserId.class);
                assertThat(!p.getName().toLowerCase().contains("userid") || fromLogin)
                        .as("%s 的形参 %s 必须挂 @CurrentUserId 从登录态取，"
                                + "否则它会被当请求参数绑定，等于开放翻别人的账单", m.getName(), p.getName())
                        .isTrue();
                if (!fromLogin) {
                    fromRequest.add(p.getName());   // 登录态注入的那个之外，一律算请求侧
                }
            }
        }
        assertThat(fromRequest)
                .as("handler 的形参变了：新增的参数确认过不能顶替 userId 之后，再把它加进白名单")
                .isEqualTo(allowed);
    }
}
