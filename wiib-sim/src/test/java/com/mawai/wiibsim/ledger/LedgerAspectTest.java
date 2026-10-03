package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 一次性标注的生命周期测试，两个 pointcut（UserMapper 资金方法 / FuturesPositionMapper 资金费扣保证金）都在场。
 * <p>
 * 刻意<b>不</b>直接调 advice 方法，而是用 AspectJProxyFactory 把切面真织到 mock 的 mapper 上：
 * 直接调方法的话，谁把 @AfterThrowing 注解删掉、方法体留着，测试照样绿——那种测试守不住任何东西。
 * 走真织入，注解一没advice 就不再挂上去，测试立刻红。
 * <p>
 * 用 mock 当 target 是因为这里要测的是"标注怎么流转"，不需要真 SQL；
 * 抛异常这条路径真跑几乎造不出来（得现场制造 PG 死锁），纯单测反而更可控。
 */
class LedgerAspectTest {

    private static final BigDecimal MINUS_TEN = new BigDecimal("-10");
    private static final BigDecimal MINUS_TWENTY = new BigDecimal("-20");
    private static final BigDecimal FEE = new BigDecimal("10");
    private static final Long POS_ID = 9L;

    private UserMapper target;
    private UserMapper proxy;
    private FuturesPositionMapper positionTarget;
    private FuturesPositionMapper positionProxy;
    private UserLedgerMapper ledgerMapper;

    @BeforeEach
    void 把切面织到mock的mapper上() {
        target = mock(UserMapper.class);
        positionTarget = mock(FuturesPositionMapper.class);
        ledgerMapper = mock(UserLedgerMapper.class);

        // 必要的是两个 mapper 的代理同时在场：标注在两个 pointcut 之间怎么流转
        // （尤其"漏到下一笔"）只有两边都能打得着才测得出来。
        // 复用同一个切面实例只是顺手——LedgerCtx 是静态 ThreadLocal，织两个实例行为完全一样。
        LedgerAspect aspect = new LedgerAspect(ledgerMapper);

        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxy = factory.getProxy();

        AspectJProxyFactory positionFactory = new AspectJProxyFactory(positionTarget);
        positionFactory.addAspect(aspect);
        positionProxy = positionFactory.getProxy();
    }

    /** 本类专门制造标注泄漏，跑完必须自己兜干净，否则串到别的用例上 */
    @AfterEach
    void 清掉可能残留的标注() {
        LedgerCtx.takeMark();
    }

