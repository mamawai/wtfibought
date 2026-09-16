package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 投票结算。不起 Spring、不连库、<b>不联网</b>。
 * 行情客户端 mock 掉，喂固定 K 线串摆出"涨/跌/平/取不到"四种局面。
 * 样本按 getFuturesKlinesLight 的实测形状造（每行 8 元素、close 在下标 4），
 * open/high/low 故意填无关常量——实现读错下标用例立刻红。
 * <p>
 * CAS 幂等在这里测不了（WHERE result IS NULL 是数据库侧的事），由
 * {@link com.mawai.wiibsim.campaign.CampaignVoteSettleRealRunTest} 在真库上钉；
 * 本类管 Java 侧：谁赢谁输、每票分多少、拿不到价时是不是真的一行都不写。
 */
class CampaignVoteSettleTest {

    private static final long CAMPAIGN_ID = 7L;

    /**
     * 活动首日（服务器本地日），poolOf 的起算点。
     * 按"今天往前推"不写死日期：settleDay 只结已过完的 UTC 日，写死未来日期用例全成空跑。
     */
    private static final LocalDate START = CampaignVoteService.utcToday().minusDays(31);

    /**
     * 能落到 vote_date 上的第一个与最后一个 UTC 日。
     * <p>
     * 活动本地窗口 [首日 00:00, 首日+14 00:00) 换成 UTC 是 [首日前一天 16:00, 首日+13 16:00)，
     * 所以<b>投得出票</b>的 UTC 日是 首日−1 ~ 首日+13；而票盖的是<b>次日</b>的戳，
     * 于是 vote_date 的取值范围整体后移一天：<b>首日 ~ 首日+14</b>，仍是 15 个。
     * 首日那批是 UTC 首日−1 的 16:00-23:55 投出来的（不到 8 小时），
     * 末日那批是 UTC 首日+13 的 00:05-16:00 投出来的。
     */
    private static final LocalDate FIRST_UTC_VOTE_DAY = START;
    private static final LocalDate LAST_UTC_VOTE_DAY = START.plusDays(14);

    /**
     * 中段三个连着的投票日。取 首日+1 起头是有讲究的：{@code poolOf} 的天数按"票是哪天投出来的"
     * （= 投票日−1）算，首日+1 这批投在 UTC 首日那天，累计上限正好是 1×100 ——
     * 于是这三天的上限是干净的 100 / 200 / 300，下面所有中段用例的算术都建在这上面。
     * 首日那批（投在活动开始前的那几小时）是另一回事，由两条边界用例单独钉。
     */
    private static final LocalDate DAY1 = START.plusDays(1);
    private static final LocalDate DAY2 = DAY1.plusDays(1);
    private static final LocalDate DAY3 = DAY1.plusDays(2);

    private static final String BTC = CampaignVote.SYMBOL_BTC;
    private static final String GOLD = CampaignVote.SYMBOL_GOLD;

    private CampaignVoteMapper voteMapper;
    private CampaignMapper campaignMapper;
    private BinanceRestClient binance;
    private CampaignVoteService service;

    /** 手搓票的自增 id，逐票断言时靠它认行 */
    private long nextVoteId;

