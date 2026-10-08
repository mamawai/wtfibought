package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.dto.FuturesAddMarginRequest;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.entity.UserLedger;
import com.mawai.wiibcommon.enums.LedgerBizType;
import com.mawai.wiibcommon.enums.LedgerWallet;
import com.mawai.wiibsim.controller.LedgerController;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.ReturningRecordProbeMapper;
import com.mawai.wiibsim.mapper.LedgerProbeMapper;
import com.mawai.wiibsim.mapper.UserLedgerMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.MarginAccountService;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 账本真跑验收（非单测）：起完整 Spring 上下文、真连本地 PG。
 * 单测把 mapper mock 掉了，绿了不代表 UPDATE ... RETURNING 在
 * PG JDBC + MyBatis 这条链路上真能拿到值——该空白由本类补。
 * <p>
 * 末尾两条是整套账本的<b>总验收</b>：混合业务跑一轮后五个钱包账实相符、并发打同一行后余额链不断。
 * 累加式对账的适用范围（存量用户为什么不参与）写在 {@link #assertInvariant} 的注释里，别跳过。
 * <p>
 * 最后一组验的是<b>账单查询接口的读路径</b>（只能查自己的 / 游标翻页 / 类型筛选 / limit 封顶）——
 * 这四件事全在 SQL 里，mock 掉 mapper 一条都验不到。放本类是为了复用这里的建号 helper 与
 * {@link #清掉本次建的测试用户()} 清理，不再另造一套。
 * <p>
 * 跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=UserLedgerRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class UserLedgerRealRunTest {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ReturningRecordProbeMapper probeMapper;

    @Autowired
    private UserLedgerMapper ledgerMapper;
    @Autowired
    private LedgerProbeMapper ledgerProbe;

    @Autowired
    private UserService userService;

    @Autowired
    private MarginAccountService marginAccountService;

    @Autowired
    private FuturesTradingService futuresTradingService;

    @Autowired
    private FuturesPositionMapper positionMapper;

    @Autowired
    private LedgerController ledgerController;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdPositionIds = new ArrayList<>();

    /** 建个一次性用户，避免污染真实账号 */
    private Long newUser(String balance) {
        // nanoTime 只取一次：取两次会让 username 和 linux_do_id 的后缀对不上号，出事时不好关联
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
     * 建号形态的测试用户：INSERT 落 balance + 补一条 INITIAL_GRANT，起点就满足不变量。
     * 生产的三个建号入口（OAuth 首登、邀请码注册、量化建号）就是这个形态——建号走 INSERT，
     * 切面看不见，靠 recordInitialGrant 把期初余额记成账本第一行。
     * <p>
     * 别改成"建号余额给 0，再 atomicUpdateBalance 补 10000"：recordInitialGrant 本身就落一行
     * +10000，再补一枪切面又落一行 +10000，账本累加变 20000 而余额只有 10000，
     * 不变量当场破——那是测试自己造的假账，不是被测代码的问题。
     */
    private Long newUserWithGrant(String balance) {
        Long uid = newUser(balance);
        userService.recordInitialGrant(uid, new BigDecimal(balance));
        return uid;
    }

    /**
     * 连的是所有者的真实开发库，不是一次性容器：RankingService 那边 userService.list() 全量无过滤，
     * 留下的测试用户会直接爬进排行榜。所以每个用例跑完按 id 删干净。
     * <p>
     * 刻意不用"给测试类挂 @Transactional 靠回滚清理"那招：那会把整个用例塞进同一个 SqlSession，
     * 后面那些 selectById 断言就会读到一级缓存/未提交态，恰好把本类要验的东西（RETURNING 真落库、
     * 缓存没吞 SQL）废掉——清理手段不能反过来削掉测试的验证力。
     */
    @AfterEach
    void 清掉本次建的测试用户() {
        // 三张表都没建 FK，删 user 不会带走它们，得逐张点名：
        // user_ledger（切面每笔都落行）、futures_position（混合业务用例造的逐仓仓位）。
        // 加新用例前先想清楚它会往哪张表落行——这个项目已经因为漏删留过孤儿数据。
        createdPositionIds.forEach(positionMapper::deleteById);
        createdPositionIds.clear();
        createdUserIds.forEach(ledgerMapper::deleteByUserId);
        createdUserIds.forEach(userMapper::deleteById);
        createdUserIds.clear();
        // 混合业务用例用 LedgerCtx.mark 补语义，这里兜的是标注泄漏。
        // 泄漏路径不是"断言失败"：mark 后面紧跟的那次调用即使返 null 也会把标注消费掉
        // （LedgerAspect 的 takeMark 刻意放在 ret != null 之前），而每次 assertInvariant 之前都没有待消费的标注。
        // 真正够得着的是"service 方法在摸到 mapper 之前就早返回/抛异常"——本类的
        // mark(SPOT_SETTLE) + applyCashInflow 就是：它 amount<=0 直接 return、用户查不到直接抛，
        // 两条都不经过任何 atomic*，@AfterThrowing 切的又是 atomic*，也切不着，标注就留在线程上串到下一条用例。
        LedgerCtx.takeMark();
    }

    @Test
    void RETURNING能拿到变动后余额() {
        Long uid = newUser("1000.00");

        BigDecimal after = userMapper.atomicUpdateBalance(uid, new BigDecimal("-300.00"));

        assertThat(after).isEqualByComparingTo("700.00");
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("700.00");
    }

    @Test
    void 余额不足时返回null且余额不变() {
        Long uid = newUser("100.00");

        BigDecimal after = userMapper.atomicUpdateBalance(uid, new BigDecimal("-500.00"));

        assertThat(after).isNull();
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * 防 MyBatis 一级缓存吞掉资金 SQL（UserMapper.atomicUpdateBalance 上 @Options(flushCache) 的看门测试）。
     * <p>
     * 必须裹在同一个事务里测：事务外每次调用各开一个 SqlSession，用完即关，一级缓存活不过一次调用，
     * 怎么测都是绿的；只有同事务复用同一个 SqlSession 时缓存才留得住、才打得中这个坑。
     * 所以这里用 TransactionTemplate 手动圈事务——别看着像多余的包装就删了。
     * <p>
     * 摘掉 @Options(flushCache) 本用例即挂：第二次调用返缓存值 900.00、SQL 不发 DB，
     * 断到 second=800.00 那行就红（实测过）。
     */
    @Test
    void 同事务内重复扣款每次都真发SQL() {
        Long uid = newUser("1000.00");

        // 同参数（同 uid、同金额）连扣两次——一级缓存正是按"语句+参数"命中的，同参才打得中
        // 用 Arrays.asList 不用 List.of：万一返 null（不该发生），List.of 会抛 NPE 盖掉真正的断言信息
        List<BigDecimal> results = tx.execute(status -> Arrays.asList(
                userMapper.atomicUpdateBalance(uid, new BigDecimal("-100.00")),
                userMapper.atomicUpdateBalance(uid, new BigDecimal("-100.00"))));

        assertThat(results).isNotNull();
        assertThat(results.get(0)).isEqualByComparingTo("900.00");
        // 第二次若被缓存挡掉会是 900.00
        assertThat(results.get(1)).isEqualByComparingTo("800.00");
        // 返回值对了还不够，得确认两次都真落库了
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("800.00");
    }

    /**
     * RETURNING 多列 → record：Task 4 要定义 3 个 record、4 个多钱包方法全靠这条路，先钉死。
     * <p>
     * MyBatis 对 record 走构造器自动映射，argNameBasedConstructorAutoMapping 默认 false，
     * 是<b>按 RETURNING 的列序依次填组件</b>，不看列名（mapUnderscoreToCamelCase 在这条路径上不参与）。
     * 两个组件又都是 BigDecimal，类型检查兜不住，列序和组件顺序对不上就是静默返错值。
     * <p>
     * 所以这里刻意让两个钱包取不同的值（700 / 300）：一旦对调，两行断言都会红。
     * 若写成都是 500 就测不出对调——改这个用例的人注意别把值改成一样的。
     */
    @Test
    void RETURNING多列按列序映射进record() {
        Long uid = newUser("1000.00");   // frozen_balance 起始 0

        // 冻结 300：balance 1000→700，frozen_balance 0→300
        ReturningRecordProbeMapper.FreezeResult r =
                probeMapper.atomicFreezeBalanceProbe(uid, new BigDecimal("300.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("700.00");          // 对调则变 300.00
        assertThat(r.frozenBalance()).isEqualByComparingTo("300.00");    // 对调则变 700.00

        // record 版同样遵守 null=没改成：余额不够时整个 record 返 null，不是返一个装满 null 的 record
        assertThat(probeMapper.atomicFreezeBalanceProbe(uid, new BigDecimal("99999.00"))).isNull();
    }

    /**
     * 上面那条用的是探针 mapper，这条打真正在跑的 UserMapper.atomicFreezeBalance。
     * 同样刻意让两个钱包取不同值（600/400），列序写反两行断言都会红——别把值改成一样的。
     */
    @Test
    void 冻结返回两个钱包新值() {
        Long uid = newUser("1000.00");

        UserMapper.BalanceFrozen r = userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("600.00");          // 对调则变 400.00
        assertThat(r.frozenBalance()).isEqualByComparingTo("400.00");    // 对调则变 600.00
    }

    /**
     * 划转是唯一"两个钱包加减的金额不一样"的方法（差额=手续费），
     * 正好用来验 RETURNING balance, game_balance 的列序：900 / 99 差得远，对调必红。
     */
    @Test
    void 划转返回余额与游戏钱包新值() {
        Long uid = newUser("1000.00");

        // net = amount − 1% 手续费，转出扣 100、到账 99
        UserMapper.BalanceGame r = userMapper.atomicTransferToGame(
                uid, new BigDecimal("100.00"), new BigDecimal("99.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("900.00");      // 对调则变 99.00
        assertThat(r.gameBalance()).isEqualByComparingTo("99.00");   // 对调则变 900.00
    }

    /**
     * 解冻单独测一遍。它的 RETURNING 列序和冻结那条一模一样，所以上面那条用例保护不到它——
     * 谁把这条的列序写反，就是"静默错账 + 零测试"。
     * 先冻 400 垫出冻结余额，再解冻 100，落到 700 / 300，两值不同，对调必红。
     */
    @Test
    void 解冻返回两个钱包新值() {
        Long uid = newUser("1000.00");

        // 垫场：balance 1000→600，frozen 0→400
        assertThat(userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"))).isNotNull();

        UserMapper.BalanceFrozen r = userMapper.atomicUnfreezeBalance(uid, new BigDecimal("100.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("700.00");          // 对调则变 300.00
        assertThat(r.frozenBalance()).isEqualByComparingTo("300.00");    // 对调则变 700.00
    }

    /**
     * 反向划转单独测一遍：它的 SET 是"先 game 后 balance"、RETURNING 是"先 balance 后 game"，
     * 两边顺序天生不一致，最容易被人"顺手对齐"成 RETURNING game_balance, balance——那就静默错账。
     * 顺带验了 atomicUpdateGameBalance 的返回值。
     */
    @Test
    void 反向划转的列序不跟着SET走() {
        Long uid = newUser("1000.00");

        assertThat(userMapper.atomicUpdateGameBalance(uid, new BigDecimal("200.00")))
                .isEqualByComparingTo("200.00");

        // 游戏钱包扣 100、余额到账 99（1% 手续费）
        UserMapper.BalanceGame r = userMapper.atomicTransferToBalance(
                uid, new BigDecimal("100.00"), new BigDecimal("99.00"));

        assertThat(r).isNotNull();
        assertThat(r.balance()).isEqualByComparingTo("1099.00");     // 对调则变 100.00
        assertThat(r.gameBalance()).isEqualByComparingTo("100.00");  // 对调则变 1099.00
    }

    /**
     * CashInflow 是三列 record，列序最容易写反的一个：三个组件全是 BigDecimal，
     * 对调不报错，就是把利息当本金、把本金当余额记进账。
     * 所以三个新值刻意互不相同（20 / 400 / 1005），任意两列对调都会红。
     * 顺带验了 atomicAddMarginLoanPrincipal / atomicAccrueInterest 的返回值。
     */
    @Test
    void 现金流入返回三列新值() {
        Long uid = newUser("1000.00");

        // 先欠上：本金 500、利息 30（两列 DB 默认 0，用真方法加上去，顺便测它们的返回值）
        assertThat(userMapper.atomicAddMarginLoanPrincipal(uid, new BigDecimal("500.00")))
                .isEqualByComparingTo("500.00");
        assertThat(userMapper.atomicAccrueInterest(uid, new BigDecimal("30.00"), LocalDate.now()))
                .isEqualByComparingTo("30.00");

        // 还息 10、还本 100，剩 5 入余额
        UserMapper.CashInflow r = userMapper.atomicApplyCashInflow(uid,
                new BigDecimal("10.00"), new BigDecimal("100.00"), new BigDecimal("5.00"));

        assertThat(r).isNotNull();
        assertThat(r.marginInterestAccrued()).isEqualByComparingTo("20.00");
        assertThat(r.marginLoanPrincipal()).isEqualByComparingTo("400.00");
        assertThat(r.balance()).isEqualByComparingTo("1005.00");
    }

    /**
     * 切面的根本保证：业务代码一行没改、一个注解没加，钱动了账就自动落地。
     * 这里刻意不标注，走的是"没标注→UNKNOWN 兜底"那条路，验的是"不漏"而不是语义。
     */
    @Test
    void 无标注也落账且不变量成立() {
        Long uid = newUser("1000.00");

        userMapper.atomicUpdateBalance(uid, new BigDecimal("-300.00"));
        userMapper.atomicUpdateBalance(uid, new BigDecimal("50.00"));

        // 账本累加 == 当前余额减建号余额。本类的测试用户是直接 INSERT 造的、没走建号入口，
        // 所以没有那条 INITIAL_GRANT（真建号路径的不变量由 LedgerProxyRealRunTest 验）
        BigDecimal sum = ledgerProbe.sumDeltaByWallet(uid, "BALANCE");
        assertThat(sum).isEqualByComparingTo("-250.00");
        assertThat(userMapper.selectById(uid).getBalance()).isEqualByComparingTo("750.00");

        // 光看求和还不够：得确认走的是"没标注→UNKNOWN 兜底"这条路，而不是恰好凑对了数
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 10);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getWallet()).isEqualTo(LedgerWallet.BALANCE);
            assertThat(row.getBizType()).isEqualTo(LedgerBizType.UNKNOWN);
        });
        // balanceAfter 取自同条 UPDATE 的 RETURNING，不是事后补查的——倒序第一条是那笔 +50
        assertThat(rows.get(0).getBalanceAfter()).isEqualByComparingTo("750.00");
        assertThat(rows.get(1).getBalanceAfter()).isEqualByComparingTo("700.00");
    }

    /**
     * 切面到底拦住了几个方法——11 个原子资金方法一次全打一枪，逐个数行数、逐个钱包对不变量。
     * <p>
     * 只写一句"pointcut 是 atomic*，应该都能拦到"是空话：方法名拼错、或者方法没进
     * LedgerRowMapping.HANDLED_METHODS（入口闸门返空 List、切面 isEmpty 跳过，静默不记账），
     * 都是这种"看着对、实际漏"的错。少拦任何一个方法，行数断言和它对应钱包的不变量断言会<b>同时</b>红。
     * <p>
     * 分工：本用例守的是"现存这 11 个都真被拦到"；"将来新增第 12 个别忘补映射"
     * 由 LedgerRowMappingTest.新增atomic方法必须补映射() 那条反射守卫负责——
     * 本用例的 11 次调用和 17 行断言全是硬编码，对新方法天生无感。
     * <p>
     * 每一步都刻意走成功路径并断言返回值非 null：返 null 的调用切面本来就不记账，
     * 那样这个用例会"因为没扣成钱所以没账"而假绿。
     */
    @Test
    void 十一个原子资金方法全被切面拦住() {
        Long uid = newUser("1000.00");
        LocalDate today = LocalDate.now();

        // balance 1000 → 700
        assertThat(userMapper.atomicUpdateBalance(uid, new BigDecimal("-300.00"))).isNotNull();          // 1 行
        // balance 700 → 650
        assertThat(userMapper.atomicSettleBalance(uid, new BigDecimal("-50.00"))).isNotNull();           // 1 行
        // balance 650 → 250，frozen 0 → 400
        assertThat(userMapper.atomicFreezeBalance(uid, new BigDecimal("400.00"))).isNotNull();           // 2 行
        // balance 250 → 350，frozen 400 → 300
        assertThat(userMapper.atomicUnfreezeBalance(uid, new BigDecimal("100.00"))).isNotNull();         // 2 行
        // frozen 300 → 0
        assertThat(userMapper.atomicDeductFrozenBalance(uid, new BigDecimal("300.00"))).isNotNull();     // 1 行
        // game 0 → 200
        assertThat(userMapper.atomicUpdateGameBalance(uid, new BigDecimal("200.00"))).isNotNull();       // 1 行
        // balance 350 → 250，game 200 → 299（1 元手续费销毁）
        assertThat(userMapper.atomicTransferToGame(uid,
                new BigDecimal("100.00"), new BigDecimal("99.00"))).isNotNull();                         // 2 行
        // game 299 → 249，balance 250 → 299（1 元手续费销毁）
        assertThat(userMapper.atomicTransferToBalance(uid,
                new BigDecimal("50.00"), new BigDecimal("49.00"))).isNotNull();                          // 2 行
        // 本金 0 → 500
        assertThat(userMapper.atomicAddMarginLoanPrincipal(uid, new BigDecimal("500.00"))).isNotNull();  // 1 行
        // 利息 0 → 30
        assertThat(userMapper.atomicAccrueInterest(uid, new BigDecimal("30.00"), today)).isNotNull();    // 1 行
        // 还息 10、还本 100、入账 40：利息 30→20，本金 500→400，balance 299→339
        assertThat(userMapper.atomicApplyCashInflow(uid, new BigDecimal("10.00"),
                new BigDecimal("100.00"), new BigDecimal("40.00"))).isNotNull();                          // 3 行

        // 1+1+2+2+1+1+2+2+1+1+3 = 17
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 100);
        assertThat(rows).hasSize(17);

        User u = userMapper.selectById(uid);
        // BALANCE 起始是 1000 不是 0（本类用户直接 INSERT 造的，没有 INITIAL_GRANT），所以减掉起始值再比
        assertThat(ledgerProbe.sumDeltaByWallet(uid, "BALANCE"))
                .isEqualByComparingTo(u.getBalance().subtract(new BigDecimal("1000.00")));
        // 另外四个钱包起始都是 0，账本累加应当直接等于 user 表当前值
        assertThat(ledgerProbe.sumDeltaByWallet(uid, "FROZEN")).isEqualByComparingTo(u.getFrozenBalance());
        assertThat(ledgerProbe.sumDeltaByWallet(uid, "GAME")).isEqualByComparingTo(u.getGameBalance());
        assertThat(ledgerProbe.sumDeltaByWallet(uid, "LOAN_PRINCIPAL"))
                .isEqualByComparingTo(u.getMarginLoanPrincipal());
        assertThat(ledgerProbe.sumDeltaByWallet(uid, "LOAN_INTEREST"))
                .isEqualByComparingTo(u.getMarginInterestAccrued());

        // 每个钱包最后一行的 balance_after 必须等于 user 表当前值。
        // 求和只看 delta，取错 record 组件（把可用余额写成冻结余额）它是发现不了的：
        // delta 来自入参、根本不过 record。这条才咬得住"RETURNING → record → 映射 → 落库"整条链。
        assertLatestBalanceAfter(rows, LedgerWallet.BALANCE, u.getBalance());
        assertLatestBalanceAfter(rows, LedgerWallet.FROZEN, u.getFrozenBalance());
        assertLatestBalanceAfter(rows, LedgerWallet.GAME, u.getGameBalance());
        assertLatestBalanceAfter(rows, LedgerWallet.LOAN_PRINCIPAL, u.getMarginLoanPrincipal());
        assertLatestBalanceAfter(rows, LedgerWallet.LOAN_INTEREST, u.getMarginInterestAccrued());
    }

    /** rows 是 id 倒序（selectByCursor 保证），所以某钱包的第一条就是它最后一次变动 */
    private static void assertLatestBalanceAfter(List<UserLedger> rows, LedgerWallet wallet, BigDecimal expected) {
        UserLedger latest = rows.stream()
                .filter(r -> r.getWallet() == wallet)
                .findFirst()
                .orElseThrow(() -> new AssertionError("钱包 " + wallet + " 一条流水都没有"));
        assertThat(latest.getBalanceAfter())
                .as("钱包 %s 最后一行的 balance_after", wallet)
                .isEqualByComparingTo(expected);
    }

    // ==================== 总验收：混合业务账实相符 + 并发一致性 ====================

    /**
     * 总验收一：跑一轮混合业务，五个钱包逐个对账实相符。
     * <p>
     * 上面那些用例都是单机制单打（RETURNING 拿得到值 / 列序没写反 / 一级缓存没吞 SQL / 切面拦得住），
     * 缺的就是一句"混着跑一轮，账还是平的"。本用例补这一句。
     * <p>
     * 每组跑完立刻对一次不变量、且末尾把五列的绝对值也钉死：不变量比的是"账本 vs user 表"，
     * 某一步整个没生效的话两边可能一起不动、照样自洽——绝对值那几行才咬得住"这一轮真跑了什么"。
     * <p>
     * 打的是各业务真实用的那个资金入口（现货买入=updateBalance、限价单=freeze/unfreeze/
     * deductFrozen、游戏=updateGameBalance、合约=真走 addMargin 那条 public 入口）；
     * 生产里语义由调用方的 @Ledger/mark 给，跨包调不到那些 protected 入口，所以这里用
     * LedgerCtx.mark 补成生产里的同一个值——<b>标注本身生效不生效由 LedgerProxyRealRunTest 验，
     * 本用例只验账平</b>。
     * <p>
     * <b>刻意不在本轮里的</b>：建号 / 爆仓 / 破产恢复 / 资金费吃仓位保证金。那四条是切面射程外的
     * 显式补记，各自的不变量断言已经在 LedgerProxyRealRunTest（那个类放在 service.impl 包
     * 就是为了够得着这几个 protected 入口）。在这儿照抄两行 SQL 假装"资金费也覆盖了"没有意义。
     */
    @Test
    void 混合业务跑一轮后五个钱包账实相符() {
        Long uid = newUserWithGrant("10000.00");
        LocalDate today = LocalDate.now();

        // ===== 现货：市价买入 + 限价单冻结 / 部分撤单 / 成交扣冻结（末尾刻意留 100 冻结未成交）=====
        LedgerCtx.mark(LedgerBizType.SPOT_BUY);
        userService.updateBalance(uid, new BigDecimal("-1200.00"));         // 买入扣款含手续费
        LedgerCtx.mark(LedgerBizType.SPOT_LIMIT_FREEZE);
        userService.freezeBalance(uid, new BigDecimal("500.00"));           // 挂限价买单冻结
        LedgerCtx.mark(LedgerBizType.SPOT_LIMIT_UNFREEZE);
        userService.unfreezeBalance(uid, new BigDecimal("200.00"));         // 撤掉一部分
        LedgerCtx.mark(LedgerBizType.SPOT_LIMIT_DEDUCT);
        userService.deductFrozenBalance(uid, new BigDecimal("200.00"));     // 成交扣冻结，还剩 100 挂着
        assertInvariant(uid, "现货组");

        // ===== 杠杆：借款 → 计息 → 卖出到账自动还息还本、余下入余额 → 再借再计息 =====
        marginAccountService.addLoanPrincipal(uid, new BigDecimal("2000.00"));
        // 计息真入口是 protected 的 accrueUserInterest（跨包调不到，另一条 accrueDailyInterest 扫全库
        // 会把所有者的真实账号一起计息，绝不能调），这里直打它内部那条 SQL
        LedgerCtx.mark(LedgerBizType.MARGIN_INTEREST_ACCRUE);
        userMapper.atomicAccrueInterest(uid, new BigDecimal("20.00"), today);
        // 一条 SQL 动三列 → 三行：还息 20、还本 2000、余下 480 入余额
        LedgerCtx.mark(LedgerBizType.SPOT_SETTLE);
        marginAccountService.applyCashInflow(uid, new BigDecimal("2500.00"), "现货卖出到账");
        marginAccountService.addLoanPrincipal(uid, new BigDecimal("600.00"));   // 再借一笔，让本金收尾非 0
        LedgerCtx.mark(LedgerBizType.MARGIN_INTEREST_ACCRUE);
        userMapper.atomicAccrueInterest(uid, new BigDecimal("9.00"), today);    // 利息收尾也非 0
        assertInvariant(uid, "杠杆组");

        // ===== 游戏钱包：划转进去（1% 手续费销毁）→ 预测下注 → 结算 → 划回 =====
        userService.transferToGame(uid, new BigDecimal("1000.00"));         // 扣 1000、到账 990
        LedgerCtx.mark(LedgerBizType.PREDICTION_BUY);
        userService.updateGameBalance(uid, new BigDecimal("-150.00"));
        LedgerCtx.mark(LedgerBizType.PREDICTION_SETTLE);
        userService.updateGameBalance(uid, new BigDecimal("380.00"));
        userService.transferToBalance(uid, new BigDecimal("200.00"));       // 扣 200、到账 198
        assertInvariant(uid, "游戏组");

        // ===== 合约：追加保证金（真 public 入口，余额→仓位保证金搬家）=====
        Long posId = newIsolatedPosition(uid, "BTCUSDT", new BigDecimal("200.00"));
        FuturesAddMarginRequest req = new FuturesAddMarginRequest();
        req.setPositionId(posId);
        req.setAmount(new BigDecimal("100.00"));
        futuresTradingService.addMargin(uid, req);
        assertInvariant(uid, "合约组");

        // ===== 收尾：五列绝对值逐个钉死 =====
        User u = userMapper.selectById(uid);
        // 10000 −1200 −500(冻) +200(解) +480(到账) −1000(划出) +198(划回) −100(保证金)
        assertThat(u.getBalance()).isEqualByComparingTo("8078.00");
        assertThat(u.getFrozenBalance()).isEqualByComparingTo("100.00");     // 500 −200 −200
        assertThat(u.getGameBalance()).isEqualByComparingTo("1020.00");      // 990 −150 +380 −200
        assertThat(u.getMarginLoanPrincipal()).isEqualByComparingTo("600.00");
        assertThat(u.getMarginInterestAccrued()).isEqualByComparingTo("9.00");

        // 行数是"不漏也不多记"的另一面：求和对得上但少了一行加一行凑数的情况，这里会红。
        // 1 建号 +1 买入 +2 冻结 +2 解冻 +1 扣冻结 +1 借款 +1 计息 +3 现金流入
        // +1 再借 +1 再计息 +2 划出 +1 下注 +1 派彩 +2 划回 +1 追加保证金 = 21
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 500);
        assertThat(rows).hasSize(21);
        // 全程每一步都有语义（mark 或 @Ledger），一条 UNKNOWN 都不该有：
        // 出现 UNKNOWN 说明有标注被上一笔提前吃掉了，或者哪一步的资金入口换了
        assertThat(rows).noneMatch(r -> r.getBizType() == LedgerBizType.UNKNOWN);

        // POSITION_MARGIN 排除在不变量之外的现场证据：追加保证金把仓位保证金从 200 顶到 300，
        // 而这个钱包一行流水都没有——那 100 已经在 BALANCE 侧记过，再记一遍就是重复。
        // 谁把 POSITION_MARGIN 加进 assertInvariant，这两行就是反例。
        assertThat(positionMapper.selectById(posId).getMargin()).isEqualByComparingTo("300.00");
        assertThat(ledgerProbe.sumDeltaByWallet(uid, LedgerWallet.POSITION_MARGIN.name()))
                .as("保证金搬家不该在 POSITION_MARGIN 留行")
                .isEqualByComparingTo("0");
    }

    /** 并发验收的任务数：一半扣余额、一半冻结 */
    private static final int CONCURRENT_TASKS = 20;

    /**
     * 总验收二：并发打同一个用户，最终余额 / 账本累加 / balance_after 链三者必须自洽。
     * <p>
     * 这条直接回答改造之初那个关切——"原来一条 SQL 更新余额，现在多了一条账本 INSERT，
     * 会不会引入并发问题"。答案的机制是：同一用户的所有资金 SQL 都 UPDATE user 表同一行，
     * PG 行锁天然把它们排成串行；只要账本 INSERT 与余额 UPDATE 在同一事务内，
     * 锁在 COMMIT 才放，账本 id 的先后就等于真实变动的先后。
     * <p>
     * <b>所以每个任务必须裹在 tx.execute 里，这不是装饰</b>：updateBalance/freezeBalance 自己
     * 没有 @Transactional（生产里的事务边界在调用方那 27 个 @Transactional 业务方法上）。
     * 不裹的话 UPDATE 自动提交后行锁就放了，账本 INSERT 落在锁外，
     * A 的 INSERT 可能排到 B 之后 —— 余额和求和照样对，但 balance_after 链会断
     * （实测过：摘掉 tx.execute，最终余额 700 和五个钱包的求和全绿，
     * 链上出现 "balanceAfter=790 而上一条 balanceAfter=1000" 直接红）。
     * 换句话说：这条用例裹事务是在<b>复刻生产形态</b>，不是为了让断言好看。
     * <p>
     * <b>这条实测反过来说明了生产上的一个风险，值得改代码的人记住</b>：一个不在事务里的
     * {@code atomic*} 调用点，<b>在并发命中同一用户那一行时</b>就会让<b>真账本</b>的
     * balance_after 审计链损坏——账单上出现"上一行 1000、下一行 790 而 delta 只有 −10"，
     * 而 {@code SUM(delta)} 照样对得上，也就是说<b>对账脚本查不出这类问题</b>。
     * 串行调用<b>不会</b>断链：UPDATE 与 INSERT 一前一后紧挨着，ledger 的 id 序仍然等于时间序，
     * 别拿这条去排查串行路径。但事务外还有一笔与并发无关的账：UPDATE 已经自动提交，
     * 紧跟的 INSERT 再失败就是余额变了账没记，事后补不回来。两条都只能靠审查看调用点在不在事务里。
     * 现状：全部 {@code atomic*} 调用点都在事务内——要么是 public {@code @Transactional} 入口
     * （CryptoOrderServiceImpl.buy/sell、UserServiceImpl.transferToGame、
     * MarginAccountServiceImpl.addLoanPrincipal/applyCashInflow…），
     * 要么是类内事务段经 TransactionTemplate / LedgerTx 开的编程式事务（预测盘也是 TransactionTemplate）。
     * 但这一点<b>没有任何自动化守卫</b>，只有代码审查兜着；唯一相关的真跑覆盖是
     * {@code LedgerProxyRealRunTest#类内事务段抛异常时资金必须回滚()} 那一条路径。
     * （刻意不加运行时检查：现存路径一条都没漏，为将来可能的回归在每笔资金变动上付常驻成本不值当。）
     * <p>
     * 刻意混两种资金 SQL（扣余额 / 冻结）而不是同一句打 20 遍：要验的是不同语句抢同一行时
     * 账本顺序仍然自洽，只打一句测不出来。冻结那半边顺带把 FROZEN 的链也验了。
     */
    @Test
    void 并发资金变动后账实相符且余额链连续() throws Exception {
        Long uid = newUserWithGrant("1000.00");

        List<Future<?>> futures = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_TASKS; i++) {
                boolean freeze = i % 2 == 0;
                futures.add(pool.submit(() -> tx.execute(status -> {
                    if (freeze) {
                        userService.freezeBalance(uid, new BigDecimal("20.00"));
                    } else {
                        userService.updateBalance(uid, new BigDecimal("-10.00"));
                    }
                    return null;
                })));
            }
        }   // close() 会等全部跑完
        // submit 会把异常吞进 Future：不逐个 get，20 个线程全炸了本用例也可能"绿"
        for (Future<?> f : futures) {
            f.get();
        }

        User u = userMapper.selectById(uid);
        assertThat(u.getBalance()).isEqualByComparingTo("700.00");     // 1000 − 10×20(冻结) − 10×10(扣款)
        assertThat(u.getFrozenBalance()).isEqualByComparingTo("200.00");
        assertInvariant(uid, "并发后");
        // 1 建号 + 10×2（冻结一条 SQL 动两个钱包）+ 10 扣款 = 31
        assertThat(ledgerMapper.selectByCursor(uid, null, null, 500)).hasSize(31);

        assertBalanceChain(uid, LedgerWallet.BALANCE);
        assertBalanceChain(uid, LedgerWallet.FROZEN);
    }

    /**
     * 不变量口径：账本只记上线之后的资金变动，期初余额不回填。
     * "流水累加 == user 表当前值"只对账本上线后建号的用户成立（INITIAL_GRANT 是期初基准，
     * 本类一律用 {@link #newUserWithGrant} 新建用户断言）；对存量用户不成立且是刻意口径，
     * <b>不许拿真库既有 userId 跑这个断言</b>，真库对账对不上也别当漏账追。
     * POSITION_MARGIN 永远不参与：余额↔保证金搬家已在 BALANCE 侧记过（见 LedgerWallet 注释）。
     */
    private void assertInvariant(Long uid, String stage) {
        User u = userMapper.selectById(uid);
        assertWalletInvariant(uid, LedgerWallet.BALANCE, u.getBalance(), stage);
        assertWalletInvariant(uid, LedgerWallet.FROZEN, u.getFrozenBalance(), stage);
        assertWalletInvariant(uid, LedgerWallet.GAME, u.getGameBalance(), stage);
        assertWalletInvariant(uid, LedgerWallet.LOAN_PRINCIPAL, u.getMarginLoanPrincipal(), stage);
        assertWalletInvariant(uid, LedgerWallet.LOAN_INTEREST, u.getMarginInterestAccrued(), stage);
    }

    private void assertWalletInvariant(Long uid, LedgerWallet wallet, BigDecimal actual, String stage) {
        assertThat(ledgerProbe.sumDeltaByWallet(uid, wallet.name()))
                .as("[%s] 钱包 %s 账实不符：账本累加 ≠ user 表当列值（映射表漏记或多记，回 LedgerRowMapping 查）",
                        stage, wallet)
                .isEqualByComparingTo(actual == null ? BigDecimal.ZERO : actual);
    }

    /**
     * 某钱包的流水必须是一条连续的链：每条的 {@code balanceAfter − delta} 等于上一条的 {@code balanceAfter}。
     * <p>
     * 求和对得上还不够：两笔并发变动若基于同一个旧值各算各的，求和照样对，但链上会出现跳变。
     * 这条才是"并发没把一致性打破"的硬证据。
     * <p>
     * 隐含前提：id 序 == 时间序，靠 user_ledger_id_seq 不带 CACHE（seqcache=1）。
     * 给序列加 CACHE 会让本断言静默失效（随机红绿），改序列前先看这里。
     */
    private void assertBalanceChain(Long uid, LedgerWallet wallet) {
        // selectByCursor 是 id 倒序，reversed() 转成 id 升序 —— 同事务内 INSERT 的 id 顺序即真实变动顺序
        List<UserLedger> rows = ledgerMapper.selectByCursor(uid, null, null, 500).reversed()
                .stream().filter(r -> r.getWallet() == wallet).toList();
        assertThat(rows).as("钱包 %s 一条流水都没有，本断言什么都没验到", wallet).isNotEmpty();

        BigDecimal prev = null;
        for (UserLedger r : rows) {
            if (prev != null) {
                assertThat(r.getBalanceAfter().subtract(r.getDelta()))
                        .as("钱包 %s 余额链在第 %d 条断裂：balanceAfter=%s delta=%s，上一条 balanceAfter=%s",
                                wallet, r.getId(), r.getBalanceAfter(), r.getDelta(), prev)
                        .isEqualByComparingTo(prev);
            }
            prev = r.getBalanceAfter();
        }
    }

    /** 造一张逐仓 OPEN 仓位，字段只填 NOT NULL 的那些 */
    private Long newIsolatedPosition(Long userId, String symbol, BigDecimal margin) {
        FuturesPosition p = new FuturesPosition();
        p.setUserId(userId);
        p.setSymbol(symbol);
        p.setSide("LONG");
        p.setMarginMode(FuturesPosition.ISOLATED);
        p.setLeverage(10);
        p.setQuantity(new BigDecimal("0.00100000"));
        p.setEntryPrice(new BigDecimal("20000.00"));
        p.setMargin(margin);
        p.setFundingFeeTotal(BigDecimal.ZERO);
        p.setStatus("OPEN");
        positionMapper.insert(p);
        createdPositionIds.add(p.getId());
        return p.getId();
    }

    // ==================== 账单查询接口（读路径）====================

    /**
     * 直接插一行流水当查询夹具，不走真业务。
     * <p>
     * 查询接口不关心行是怎么来的，走真业务造 100 多行既慢、又把用例搅成"业务 + 查询"混合体，
     * 断言红了分不清是哪边坏的。写路径（切面落账、balance_after 取自 RETURNING）由本类前面那些
     * 用例负责，这里只喂读路径。balance_after 随便填 0 也是这个道理——读路径不看它。
     */
    private Long insertRow(Long uid, LedgerBizType type) {
        UserLedger e = new UserLedger();
        e.setUserId(uid);
        e.setWallet(LedgerWallet.BALANCE);
        e.setBizType(type);
        e.setDelta(new BigDecimal("-1.00"));
        e.setBalanceAfter(BigDecimal.ZERO);
        ledgerMapper.insert(e);
        return e.getId();
    }

    /** 走真 controller 而不是直接打 mapper：limit 封顶在 controller 里，跳过它就验不到 */
    private List<UserLedger> query(Long uid, LedgerBizType bizType, Long beforeId, int limit) {
        return ledgerController.list(uid, bizType, beforeId, limit).getData();
    }

    private static List<Long> ids(List<UserLedger> rows) {
        return rows.stream().map(UserLedger::getId).toList();
    }

    /**
     * 最要紧的一条：只能查自己的。刻意双向都查一遍——只查一边的话，
     * "把 userId 当死值筛"这种错有一半概率蒙对。
     */
    @Test
    void 账单只返回自己的流水() {
        Long me = newUserWithGrant("1000.00");
        Long other = newUserWithGrant("1000.00");
        Long myRow = insertRow(me, LedgerBizType.SPOT_BUY);
        Long otherRow = insertRow(other, LedgerBizType.SPOT_BUY);

        List<UserLedger> mine = query(me, null, null, 30);
        assertThat(mine).hasSize(2);          // 建号那条 + 刚插的那条
        assertThat(mine).allSatisfy(r -> assertThat(r.getUserId()).isEqualTo(me));
        assertThat(ids(mine)).contains(myRow).doesNotContain(otherRow);

        List<UserLedger> theirs = query(other, null, null, 30);
        assertThat(theirs).hasSize(2);
        assertThat(theirs).allSatisfy(r -> assertThat(r.getUserId()).isEqualTo(other));
        assertThat(ids(theirs)).contains(otherRow).doesNotContain(myRow);
    }

    /**
     * 游标翻页真的往前翻：第二页不含第一页任何一条、整页 id 都比第一页最小的还小，翻到底返空。
     * <p>
     * 把 SQL 里的 {@code id &lt; #{beforeId}} 写成 {@code &gt;} 或者漏掉，第二页会重复第一页
     * （doesNotContainAnyElementsOf 红）；把 {@code ORDER BY id DESC} 写成 ASC，倒序断言红。
     */
    @Test
    void 游标翻页往前翻不重不漏() {
        Long uid = newUserWithGrant("1000.00");       // 建号 1 行
        for (int i = 0; i < 6; i++) {
            insertRow(uid, LedgerBizType.SPOT_BUY);   // 共 7 行
        }

        List<UserLedger> p1 = query(uid, null, null, 3);
        assertThat(p1).hasSize(3);
        assertThat(ids(p1)).isSortedAccordingTo(Comparator.reverseOrder());

        List<UserLedger> p2 = query(uid, null, p1.getLast().getId(), 3);
        assertThat(p2).hasSize(3);
        assertThat(ids(p2)).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(ids(p2)).doesNotContainAnyElementsOf(ids(p1));
        assertThat(p2.getFirst().getId()).isLessThan(p1.getLast().getId());

        // 第三页只剩建号那条，再翻一页空——"返回空数组即到底"这个前端契约
        List<UserLedger> p3 = query(uid, null, p2.getLast().getId(), 3);
        assertThat(p3).hasSize(1);
        assertThat(p3.getFirst().getBizType()).isEqualTo(LedgerBizType.INITIAL_GRANT);
        assertThat(query(uid, null, p3.getLast().getId(), 3)).isEmpty();

        // 三页并起来正好是全部 7 行、无重复：翻页既没漏也没重
        assertThat(ids(p1)).doesNotContainAnyElementsOf(ids(p3));
        assertThat(ids(p2)).doesNotContainAnyElementsOf(ids(p3));
    }

    /** bizType 筛选真的生效——末尾那句"不筛时 4 行"是防"本来就只有 2 行"的假绿 */
    @Test
    void bizType筛选只返回该类型() {
        Long uid = newUserWithGrant("1000.00");       // INITIAL_GRANT 1 行
        Long buy1 = insertRow(uid, LedgerBizType.SPOT_BUY);
        insertRow(uid, LedgerBizType.PREDICTION_BUY);
        Long buy2 = insertRow(uid, LedgerBizType.SPOT_BUY);

        List<UserLedger> rows = query(uid, LedgerBizType.SPOT_BUY, null, 30);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.getBizType()).isEqualTo(LedgerBizType.SPOT_BUY));
        assertThat(ids(rows)).containsExactly(buy2, buy1);   // 筛完仍是 id 倒序

        assertThat(query(uid, null, null, 30)).hasSize(4);
    }

    /**
     * limit 上限真的卡住：库里有 106 行，传 Integer.MAX_VALUE 也只回 100。
     * <p>
     * 末尾那句直打 mapper 拿 106 是<b>防假绿的关键</b>：不确认库里真有超过封顶的行数，
     * "只回 100 条"可能只是因为本来就没那么多。
     */
    @Test
    void limit上限卡住不让一把拉全表() {
        Long uid = newUserWithGrant("1000.00");        // 建号 1 行
        for (int i = 0; i < 105; i++) {
            insertRow(uid, LedgerBizType.SPOT_BUY);    // 共 106 行
        }

        assertThat(query(uid, null, null, Integer.MAX_VALUE)).hasSize(100);
        assertThat(query(uid, null, null, 1000)).hasSize(100);
        // 负数不兜到 1 的话 PG 直接报 "LIMIT must not be negative"，一个手搓请求就是 500
        assertThat(query(uid, null, null, -1)).hasSize(1);

        // 封顶之外的行确实存在，上面三条才不是"本来就没那么多"
        assertThat(ledgerMapper.selectByCursor(uid, null, null, 1000)).hasSize(106);
    }
}