    @Test
    void 成功的资金变动消费掉标注并按标注落账() {
        when(target.atomicUpdateBalance(1L, MINUS_TEN)).thenReturn(new BigDecimal("90"));

        LedgerCtx.mark(LedgerBizType.PREDICTION_BUY, 42L);
        proxy.atomicUpdateBalance(1L, MINUS_TEN);

        UserLedger entry = captureEntry();
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.PREDICTION_BUY);
        assertThat(entry.getRefId()).isEqualTo(42L);
    }

    /**
     * SQL 抛异常时标注必须被丢弃 —— 这条是 @AfterThrowing 的看门测试。
     * <p>
     * @AfterReturning 在抛异常时整条 advice 不执行，标注就留在线程上；线程池/Tomcat 复用后，
     * 下一个请求第一笔没标注的资金变动会继承它。所以这里第二笔刻意<b>不</b>标注：
     * 摘掉 @AfterThrowing，第二笔的 bizType 会变成 PREDICTION_BUY、refId 变成 42（实测过，见报告）。
     */
    @Test
    void SQL抛异常时标注必须被丢弃() {
        // 第一笔：带标注，SQL 抛异常（PG 死锁/锁超时/约束冲突都是这个形态）
        when(target.atomicUpdateBalance(1L, MINUS_TEN))
                .thenThrow(new RuntimeException("deadlock detected"));

        LedgerCtx.mark(LedgerBizType.PREDICTION_BUY, 42L);
        assertThatThrownBy(() -> proxy.atomicUpdateBalance(1L, MINUS_TEN))
                .hasMessageContaining("deadlock");

        // 抛异常 = 这笔钱没动，一行账都不许记
        verifyNoInteractions(ledgerMapper);

        // 第二笔：另一个用户、没标注、成功。标注若没被丢弃，这笔就会顶着上一笔的语义入账
        when(target.atomicUpdateBalance(2L, MINUS_TWENTY)).thenReturn(new BigDecimal("80"));
        proxy.atomicUpdateBalance(2L, MINUS_TWENTY);

        UserLedger entry = captureEntry();
        assertThat(entry.getUserId()).isEqualTo(2L);
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.UNKNOWN);   // 泄漏则为 PREDICTION_BUY
        assertThat(entry.getRefId()).isNull();                             // 泄漏则为 42
    }

    // ==================== 第二个 pointcut：资金费扣仓位保证金 ====================

    /**
     * 正向：扣成了就按 POSITION_MARGIN 落一条，userId/扣款额取自 markPositionFee，
     * balanceAfter 取自 SQL 返回的扣后保证金。
     * <p>
     * 删掉 {@code recordFundingFeeFromMargin} 整条 advice 这条就红——否则那条 advice
     * 在默认单测里一行都跑不到（本类原来只织 UserMapper），只有真跑才碰得着。
     */
    @Test
    void 扣保证金成功时按POSITION_MARGIN落账() {
        when(positionTarget.atomicDeductFundingFee(POS_ID, FEE)).thenReturn(new BigDecimal("90"));

        LedgerCtx.markPositionFee(7L, POS_ID, FEE);
        positionProxy.atomicDeductFundingFee(POS_ID, FEE);

        UserLedger entry = captureEntry();
        assertThat(entry.getUserId()).isEqualTo(7L);                       // 切面从 SQL 参数里拿不到，只能靠 mark
        assertThat(entry.getWallet()).isEqualTo(LedgerWallet.POSITION_MARGIN);
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.FUNDING_FEE_FROM_MARGIN);
        assertThat(entry.getDelta()).isEqualByComparingTo("-10");
        assertThat(entry.getBalanceAfter()).isEqualByComparingTo("90");     // 写死 0 则红
        assertThat(entry.getRefType()).isEqualTo("POSITION");
        assertThat(entry.getRefId()).isEqualTo(POS_ID);
    }

    /**
     * 保证金不够（返 null）时标注必须<b>当场</b>被消费掉 —— 这条是"takeMark 必须是第一句"的看门测试。
     * <p>
     * 返 null 不是异常路径而是设计上的正常分支：紧接着还有"扣光全部保证金"那一枪。
     * 把 takeMark 挪到判空之后（brief 草稿的写法），标注就留在线程上，
     * 而资金费是单线程 for 循环逐仓位跑的，下一个仓位第一笔没标注的资金变动会直接继承它。
     * 所以第二笔刻意换个用户、<b>不</b>标注：挪回去的话它的 bizType 会变成
     * FUNDING_FEE_FROM_MARGIN、refId 变成 9（实测过）。
     */
    @Test
    void 保证金不够返null时标注必须被消费掉() {
        when(positionTarget.atomicDeductFundingFee(POS_ID, FEE)).thenReturn(null);

        LedgerCtx.markPositionFee(7L, POS_ID, FEE);
        assertThat(positionProxy.atomicDeductFundingFee(POS_ID, FEE)).isNull();
        verifyNoInteractions(ledgerMapper);   // 没扣成，一行账都不许记

        assertNextUnmarkedWalletMoveIsUnknown();
    }

    /**
     * 扣保证金的 SQL 抛异常时标注必须被丢掉 —— 这条是 @AfterThrowing 那个
     * {@code || positionMarginMutation()} 的看门测试。
     * <p>
     * 只写 walletMutation() 的话，这条路径抛异常后标注留在线程上，而它<b>带着 userId</b>：
     * 漏到下一笔就是把这个用户的资金费记到另一个用户账本上。删掉那半个表达式本用例即红（实测过）。
     */
    @Test
    void 扣保证金SQL抛异常时标注必须被丢弃() {
        when(positionTarget.atomicDeductFundingFeePartial(POS_ID))
                .thenThrow(new RuntimeException("deadlock detected"));

        LedgerCtx.markPositionFee(7L, POS_ID, FEE);
        assertThatThrownBy(() -> positionProxy.atomicDeductFundingFeePartial(POS_ID))
                .hasMessageContaining("deadlock");
        verifyNoInteractions(ledgerMapper);   // 抛异常 = 这笔钱没动

        assertNextUnmarkedWalletMoveIsUnknown();
    }

    /**
     * 上面两条共用的收尾：再打一枪<b>没标注</b>的普通资金变动，它必须落成 UNKNOWN。
     * 标注若没被丢干净，这一枪就会顶着资金费的语义和别人的 refId 入账。
     */
    private void assertNextUnmarkedWalletMoveIsUnknown() {
        when(target.atomicUpdateBalance(2L, MINUS_TWENTY)).thenReturn(new BigDecimal("80"));
        proxy.atomicUpdateBalance(2L, MINUS_TWENTY);

        UserLedger entry = captureEntry();
        // userId 取自 args[0]，标注泄漏了也还是 2——这行只是确认打的是第二枪，不是泄漏的探针
        assertThat(entry.getUserId()).isEqualTo(2L);
        assertThat(entry.getWallet()).isEqualTo(LedgerWallet.BALANCE);
        assertThat(entry.getBizType()).isEqualTo(LedgerBizType.UNKNOWN);    // 泄漏则为 FUNDING_FEE_FROM_MARGIN
        assertThat(entry.getRefId()).isNull();                              // 泄漏则为 9
    }

    private UserLedger captureEntry() {
        ArgumentCaptor<UserLedger> captor = ArgumentCaptor.forClass(UserLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        return captor.getValue();
    }
}