    @BeforeEach
    void setUp() {
        voteMapper = mock(CampaignVoteMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        binance = mock(BinanceRestClient.class);
        service = new CampaignVoteService(voteMapper, new CampaignService(campaignMapper, new MessageCatalog()),
                binance, new MessageCatalog());
        nextVoteId = 1;

        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setStartAt(START.atStartOfDay());
        c.setEndAt(START.plusDays(14).atStartOfDay());
        c.setStatus(Campaign.STATUS_RUNNING);
        when(campaignMapper.selectActive()).thenReturn(c);

        // 默认此前一分没发出去过
        when(voteMapper.sumScoreUpTo(eq(CAMPAIGN_ID), any())).thenReturn(BigDecimal.ZERO);
    }

    // ==================== 涨 / 跌 / 平 ====================

    /**
     * 一个正常交易日：BTC 涨、黄金跌，猜对的按"池 ÷ 当日总正确票数"拿分，猜错的 0 分。
     * <p>
     * 样本凑 18 张赢票：100 ÷ 18 = 5.5555…，不触封顶且必须向下取整（进位就超发）；
     * 两标的方向刻意相反——实现把涨跌套错标的时输赢立刻全反。
     */
    @Test
    void 涨跌各半时赢家按池均分输家记零分() {
        upDay(BTC);
        downDay(GOLD);

        CampaignVote btcWin = vote(1L, BTC, CampaignVote.UP);
        CampaignVote btcLose = vote(2L, BTC, CampaignVote.DOWN);
        CampaignVote goldWin = vote(3L, GOLD, CampaignVote.DOWN);
        CampaignVote goldLose = vote(4L, GOLD, CampaignVote.UP);

        List<CampaignVote> votes = new ArrayList<>(List.of(btcWin, btcLose, goldWin, goldLose));
        for (long u = 5; u <= 20; u++) votes.add(vote(u, BTC, CampaignVote.UP));   // 再凑 16 张赢票

        unsettled(DAY1, votes);
        service.settleDay(DAY1);

        List<Settled> rows = settledRows();
        assertThat(rows).as("20 张票必须一张不漏地写回结果").hasSize(20);
        assertThat(rows).extracting(Settled::id, Settled::result, Settled::score)
                .contains(tuple(btcWin.getId(), CampaignVote.WIN, "5.55"),
                        tuple(goldWin.getId(), CampaignVote.WIN, "5.55"),
                        tuple(btcLose.getId(), CampaignVote.LOSE, "0"),
                        tuple(goldLose.getId(), CampaignVote.LOSE, "0"));
        assertThat(rows).filteredOn(r -> CampaignVote.WIN.equals(r.result()))
                .as("18 张赢票每张 5.55").hasSize(18)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("5.55"));
    }

