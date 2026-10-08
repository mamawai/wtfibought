package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.entity.CryptoOrder;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibcommon.enums.OrderSide;
import com.mawai.wiibcommon.enums.OrderStatus;
import com.mawai.wiibcommon.enums.OrderType;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibsim.controller.InternalFuturesTradeController;
import com.mawai.wiibsim.ledger.LedgerTx;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.LedgerProbeMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账本真跑验收：两种压标签的方式到底生没生效（public 方法上的 {@code @Ledger}、类内事务段的 {@code LedgerTx}）；
 * 类内事务段抛异常时钱回不回滚；以及<b>切面射程外那几条路径</b>（建号 INSERT、爆仓/破产恢复的整体覆写 UPDATE、
 * 资金费扣仓位保证金）补记得对不对——那几条全靠业务代码显式记，漏了不报错、事后补不回来。
 * <p>
 * 单测和 LedgerPlacementTest 都只能证明"注解没标在明显拦不到的位置"，证不了"真的拦到了"。
 * 两种形态哪种不通都不报错——流水静默落 UNKNOWN，事后补不回来。所以两种形态各真跑一次。
 * <p>
 * 放在 service.impl 包：破产恢复和资金费用例要直接调包内可见的 resetUser / doChargeFundingFeeOne。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=LedgerProxyRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class LedgerProxyRealRunTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserLedgerMapper ledgerMapper;
    @Autowired
    private LedgerProbeMapper ledgerProbe;

    @Autowired
    private UserService userService;

    @Autowired
    private CryptoOrderServiceImpl cryptoOrderServiceImpl;

    @Autowired
    private CryptoOrderMapper cryptoOrderMapper;

    @Autowired
    private FuturesPositionMapper positionMapper;

    @Autowired
    private FuturesTradingServiceImpl futuresTradingServiceImpl;

    @Autowired
    private FuturesSettlementServiceImpl futuresSettlementServiceImpl;

    @Autowired
    private BankruptcyServiceImpl bankruptcyServiceImpl;

    @Autowired
    private InternalFuturesTradeController internalFuturesTradeController;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private LedgerTx ledgerTx;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdPositionIds = new ArrayList<>();
    private final List<Long> createdOrderIds = new ArrayList<>();

    private Long newUser(String balance) {
        String tag = "ledger-test-" + System.nanoTime();
        User u = new User();
        u.setUsername(tag);
        u.setLinuxDoId("internal:" + tag);
        u.setBalance(new BigDecimal(balance));
        u.setFrozenBalance(BigDecimal.ZERO);
        u.setGameBalance(BigDecimal.ZERO);
        u.setIsBankrupt(false);
        u.setBankruptCount(0);
        userMapper.insert(u);
        createdUserIds.add(u.getId());
        return u.getId();
    }

    /**
     * 连的是所有者的真实开发库：测试用户会爬进排行榜，跑完必须按 id 清干净。
     * <p>
     * 这几张表都<b>没有 FK</b>，删 user 不会带走它们，得逐张点名：
     * user_ledger（切面每笔都插）、futures_position（事务验证与资金费用例造的仓位）、
     * crypto_order（撤单用例造的挂单）。
     * 加新用例前先想清楚它会往哪张表落行。
     * <p>
     * 刻意<b>不</b>摘 Redis 触发索引：本类的仓位都是直接 INSERT 造的、从没 registerPositionIndex 过，
     * 而资金费用例走到的 updateLiquidationPrice 只在 zScore 已有该 member 时才 zAdd
     * （见 FuturesPositionIndexServiceImpl），所以压根没有索引可留，没什么要清的。
     * （曾经这里还写着"调 unregisterAll 会抛 ClassCastException"——那个 Spring Boot 4 迁移遗留的强转 bug
     * 已修，索引读写现在由 FuturesPositionIndexRealRunTest 单独兜。）
     */
    @AfterEach
    void 清掉本次建的测试数据() {
        createdPositionIds.forEach(positionMapper::deleteById);
        createdPositionIds.clear();
        createdOrderIds.forEach(cryptoOrderMapper::deleteById);
        createdOrderIds.clear();
        createdUserIds.forEach(ledgerMapper::deleteByUserId);
        createdUserIds.forEach(userMapper::deleteById);
        createdUserIds.clear();
    }

    /**
     * 形态一：public 接口方法上的 @Ledger（UserServiceImpl.transferToGame）。
     * 划转一条 SQL 动两个钱包 → 两行，两行都得是 WALLET_TRANSFER_OUT。
     */
    @Test
    void public接口方法上的Ledger真的生效() {
        Long uid = newUser("1000.00");

        userService.transferToGame(uid, new BigDecimal("100.00"));

        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(2);
        // 没生效就会是 UNKNOWN（切面兜底），这条断言就是"注解生效"的唯一硬证据
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getBizType()).isEqualTo(LedgerBizType.WALLET_TRANSFER_OUT));
        // 顺带确认两行是同一条 SQL 的两个钱包：转出 100、到账 99（1% 手续费销毁）
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.BALANCE);
            assertThat(r.getDelta()).isEqualByComparingTo("-100.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.GAME);
            assertThat(r.getDelta()).isEqualByComparingTo("99.00");
        });
    }

    /**
     * 形态二：类内事务段经 LedgerTx 压标签（CryptoOrderServiceImpl.cancel → doCancelOrder）。
     * 这是类内事务段的统一写法，也是标签最吃重的形态。
     * <p>
     * 选撤单：金额完全确定（解冻多少就是多少），不把"标签生效没有"和配置项绑在一起。
     * 走真入口 cancel，验的就是生产里那一行 ledgerTx.call。
     */
    @Test
    void 类内事务段经LedgerTx压标签时真的生效() {
        Long uid = newUser("1000.00");
        // 垫场：先真冻结 500，否则解冻那条 SQL 的 frozen_balance >= 500 条件不满足，返 null 不记账
        userService.freezeBalance(uid, new BigDecimal("500.00"));
        Long orderId = newPendingLimitBuyOrder(uid, new BigDecimal("500.00"));
        ledgerMapper.deleteByUserId(uid);   // 冻结那笔是垫场，清掉免得混进断言

        cryptoOrderServiceImpl.cancel(uid, orderId);

        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        // 一条 atomicUnfreezeBalance 动两个钱包 → 两行
        assertThat(rows).hasSize(2);
        // 没生效就会是 UNKNOWN（切面兜底），这条断言就是"注解生效"的唯一硬证据
        assertThat(rows).allSatisfy(r ->
                assertThat(r.getBizType()).isEqualTo(LedgerBizType.SPOT_LIMIT_UNFREEZE));
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.FROZEN);
            assertThat(r.getDelta()).isEqualByComparingTo("-500.00");
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.getWallet()).isEqualTo(LedgerWallet.BALANCE);
            assertThat(r.getDelta()).isEqualByComparingTo("500.00");
        });
    }

    /** 造一张 PENDING 的限价买单，供撤单用例打。frozenAmount 要和调用方真冻结的数一致 */
    private Long newPendingLimitBuyOrder(Long userId, BigDecimal frozenAmount) {
        CryptoOrder o = new CryptoOrder();
        o.setUserId(userId);
        o.setSymbol("BTCUSDT");
        o.setOrderSide(OrderSide.BUY.getCode());
        o.setOrderType(OrderType.LIMIT.getCode());
        o.setQuantity(new BigDecimal("0.01"));
        o.setLeverage(1);
        o.setLimitPrice(new BigDecimal("50000.00"));
        o.setFrozenAmount(frozenAmount);
        o.setStatus(OrderStatus.PENDING.getCode());
        cryptoOrderMapper.insert(o);
        createdOrderIds.add(o.getId());
        return o.getId();
    }

    // ==================== 类内事务段抛异常时钱回不回滚 ====================

    /**
     * 真跑一遍看"钱到底回不回滚"——失败注入。
     * <p>
     * 打 {@code FuturesTradingServiceImpl.addMargin}，它经 LedgerTx 进的 doAddMargin 执行顺序天然适合注入：
     * <pre>
     * atomicUpdateBalance(-amount)   ← 钱动了，切面同时插了账本行
     * atomicAddMargin(+amount)       ← 仓位 margin 也动了
     * calcStaticLiqPrice(symbol...)  ← 未配置档位的 symbol 在这里抛 FUTURES_SYMBOL_NOT_CONFIGURED
     * </pre>
     * 所以只要把仓位的 symbol 造成一个 bracket 表里没有的值，就能在"钱已经动完"之后
     * 稳定抛异常，不需要 mock 任何东西。
     * <p>
     * 事务生效 → 余额、仓位 margin、账本三样全回滚；不生效 → 三样都留下痕迹。
     */
    @Test
    void 类内事务段抛异常时资金必须回滚() {
        Long uid = newUser("1000.00");
        // bracket 表里绝不会有的 symbol，让 calcStaticLiqPrice 在动钱之后抛
        Long posId = newIsolatedPosition(uid, "NOSUCHSYMBOLUSDT", new BigDecimal("200.00"));

        FuturesAddMarginRequest req = new FuturesAddMarginRequest();
        req.setPositionId(posId);
        req.setAmount(new BigDecimal("100.00"));

        // 必须钉住是哪个异常：FUTURES_SYMBOL_NOT_CONFIGURED 只可能来自 calcStaticLiqPrice（两次动钱之后），
        // 只断言"抛了"的话前置校验抛出也假绿
        assertThatThrownBy(() -> futuresTradingServiceImpl.addMargin(uid, req))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .as("失败注入必须落在 calcStaticLiqPrice（动钱之后），否则本用例什么都没验到")
                .isEqualTo(ErrorCode.FUTURES_SYMBOL_NOT_CONFIGURED.getCode());

        // 三样一起看：只看余额的话，万一 atomicAddMargin 那步就失败了也会"余额没变"，假绿
        assertThat(userMapper.selectById(uid).getBalance())
                .as("余额必须回滚到 1000（若为 900 则 LedgerTx 没把事务开起来）")
                .isEqualByComparingTo("1000.00");
        assertThat(positionMapper.selectById(posId).getMargin())
                .as("仓位保证金必须回滚到 200")
                .isEqualByComparingTo("200.00");
        assertThat(ledgerMapper.selectByCursor(uid, null, null, 10))
                .as("钱没动成，账本不该留行")
                .isEmpty();
    }

    /** 造一张逐仓 OPEN 仓位，字段只填 NOT NULL 的那些 */
    private Long newIsolatedPosition(Long userId, String symbol, BigDecimal margin) {
        return newIsolatedPosition(userId, symbol, margin, new BigDecimal("0.10"));
    }

    private Long newIsolatedPosition(Long userId, String symbol, BigDecimal margin, BigDecimal quantity) {
        FuturesPosition p = new FuturesPosition();
        p.setUserId(userId);
        p.setSymbol(symbol);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(10);
        p.setQuantity(quantity);
        p.setEntryPrice(new BigDecimal("20000.00"));
        p.setMargin(margin);
        p.setFundingFeeTotal(BigDecimal.ZERO);
        p.setStatus("OPEN");
        positionMapper.insert(p);
        createdPositionIds.add(p.getId());
        return p.getId();
    }

    // ==================== 切面射程外的三条路径：建号 / 爆仓 / 破产恢复 ====================

    /**
     * 建号是 INSERT（balance 直接是列值），不穿任何 atomic* 方法，切面根本看不见——
     * 不显式补 INITIAL_GRANT，每个新用户开局就是 SUM(delta)=0 而 balance=10000，不变量当场破。
     * <p>
     * 打的是真入口 {@code /internal/futures/ensure-account}（量化机器人建号），不是直接调
     * recordInitialGrant：后者只能证明那个方法自己没写错，证不了建号路径真的调了它。
     * 另两个建号入口（OAuth 首登、邀请码注册）调的是同一个 recordInitialGrant，
     * 但都得先过 StpUtil.login（非 Web 上下文起不来），真跑不了，只能靠代码审查。
     */
    @Test
    void 新建账户落库后不变量立即成立() {
        String username = "ledger-test-" + System.nanoTime();
        BigDecimal initial = new BigDecimal("12345.00");

        var body = internalFuturesTradeController.ensureAccount(username, initial).getData();
        Long uid = (Long) body.get("userId");
        createdUserIds.add(uid);

        // 就一条 INITIAL_GRANT，delta 和 balanceAfter 都是建号那一刻的余额
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getBizType()).isEqualTo(LedgerBizType.INITIAL_GRANT);
        assertThat(rows.getFirst().getWallet()).isEqualTo(LedgerWallet.BALANCE);
        assertThat(rows.getFirst().getBalanceAfter()).isEqualByComparingTo(initial);

        assertBalanceInvariant(uid);
    }

    /**
     * 爆仓把五个钱包整体覆写成 0，SQL 名字不叫 atomic*、也拿不到旧值，切面双重抓不到。
     * 靠 selectByIdForUpdate 读快照后逐钱包补记 −旧值，清零后不变量必须仍成立（两边都是 0）。
     * <p>
     * 刻意先把四个钱包都垫成非 0 再爆：只垫余额的话，漏记 FROZEN/GAME/借款那几条也照样绿。
     * 用 bankruptNow 而不是 checkAndLiquidateAll——后者扫全库，会顺手爆掉所有者的真实账号。
     */
    @Test
    void 爆仓清零后各钱包不变量仍成立() {
        Long uid = newUserWithGrant("10000.00");
        LocalDate today = LocalDate.now();

        // 垫场：五个钱包全弄成非 0，且互不相同，漏记哪条哪条红
        assertThat(userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"))).isNotNull();
        assertThat(userMapper.atomicUpdateGameBalance(uid, new BigDecimal("300.00"))).isNotNull();
        assertThat(userMapper.atomicAddMarginLoanPrincipal(uid, new BigDecimal("500.00"))).isNotNull();
        assertThat(userMapper.atomicAccrueInterest(uid, new BigDecimal("30.00"), today)).isNotNull();
        assertAllWalletInvariants(uid);   // 爆仓前先确认起点是平的，否则下面绿了也说明不了问题

        bankruptcyServiceImpl.bankruptNow(uid);

        User after = userMapper.selectById(uid);
        assertThat(after.getIsBankrupt()).isTrue();
        assertThat(after.getBalance()).isEqualByComparingTo("0");
        assertAllWalletInvariants(uid);

        // 光看求和不够：得确认真是"爆仓清零"这几条把账抹平的，而不是恰好凑对了数
        assertThat(ledgerMapper.selectByCursor(uid, LedgerBizType.BANKRUPT_CLEAR.name(), null, 10))
                .as("五个钱包都非 0，清零就该记五条")
                .hasSize(5);
    }

    /**
     * 破产恢复：balance 被整体覆写成初始资金，delta 是"目标值 − 快照旧值"而不是初始资金本身。
     * 恢复要求 bankrupt_reset_date <= today，所以 today 传爆仓时算出来的那个恢复日。
     * 照 resetBankruptUsers 循环体那一行调 resetUser；resetBankruptUsers 扫全库，会恢复所有者的真实破产账号。
     */
    @Test
    void 破产恢复后不变量仍成立() {
        Long uid = newUserWithGrant("10000.00");
        bankruptcyServiceImpl.bankruptNow(uid);
        LocalDate resetDate = userMapper.selectById(uid).getBankruptResetDate();

        transactionTemplate.executeWithoutResult(_ -> bankruptcyServiceImpl.resetUser(uid, resetDate));

        User after = userMapper.selectById(uid);
        assertThat(after.getIsBankrupt()).isFalse();
        assertThat(after.getBalance()).isGreaterThan(BigDecimal.ZERO);   // 恢复到配置的初始资金
        assertAllWalletInvariants(uid);
        assertThat(ledgerMapper.selectByCursor(uid, LedgerBizType.BANKRUPT_RESET.name(), null, 10))
                .as("清零后只有 balance 从 0 变回初始资金，就一条")
                .hasSize(1);
    }

    /**
     * 资金费在余额扣不动时直接吃仓位保证金——这笔钱不穿 user 表，由第二个 pointcut
     * （FuturesPositionMapper.atomicDeductFundingFee*）记账，userId 与扣款额靠调用点的
     * markPositionFee 带进来。切面若没织上或调用点漏标，这里一条流水都没有。
     * <p>
     * 余额刻意给 0：支付方三级兜底的第一级 atomicUpdateBalance 必然返 null，才会掉到扣保证金那级。
     * 断言写成"delta == 实际少掉的保证金、balanceAfter == 库里当前保证金"这种相对式；
     * mark 价取实时缓存（取不到退回开仓价 20000）、数量 0.001、费率 1%，费只有几块钱，
     * 远小于 5000 保证金，稳定走"够扣"那一级（这一级之后不做强平复核）。
     */
    @Test
    void 资金费扣保证金走第二个切点记账() {
        Long uid = newUser("0.00");
        Long posId = newIsolatedPosition(uid, "BTCUSDT", new BigDecimal("5000.00"), new BigDecimal("0.00100000"));
        BigDecimal marginBefore = positionMapper.selectById(posId).getMargin();

        // 正费率 + LONG = 本仓应付；生产入口：拿仓位锁，经 ledgerTx 进事务段
        futuresSettlementServiceImpl.chargeFundingFeeOne(positionMapper.selectById(posId), new BigDecimal("0.0100"));

        BigDecimal marginAfter = positionMapper.selectById(posId).getMargin();
        assertThat(marginAfter).as("保证金必须真被扣了，否则本用例什么都没验到").isLessThan(marginBefore);

        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(1);
        UserLedger row = rows.getFirst();
        assertThat(row.getWallet()).isEqualTo(LedgerWallet.POSITION_MARGIN);
        assertThat(row.getBizType()).isEqualTo(LedgerBizType.FUNDING_FEE_FROM_MARGIN);
        assertThat(row.getDelta()).isEqualByComparingTo(marginAfter.subtract(marginBefore));
        // balanceAfter 取自同条 UPDATE 的 RETURNING margin，不是事后补查也不是写死的 0
        assertThat(row.getBalanceAfter()).isEqualByComparingTo(marginAfter);
        assertThat(row.getRefType()).isEqualTo("POSITION");
        assertThat(row.getRefId()).isEqualTo(posId);
        assertThat(row.getSymbol()).isEqualTo("BTCUSDT");
    }

    /**
     * 第三级兜底"保证金也不够、直接扣光"：那条 SQL 是整体覆写（SET margin = 0），扣款额只能从
     * <b>锁内快照</b>来。断言 delta 恰等于建仓时那 0.01，就是在钉死"记的是 selectMarginForUpdate
     * 读到的真实保证金"，不是那笔算出来的资金费，也不是恒为 0 的 RETURNING 值。
     * <p>
     * 保证金给 0.01：mark 价传 20000，资金费 20000 × 0.001 × 1% = 0.20，
     * 必然扣不动、掉到这一级。checkLiquidation 只有这一级返 true，用它钉住分支。
     */
    @Test
    void 资金费扣光保证金记的是锁内真实扣款额() {
        Long uid = newUser("0.00");
        Long posId = newIsolatedPosition(uid, "BTCUSDT", new BigDecimal("0.01"), new BigDecimal("0.00100000"));

        // chargeFundingFeeOne 里的事务段，不含之后的强平复核
        var result = ledgerTx.call(LedgerBizType.FUNDING_FEE_PAY, () -> futuresSettlementServiceImpl
                .doChargeFundingFeeOne(posId, new BigDecimal("0.0100"), new BigDecimal("20000")));

        assertThat(result.checkLiquidation())
                .as("checkLiquidation=true 只可能来自'扣光'那一级，false 说明走成了'够扣'、本用例没测到东西")
                .isTrue();
        assertThat(positionMapper.selectById(posId).getMargin()).isEqualByComparingTo("0");

        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getBizType()).isEqualTo(LedgerBizType.FUNDING_FEE_FROM_MARGIN);
        assertThat(rows.getFirst().getDelta())
                .as("扣款额必须是锁内读到的真实保证金 0.01，不是那笔算出来的资金费、也不是 0")
                .isEqualByComparingTo("-0.01");
        assertThat(rows.getFirst().getBalanceAfter()).isEqualByComparingTo("0");
    }

    /** 建号入口造的用户：直接 INSERT + 补一条 INITIAL_GRANT，起点就满足不变量 */
    private Long newUserWithGrant(String balance) {
        Long uid = newUser(balance);
        userService.recordInitialGrant(uid, new BigDecimal(balance));
        return uid;
    }

    /** 五个钱包逐个对不变量：SUM(delta) == user 表当列值（POSITION_MARGIN 不参与，见 LedgerWallet 注释） */
    private void assertAllWalletInvariants(Long uid) {
        User u = userMapper.selectById(uid);
        assertWallet(uid, LedgerWallet.BALANCE, u.getBalance());
        assertWallet(uid, LedgerWallet.FROZEN, u.getFrozenBalance());
        assertWallet(uid, LedgerWallet.GAME, u.getGameBalance());
        assertWallet(uid, LedgerWallet.LOAN_PRINCIPAL, u.getMarginLoanPrincipal());
        assertWallet(uid, LedgerWallet.LOAN_INTEREST, u.getMarginInterestAccrued());
    }

    private void assertBalanceInvariant(Long uid) {
        assertWallet(uid, LedgerWallet.BALANCE, userMapper.selectById(uid).getBalance());
    }

    private void assertWallet(Long uid, LedgerWallet wallet, BigDecimal expected) {
        assertThat(ledgerProbe.sumDeltaByWallet(uid, wallet.name()))
                .as("钱包 %s 的 SUM(delta) 必须等于 user 表当列值", wallet)
                .isEqualByComparingTo(expected == null ? BigDecimal.ZERO : expected);
    }
}