    /**
     * 平盘：那个标的当天的票全判 DEFERRED、0 分，且<b>不计入当日正确票数</b> ——
     * 它们要是混进分母，另一个标的的赢家就被平盘票稀释了。
     * <p>
     * 这里黄金只有一张赢票，独吞整池 100 却只拿到封顶的 6，正说明分母是 1 不是 4。
     */
    @Test
    void 平盘标的整体顺延且不稀释另一标的的赢家() {
        flatDay(BTC);
        upDay(GOLD);

        CampaignVote btcUp = vote(1L, BTC, CampaignVote.UP);
        CampaignVote btcDown = vote(2L, BTC, CampaignVote.DOWN);
        CampaignVote btcUp2 = vote(3L, BTC, CampaignVote.UP);
        CampaignVote goldUp = vote(4L, GOLD, CampaignVote.UP);

        unsettled(DAY1, List.of(btcUp, btcDown, btcUp2, goldUp));
        service.settleDay(DAY1);

        assertThat(settledRows()).extracting(Settled::id, Settled::result, Settled::score)
                .containsExactlyInAnyOrder(
                        tuple(btcUp.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(btcDown.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(btcUp2.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(goldUp.getId(), CampaignVote.WIN, "6.00"));
    }

    /**
     * 该标的这天压根没开市（黄金的周末）：它的票全判 DEFERRED、0 分，
     * 而<b>另一个标的在同一次结算里照常发分</b> —— 这是本条用例的要害。
     * <p>
     * 黄金喂的是"上一交易日 100→110（涨）"：实现不核对 openTime 的话
     * goldUp 会变成赢票、分母从 1 变 2，四行断言一起红。
     * <p>
     * DEFERRED 不是 null：没开市是市场真实状态，返 null 会让 BTC 的票被黄金周末拖着发不出分。
     */
    @Test
    void 该日无日线的标的判顺延另一标的照常结算() {
        upDay(BTC);
        closedOn(GOLD, Set.of(DAY1), "100", "110");

        CampaignVote btcWin = vote(1L, BTC, CampaignVote.UP);
        CampaignVote btcLose = vote(2L, BTC, CampaignVote.DOWN);
        CampaignVote goldUp = vote(3L, GOLD, CampaignVote.UP);
        CampaignVote goldDown = vote(4L, GOLD, CampaignVote.DOWN);

        unsettled(DAY1, List.of(btcWin, btcLose, goldUp, goldDown));
        service.settleDay(DAY1);

        assertThat(settledRows()).extracting(Settled::id, Settled::result, Settled::score)
                .containsExactlyInAnyOrder(
                        // BTC 只有一张赢票，独吞整池 100 → 拿到封顶的 6.00（分母是 1，黄金那两张没进来）
                        tuple(btcWin.getId(), CampaignVote.WIN, "6.00"),
                        tuple(btcLose.getId(), CampaignVote.LOSE, "0"),
                        tuple(goldUp.getId(), CampaignVote.DEFERRED, "0"),
                        tuple(goldDown.getId(), CampaignVote.DEFERRED, "0"));
    }

    // ==================== 拿不到价 ====================

    /*
     * 下面三条是 DEFERRED 的对照组：拿不到数据时必须"整天一行都不写"，
     * 不能顺手退化成"全判 DEFERRED"。两者一旦合并，一次网络抖动就把全天的票判成平盘落库，
     * 而 CAS 让这事再也纠不回来。
     */

    /**
     * 取行情抛异常：整天一行都不写——池子两标的共享，只结一半等于按错误分母发分且 CAS 后纠不回。
     */
    @Test
    void 取日线抛异常时整天一行都不写() {
        upDay(BTC);
        when(binance.getFuturesKlinesLight(eq(GOLD), any(), anyInt(), anyLong()))
                .thenThrow(new RuntimeException("451 restricted location"));

        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP), vote(2L, GOLD, CampaignVote.UP)));
        service.settleDay(DAY1);

        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    /** 只回一根日线（比不出"vs 前日收盘"）同样是拿不到价：一行都不写 */
    @Test
    void 日线不足两根时整天一行都不写() {
        upDay(BTC);
        when(binance.getFuturesKlinesLight(eq(GOLD), any(), anyInt(), anyLong()))
                .thenReturn(klines(DAY1, "100"));

        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP), vote(2L, GOLD, CampaignVote.UP)));
        service.settleDay(DAY1);

        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    /** 返回一串不是 K 线的垃圾（网关错误页之类）也得当"没拿到"，不能崩在解析里 */
    @Test
    void 返回垃圾数据时整天一行都不写() {
        upDay(BTC);
        when(binance.getFuturesKlinesLight(eq(GOLD), any(), anyInt(), anyLong()))
                .thenReturn("{\"code\":0,\"msg\":\"Service unavailable from a restricted location\"}");

        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP), vote(2L, GOLD, CampaignVote.UP)));
        service.settleDay(DAY1);

        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    // ==================== 逐票分摊 ====================

    /**
     * 一个人两张赢票：分摊到票上后逐票之和必须<b>恰好</b>等于他应得的那个数。
     * <p>
     * 样本凑 5.01 这种除不尽的数：前票向下取整 2.50、末票兜 2.51，两票都取 2.50 就凭空少 1 分。
     * 凑法：首日已发 79.96 → 池 20.04；总正确票 8 → 每票 2.5050；双份 5.0100 未触封顶。
     */
    @Test
    void 一个人两张赢票分摊后逐票之和等于他应得() {
        upDay(BTC);
        downDay(GOLD);
        when(voteMapper.sumScoreUpTo(eq(CAMPAIGN_ID), any())).thenReturn(new BigDecimal("79.96"));

        CampaignVote mineBtc = vote(1L, BTC, CampaignVote.UP);
        CampaignVote mineGold = vote(1L, GOLD, CampaignVote.DOWN);

        List<CampaignVote> votes = new ArrayList<>(List.of(mineBtc, mineGold));
        for (long u = 2; u <= 7; u++) votes.add(vote(u, BTC, CampaignVote.UP));   // 总正确票数 8

        unsettled(DAY1, votes);
        service.settleDay(DAY1);

        List<Settled> rows = settledRows();
        assertThat(rows).extracting(Settled::id, Settled::score)
                .as("前票 2.50、末票兜 2.51")
                .contains(tuple(mineBtc.getId(), "2.50"), tuple(mineGold.getId(), "2.51"));

        BigDecimal mine = rows.stream()
                .filter(r -> r.id().equals(mineBtc.getId()) || r.id().equals(mineGold.getId()))
                .map(r -> new BigDecimal(r.score()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(mine).as("双份 = 每票 2.5050 × 2").isEqualByComparingTo("5.01");

        assertThat(rows).filteredOn(r -> !r.id().equals(mineBtc.getId()) && !r.id().equals(mineGold.getId()))
                .as("单份的每人 2.50").hasSize(6)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
    }

    // ==================== 可分池的反推 ====================

    /**
     * 池子靠 {@code 100 × 已过天数 − 全场已发出的分} 反推，不存"顺延余额"。
     * <p>
     * 同样 40 张赢票：活动首日池 100 → 每票 2.50；次日若首日一分没发出去（截至次日的已发额仍是 0），
     * 池就是 200 → 每票 5.00。这个"翻倍"就是顺延，它不是某一列存下来的，是减出来的。
     */
    @Test
    void 前一日全额顺延后次日池子变大() {
        upDay(BTC);
        downDay(GOLD);

        List<CampaignVote> day1 = new ArrayList<>();
        List<CampaignVote> day2 = new ArrayList<>();
        for (long u = 1; u <= 40; u++) day1.add(vote(u, BTC, CampaignVote.UP));
        for (long u = 1; u <= 40; u++) day2.add(vote(u, BTC, CampaignVote.UP));
        unsettled(DAY1, day1);
        unsettled(DAY2, day2);

        service.settleDay(DAY1);
        assertThat(settledRows()).as("首日：池 100 ÷ 40 票").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));

        service.settleDay(DAY2);
        List<Settled> both = settledRows();
        assertThat(both).hasSize(80);
        assertThat(both.subList(40, 80)).as("次日：池 200 ÷ 40 票，首日那 100 顺延了过来")
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("5.00"));
    }

    /**
     * 休市那天没发出去的池子，一分不少地进次日；次日发光了，第三日就没得顺延。
     * <p>
     * 这里用 {@link #liveLedger()} 把 settle / sumScoreUpTo 换成活的账本 ——
     * 顺延不是某一列存下来的，是减出来的，走三天才看得出这一点。
     * <pre>
     *   首日   黄金休市，当天只有黄金票 → 一分不发，上限 100 原封不动
     *   第 2 日 上限 200 − 已发 0   = 池 200 ÷ 40 票 = 每票 5.00   ← 首日那 100 顺延过来了
     *   第 3 日 上限 300 − 已发 200 = 池 100 ÷ 40 票 = 每票 2.50   ← 没得顺延就掉回来
     * </pre>
     */
    @Test
    void 休市那天没发出去的池子顺延到次日() {
        Map<Long, LocalDate> ledger = liveLedger();

        upDay(BTC);
        closedOn(GOLD, Set.of(DAY1), "100", "110");   // 黄金只在活动首日休市

        List<CampaignVote> day1 = List.of(vote(1L, GOLD, CampaignVote.UP),
                vote(2L, GOLD, CampaignVote.DOWN));
        day1.forEach(v -> ledger.put(v.getId(), DAY1));
        List<CampaignVote> day2 = votesOn(DAY2, ledger, 40);
        List<CampaignVote> day3 = votesOn(DAY3, ledger, 40);
        unsettled(DAY1, day1);
        unsettled(DAY2, day2);
        unsettled(DAY3, day3);

        service.settleDay(DAY1);
        assertThat(settledRows()).as("黄金休市：两张票都判顺延").hasSize(2)
                .allSatisfy(r -> {
                    assertThat(r.result()).isEqualTo(CampaignVote.DEFERRED);
                    assertThat(r.score()).isEqualTo("0");
                });

        service.settleDay(DAY2);
        assertThat(settledRows().subList(2, 42))
                .as("次日池 200：首日那 100 顺延了过来").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("5.00"));

        service.settleDay(DAY3);
        assertThat(settledRows().subList(42, 82))
                .as("第三日池 100：上一日发光了，没得顺延").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
    }

    /**
     * <b>补结算</b>：先把后面的日子结了，再回头补前面漏掉的那天 ——
     * 那天的赢家必须拿到它<b>应得</b>的池，不能因为"后面几天已经把分发出去了"就集体归零。
     * <p>
     * 钉的是 sumScoreUpTo 卡 {@code vote_date <=} 那道口径：减"全场已发"的话补结的那天会被夹到 0。
     * 样本刻意拉开（应得 2.50 vs 归零 0.00），回归一眼看得出。
     */
    @Test
    void 补结算漏掉的那天仍拿到它应得的池() {
        Map<Long, LocalDate> ledger = liveLedger();
        upDay(BTC);
        downDay(GOLD);

        List<CampaignVote> day1 = votesOn(DAY1, ledger, 40);   // id 1-40，漏结的那天
        List<CampaignVote> day2 = votesOn(DAY2, ledger, 40);
        List<CampaignVote> day3 = votesOn(DAY3, ledger, 40);
        unsettled(DAY1, day1);
        unsettled(DAY2, day2);
        unsettled(DAY3, day3);

        // 首日漏跑，后面两天照常结
        service.settleDay(DAY2);
        service.settleDay(DAY3);
        // 隔几天才回头补首日
        service.settleDay(DAY1);

        List<Settled> rows = settledRows();
        assertThat(rows).filteredOn(r -> r.id() <= 40)
                .as("补结的首日：池 100 ÷ 40 票 = 2.50；减全场已发的话这里全是 0.00")
                .hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
        assertThat(rows).filteredOn(r -> r.id() > 40 && r.id() <= 80)
                .as("第 2 日：上限 200 − 0 = 池 200 ÷ 40 票").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("5.00"));
        assertThat(rows).filteredOn(r -> r.id() > 80)
                .as("第 3 日：上限 300 − 200 = 池 100 ÷ 40 票").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
    }

    /**
     * <b>顺序结算的happy path 一分没变</b>：卡日期这个改动只影响乱序补结，
     * 正常按天推进时逐位相同（后面的日子还没结，本来就没分可加）。这条把三天的每票分值钉死，
     * 免得日后有人以为"加了个 WHERE 总归改了点什么"。
     * <pre>
     *   首日   上限 100 − 0   = 池 100 ÷ 40 票 = 每票 2.50，发掉 100
     *   第 2 日 上限 200 − 100 = 池 100 ÷ 25 票 = 每票 4.00，发掉 100
     *   第 3 日 上限 300 − 200 = 池 100 ÷ 20 票 = 每票 5.00
     * </pre>
     * 票数逐日变少、每票分值逐日变高，三个数各不相同 —— 池子算错一天就对不上。
     */
    @Test
    void 顺序结算三天的每票分值逐日推进() {
        Map<Long, LocalDate> ledger = liveLedger();
        upDay(BTC);
        downDay(GOLD);

        unsettled(DAY1, votesOn(DAY1, ledger, 40));
        unsettled(DAY2, votesOn(DAY2, ledger, 25));
        unsettled(DAY3, votesOn(DAY3, ledger, 20));

        service.settleDay(DAY1);
        service.settleDay(DAY2);
        service.settleDay(DAY3);

        List<Settled> rows = settledRows();
        assertThat(rows).hasSize(85);
        assertThat(rows.subList(0, 40)).allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
        assertThat(rows.subList(40, 65)).allSatisfy(r -> assertThat(r.score()).isEqualTo("4.00"));
        assertThat(rows.subList(65, 85)).allSatisfy(r -> assertThat(r.score()).isEqualTo("5.00"));
    }

    /**
     * 第一个能落到 vote_date 上的 UTC 日就是活动首日本身（票投在活动开赛后的头几小时：
     * 本地 00:00 开赛 = UTC 前一天 16:00，那批票盖的是次日 = 首日的戳）。
     * 它按"投出那天"算得 days=0，靠 {@code Math.max(days,1)} 抬成 1，
     * 上限仍是 100，那不到 8 小时里投的票照常有分可拿。去掉那个 max，这 40 张票全发 0.00。
     */
    @Test
    void 首个UTC投票日的池子上限被抬到一天份() {
        upDay(BTC);
        downDay(GOLD);

        List<CampaignVote> votes = new ArrayList<>();
        for (long u = 1; u <= 40; u++) votes.add(vote(u, BTC, CampaignVote.UP));
        unsettled(FIRST_UTC_VOTE_DAY, votes);

        service.settleDay(FIRST_UTC_VOTE_DAY);

        assertThat(settledRows()).as("池 100 ÷ 40 票").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
    }

    /**
     * ★ 头两个投票日<b>共用</b>一份 100，不是各拿一份。★
     * <p>
     * 首日被 max 抬到上限 100、发光；次日按"投出那天"算 days=1，上限还是 100，
     * 减掉截至次日已发的 100 → 池 0 → 每票 0.00。这 15 个投票日比活动天数多出来的那一个，
     * 就是这么被吸收掉的：边界只把预算在相邻两天之间挪，不凭空造预算。
     * <p>
     * 单写这条钉 minusDays(1)：天数跟着投票日本身算的话两天各得一份 100，
     * 次日 40 张票从 0.00 变 2.50，全场总额从 1400 涨到 1500。
     */
    @Test
    void 头两个投票日共用一份一百() {
        Map<Long, LocalDate> ledger = liveLedger();
        upDay(BTC);
        downDay(GOLD);

        LocalDate second = FIRST_UTC_VOTE_DAY.plusDays(1);
        unsettled(FIRST_UTC_VOTE_DAY, votesOn(FIRST_UTC_VOTE_DAY, ledger, 40));
        unsettled(second, votesOn(second, ledger, 40));

        service.settleDay(FIRST_UTC_VOTE_DAY);
        service.settleDay(second);

        List<Settled> rows = settledRows();
        assertThat(rows).hasSize(80);
        assertThat(rows.subList(0, 40)).as("首日：上限 100 − 0 = 池 100 ÷ 40 票")
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("2.50"));
        assertThat(rows.subList(40, 80)).as("次日：上限还是 100，已发也是 100 → 池 0")
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("0.00"));
    }

    /**
     * 最后一个能落到 vote_date 上的 UTC 日是 08-17（活动 SGT 08-17 00:00 收摊 = UTC 08-16 16:00，
     * 那天最后一批票盖的是次日 08-17 的戳）。它按"投出那天"（08-16）算得 days=14 →
     * 累计上限正好 1400 = 14 天 × 100。喂 1394 让剩 6.00 分/40 票=0.15；
     * 上限错算成 1500 时每票 2.65，差得足够远一眼分得开。
     */
    @Test
    void 末个UTC投票日的累计上限正好是十四天份() {
        upDay(BTC);
        downDay(GOLD);
        when(voteMapper.sumScoreUpTo(eq(CAMPAIGN_ID), any())).thenReturn(new BigDecimal("1394.00"));

        List<CampaignVote> votes = new ArrayList<>();
        for (long u = 1; u <= 40; u++) votes.add(vote(u, BTC, CampaignVote.UP));
        unsettled(LAST_UTC_VOTE_DAY, votes);

        service.settleDay(LAST_UTC_VOTE_DAY);

        assertThat(settledRows()).as("池 = 1400 − 1394 = 6.00，÷ 40 票").hasSize(40)
                .allSatisfy(r -> assertThat(r.score()).isEqualTo("0.15"));
    }

    // ==================== 取价的边界 ====================

    /**
     * endTime 得是该 UTC 日的最后一毫秒：传成次日 0 点整会把次日刚开的日线带回来，整日输赢集体错位。
     */
    @Test
    void 按UTC日末毫秒取日线() {
        upDay(BTC);
        upDay(GOLD);
        unsettled(DAY1, List.of(vote(1L, BTC, CampaignVote.UP)));

        service.settleDay(DAY1);

        ArgumentCaptor<Long> endTime = ArgumentCaptor.forClass(Long.class);
        verify(binance, atLeast(1))
                .getFuturesKlinesLight(eq(BTC), eq("1d"), anyInt(), endTime.capture());
        assertThat(endTime.getValue())
                .isEqualTo(DAY2.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1);
    }

    // ==================== 空转 ====================

    /** 没有活动：票都不查，行情更不该取（白白挨一次 451） */
    @Test
    void 没有活动时不查票也不取行情() {
        when(campaignMapper.selectActive()).thenReturn(null);

        service.settleDay(DAY1);

        verify(voteMapper, never()).listUnsettled(any(), any());
        verify(binance, never()).getFuturesKlinesLight(any(), any(), anyInt(), anyLong());
    }

    /**
     * 还没过完的 UTC 日一律不结：openTime 核对拦不住"今天"（当日 0 点就开出来了，只是半根还在长的蜡烛），
     * 这道闸必须单独有。
     */
    @Test
    void 今天与未来的日子一律不结() {
        upDay(BTC);
        downDay(GOLD);
        LocalDate today = CampaignVoteService.utcToday();
        unsettled(today, List.of(vote(1L, BTC, CampaignVote.UP)));
        unsettled(today.plusDays(1), List.of(vote(2L, BTC, CampaignVote.UP)));

        service.settleDay(today);
        service.settleDay(today.plusDays(1));

        verify(voteMapper, never()).settle(anyLong(), any(), any());
        verify(voteMapper, never()).listUnsettled(any(), any());
        verify(binance, never()).getFuturesKlinesLight(any(), any(), anyInt(), anyLong());
    }

    /** 当日没有待结算票（含重跑已结算完的一天）：不取行情、不写库 */
    @Test
    void 当日无待结算票时不取行情也不写库() {
        unsettled(DAY1, List.of());

        service.settleDay(DAY1);

        verify(binance, never()).getFuturesKlinesLight(any(), any(), anyInt(), anyLong());
        verify(voteMapper, never()).settle(anyLong(), any(), any());
    }

    // ==================== 总分汇总 ====================

    /**
     * sumScoreByUser 返回的是 JDBC 原始列标签（PG 全小写），这里按 user_id / total 取值并转成 Map。
     * SQL 没筛 result，只投过票的人也出一行 total=0——keySet 不是"拿过分的人"。
     */
    @Test
    void 总分按列名汇总成用户到分数的映射() {
        when(voteMapper.sumScoreByUser(CAMPAIGN_ID)).thenReturn(List.of(
                Map.of("user_id", 11L, "total", new BigDecimal("12.34")),
                Map.of("user_id", 22L, "total", new BigDecimal("0.00"))));

        assertThat(service.voteScoreByUser(CAMPAIGN_ID))
                .containsOnlyKeys(11L, 22L)
                .containsEntry(11L, new BigDecimal("12.34"));
    }

    // ==================== 手搓行 ====================

    /** 捕获到的一次 settle 调用。score 用字符串比，顺带把小数位一起钉住（列是 NUMERIC(8,2)） */
    private record Settled(Long id, String result, String score) {
    }

    private List<Settled> settledRows() {
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BigDecimal> score = ArgumentCaptor.forClass(BigDecimal.class);
        verify(voteMapper, atLeast(0)).settle(id.capture(), result.capture(), score.capture());

        List<Settled> out = new ArrayList<>();
        for (int i = 0; i < id.getAllValues().size(); i++) {
            out.add(new Settled(id.getAllValues().get(i), result.getAllValues().get(i),
                    score.getAllValues().get(i).toPlainString()));
        }
        return out;
    }

    private void unsettled(LocalDate day, List<CampaignVote> votes) {
        when(voteMapper.listUnsettled(CAMPAIGN_ID, day)).thenReturn(votes);
    }

    /**
     * 把 settle / sumScoreUpTo 换成一对活账本：settle 按票所属的投票日记账，
     * sumScoreUpTo 只加"不晚于问的那天"的桶 —— 与真库 {@code SUM(...) WHERE vote_date <= ?} 同口径。
     * <p>
     * 默认那个死值 stub 看不出乱序补结的区别（它不管问的是哪天都回同一个数），
     * 而"补结的那天该拿多少"恰恰只在日期分桶下才谈得上。
     *
     * @return voteId → 投票日 的登记表，手搓票时往里登记（{@link #votesOn} 会自动登记）
     */
    private Map<Long, LocalDate> liveLedger() {
        Map<Long, LocalDate> dayOfVote = new HashMap<>();
        Map<LocalDate, BigDecimal> spentByDay = new HashMap<>();

        when(voteMapper.settle(anyLong(), any(), any())).thenAnswer(inv -> {
            spentByDay.merge(dayOfVote.get((Long) inv.getArgument(0)), inv.getArgument(2), BigDecimal::add);
            return 1;
        });
        when(voteMapper.sumScoreUpTo(eq(CAMPAIGN_ID), any())).thenAnswer(inv -> {
            LocalDate upTo = inv.getArgument(1);
            return spentByDay.entrySet().stream()
                    .filter(e -> !e.getKey().isAfter(upTo))
                    .map(Map.Entry::getValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        });
        return dayOfVote;
    }

    /** 某日 n 张看涨 BTC 的票，顺手登记进账本 */
    private List<CampaignVote> votesOn(LocalDate day, Map<Long, LocalDate> ledger, int n) {
        List<CampaignVote> out = new ArrayList<>(n);
        for (long u = 1; u <= n; u++) {
            CampaignVote v = vote(u, BTC, CampaignVote.UP);
            ledger.put(v.getId(), day);
            out.add(v);
        }
        return out;
    }

    private CampaignVote vote(long userId, String symbol, String direction) {
        CampaignVote v = new CampaignVote();
        v.setId(nextVoteId++);
        v.setCampaignId(CAMPAIGN_ID);
        v.setUserId(userId);
        v.setSymbol(symbol);
        v.setDirection(direction);
        return v;
    }

    private void upDay(String symbol) {
        tradesEveryDay(symbol, "100", "110");
    }

    private void downDay(String symbol) {
        tradesEveryDay(symbol, "100", "90");
    }

    private void flatDay(String symbol) {
        tradesEveryDay(symbol, "100", "100");
    }

    /**
     * 仿 24×7 品种（BTC）：按 endTime 现算 openTime，同一个 stub 服务同一条用例里结算的好几天。
     */
    private void tradesEveryDay(String symbol, String prevClose, String close) {
        when(binance.getFuturesKlinesLight(eq(symbol), eq("1d"), anyInt(), anyLong()))
                .thenAnswer(inv -> klines(utcDayOf(inv.getArgument(3)), "999", prevClose, close));
    }

    /**
     * 仿非 24 小时品种休市（黄金的周末）：closedDays 那几天没有日线，
     * 返回的最后一根停在前一天 —— 也就是"上一个交易日"。
     */
    private void closedOn(String symbol, Set<LocalDate> closedDays, String prevClose, String close) {
        when(binance.getFuturesKlinesLight(eq(symbol), eq("1d"), anyInt(), anyLong()))
                .thenAnswer(inv -> {
                    LocalDate asked = utcDayOf(inv.getArgument(3));
                    LocalDate last = closedDays.contains(asked) ? asked.minusDays(1) : asked;
                    return klines(last, "999", prevClose, close);
                });
    }

    private static LocalDate utcDayOf(long epochMs) {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static long utcStartMs(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /**
     * 仿 {@code getFuturesKlinesLight} 的返回：每行 8 个元素、openTime 在下标 0、收盘价在下标 4。
     * 最后一根落在 lastDay，往前每根退一天；首行是根用不着的旧日线 ——
     * 实现必须取<b>最后两根</b>，取头两根的话涨跌就反了。
     * <p>
     * open/high/low 填成与 close 无关的常量：实现读错收盘价下标的话，各行就一模一样，涨跌塌成平盘。
     */
    private static String klines(LocalDate lastDay, String... closes) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < closes.length; i++) {
            LocalDate day = lastDay.minusDays(closes.length - 1L - i);
            ArrayNode r = rows.addArray();
            r.add(utcStartMs(day));                    // 0 openTime ← 用来核对"是不是我要的那天"
            r.add("1");                                // 1 open
            r.add("99999");                            // 2 high
            r.add("0.01");                             // 3 low
            r.add(closes[i]);                          // 4 close ← 判涨跌的那位
            r.add("0");                                // 5 volume
            r.add(utcStartMs(day.plusDays(1)) - 1);    // 6 closeTime
            r.add("0");                                // 7 quoteVolume
        }
        return MAPPER.writeValueAsString(rows);
    }
}
