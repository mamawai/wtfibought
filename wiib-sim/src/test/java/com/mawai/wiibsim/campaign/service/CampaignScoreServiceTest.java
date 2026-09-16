package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignMapper;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.CampaignScore;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.campaign.model.MyCampaignView;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import com.mawai.wiibsim.campaign.score.TradeScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 积分总表的汇总半边：四路分怎么合、谁上榜、怎么排、预估怎么算、缓存怎么走。
 * 不起 Spring、不连库、不连 Redis。
 * <p>
 * CampaignService 用真的（同 {@link CampaignCheckinServiceTest}），钉"活动结束了榜还出得来"。
 * 三路分数全 mock 掉是有意的：各自算法由各自的测试类管，本类只管"合"这一步
 * （正负分拆法、下限 0、谁进榜、分母算谁、缓存 JSON 往返）。
 * 样本里两类"不该上榜的人"承重：IDLE 在名单里但零分（防 0 分噪音），
 * 900/901 有投票分不在名单（防拿投票分那张图当参与名单）
 * （sumScoreByUser 不筛 result，只投过票没结算的人也会出一行）—— 那会把机器人和邀请码用户放进榜里。
 */
class CampaignScoreServiceTest {

    private static final long CAMPAIGN_ID = 7L;
    private static final String BOARD_KEY = "campaign:board:7";

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 3, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 17, 0, 0);
    private static final BigDecimal POOL = new BigDecimal("1000");

    private static final long ACE = 1L;    // 交易 + 签到 + 投票，四路全有
    private static final long MID = 2L;    // 只签到
    private static final long REKT = 3L;   // 正分 10、罚分 −30，靠下限兜到 0
    private static final long IDLE = 4L;   // 在名单里但一分没有
    private static final long TIE = 5L;    // 与 MID 同分，用来看同分怎么排
    private static final long GHOST = 900L; // 只在投票分图里出现，不在名单里

    private CampaignMapper campaignMapper;
    private CampaignStatsMapper statsMapper;
    private TradeScorer tradeScorer;
    private CampaignCheckinService checkinService;
    private CampaignVoteService voteService;
    private CacheService cacheService;
    private CampaignScoreService service;

    @BeforeEach
    void setUp() {
        campaignMapper = mock(CampaignMapper.class);
        statsMapper = mock(CampaignStatsMapper.class);
        tradeScorer = mock(TradeScorer.class);
        checkinService = mock(CampaignCheckinService.class);
        voteService = mock(CampaignVoteService.class);
        cacheService = mock(CacheService.class);
        service = new CampaignScoreService(new CampaignService(campaignMapper, new MessageCatalog()), statsMapper,
                tradeScorer, checkinService, voteService, cacheService);

        running(START, END);

        // 名单顺序刻意打乱：排序真在干活的话，输出必须是 1,2,5,3 而不是这里的顺序
        when(statsMapper.listEligibleUsers()).thenReturn(List.of(
                user(TIE, "tie"), user(REKT, "rekt"), user(ACE, "ace"),
                user(IDLE, "idle"), user(MID, "mid")));

        when(tradeScorer.scoreAll(CAMPAIGN_ID, START, END)).thenReturn(Map.of(
                ACE, List.of(ScoreItem.of("ROI40", "单仓位 ROI ≥ 40%", 2, 10),
                        ScoreItem.of("LIQ_TRIGGER", "触发强平", 1, -5)),
                REKT, List.of(ScoreItem.of("ROI40", "单仓位 ROI ≥ 40%", 2, 10),
                        ScoreItem.of("RESET_EXTRA", "付费重置账户", 1, -30))));

        when(checkinService.scoreAll(any(Campaign.class))).thenReturn(Map.of(
                ACE, List.of(ScoreItem.of("CHECKIN", "每日签到", 6, 6)),
                MID, List.of(ScoreItem.of("CHECKIN", "每日签到", 6, 6)),
                TIE, List.of(ScoreItem.of("CHECKIN", "每日签到", 6, 6))));

        // 900 是"投过票还没结算"的那种行：total=0 但确实出现在结果里，绝不能因此上榜
        when(voteService.voteScoreByUser(CAMPAIGN_ID)).thenReturn(Map.of(
                ACE, new BigDecimal("3.50"),
                GHOST, BigDecimal.ZERO));

        when(voteService.board(anyLong())).thenReturn(List.of(
                new VoteBoard("BTCUSDT", 3, 1, "UP")));
    }

    // ==================== 四路分怎么合 ====================

    /**
     * 一个人的最终分 = 交易正分 + 日常分 + 投票分 + 罚分（罚分是负的），且四项各自单列。
     * <p>
     * ACE：交易 +10、罚 −5、签到 +6、投票 +3.50 → 14.50。
     * 四个字段分开断言是必须的 —— 只看 finalScore 的话，把罚分错算进 tradeScore（10−5=5）
     * 再单列一次罚分，总分照样是 14.50，前端却会显示"交易得分 5"。
     */
    @Test
    void 最终分是交易日常投票罚分四路之和且各项单列() {
        CampaignScore ace = pick(service.scoreBoard(), ACE);

        assertThat(ace.tradeScore()).as("交易正分不含罚分").isEqualTo(10);
        assertThat(ace.penalty()).as("罚分单列且为负").isEqualTo(-5);
        assertThat(ace.dailyScore()).isEqualTo(6);
        assertThat(ace.voteScore()).isEqualByComparingTo(new BigDecimal("3.50"));
        assertThat(ace.finalScore()).isEqualByComparingTo(new BigDecimal("14.50"));
        assertThat(ace.username()).isEqualTo("ace");
        assertThat(ace.claimable()).as("名单已按纯数字 linux_do_id 筛过，恒为 true").isTrue();
    }

    /** 明细把三路的条目都拼进来，投票分作为单独一条 VOTE 追加在最后 */
    @Test
    void 明细汇总三路条目且投票单列一条() {
        CampaignScore ace = pick(service.scoreBoard(), ACE);

        assertThat(ace.items()).extracting(ScoreItem::code)
                .containsExactly("ROI40", "LIQ_TRIGGER", "CHECKIN", "VOTE");
        assertThat(item(ace.items(), "VOTE").score()).isEqualByComparingTo(new BigDecimal("3.50"));
    }

    /** 没有投票分的人不产出 VOTE 那条，明细里不挂一条 0 分的空项 */
    @Test
    void 没投票分的人不出VOTE那条() {
        assertThat(pick(service.scoreBoard(), MID).items())
                .extracting(ScoreItem::code).containsExactly("CHECKIN");
    }

    /**
     * ★ 重罚只把总分兜到 0，不会变成负数 ★
     * <p>
     * REKT 正分 10、全仓爆仓 −30，真值 −20。个人总分下限为 0 是分配公式的前提：
     * 负分参与求和会把分母做小，等于让爆仓的人反过来<b>抬高</b>别人的到手额度，
     * 别人的收益凭什么随他爆不爆仓变。同时他仍要留在榜上并带着明细 ——
     * 得让他看得见自己被扣在哪，而不是人间蒸发。
     */
    @Test
    void 重罚把总分兜到零而不是负数() {
        CampaignScore rekt = pick(service.scoreBoard(), REKT);

        assertThat(rekt.finalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(rekt.finalScore().signum()).as("兜的是 0，不是 −20").isNotNegative();
        assertThat(rekt.tradeScore()).isEqualTo(10);
        assertThat(rekt.penalty()).isEqualTo(-30);
        assertThat(rekt.items()).extracting(ScoreItem::code)
                .as("扣到 0 的人也得留着明细，否则他不知道分去哪了")
                .containsExactly("ROI40", "RESET_EXTRA");
    }

    /**
     * 日常侧的负分也要计进 penalty：把"明细之和 == 总分"钉成结构性的，
     * 不靠"碰巧日常侧全是正分"。
     */
    @Test
    void 日常侧的负分也计进罚分() {
        when(checkinService.scoreAll(any(Campaign.class))).thenReturn(Map.of(
                MID, List.of(ScoreItem.of("CHECKIN", "每日签到", 6, 6),
                        ScoreItem.of("DAILY_PENALTY", "假想的日常扣分", 1, -3))));

        CampaignScore mid = pick(service.scoreBoard(), MID);

        assertThat(mid.dailyScore()).as("日常正分不含那 −3").isEqualTo(6);
        assertThat(mid.penalty()).as("日常侧的负分要收进 penalty，否则总分不认它").isEqualTo(-3);
        assertThat(mid.finalScore())
                .as("只收交易侧负分的话这里会是 6.00，而明细上明明挂着 −3")
                .isEqualByComparingTo(new BigDecimal("3.00"));
    }

    // ==================== 谁上榜、怎么排 ====================

    /** 榜单按最终分降序，同分按 userId 升序（MID=2 排在 TIE=5 前面，尽管名单里 5 在前） */
    @Test
    void 榜单按最终分降序同分按userId升序() {
        assertThat(service.scoreBoard())
                .extracting(CampaignScore::userId, CampaignScore::finalScore)
                .containsExactly(
                        tuple(ACE, new BigDecimal("14.50")),
                        tuple(MID, new BigDecimal("6.00")),
                        tuple(TIE, new BigDecimal("6.00")),
                        tuple(REKT, new BigDecimal("0.00")));
    }

    /** 在名单里但一分没有、一条明细也没有的人不上榜 */
    @Test
    void 名单里毫无活动的人不上榜() {
        assertThat(service.scoreBoard()).extracting(CampaignScore::userId).doesNotContain(IDLE);
    }

    /**
     * ★ 只在投票分图里出现的人不上榜 ★
     * <p>
     * voteScoreByUser 建在 sumScoreByUser 上，那条 SQL 不筛 result —— 只投过票还没结算的人
     * 也会出一行、total=0。总分是对的，但"出现在结果里"不等于"是参与者"。
     * 榜单的参与名单只有一个来源：listEligibleUsers。拿投票图去遍历的话，
     * 量化机器人和邀请码用户会直接进榜。
     */
    @Test
    void 只在投票分图里出现的人不上榜() {
        assertThat(service.scoreBoard()).extracting(CampaignScore::userId)
                .containsExactly(ACE, MID, TIE, REKT)
                .doesNotContain(GHOST);
    }

    /**
     * 读路径不判活动时间窗：活动整体挪到过去，榜单照样出得来。
     * <p>
     * 结束后前端还要展示最终榜与领取入口。这条会在有人把 current() 顺手改成 requireRunning() 时变红。
     */
    @Test
    void 活动已结束依然出得来榜单() {
        running(LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(16));

        assertThat(service.scoreBoard()).isNotEmpty();
    }

    // ==================== 分配权重 ====================

    /** 分母只算"能领取且分数为正"的人：REKT 兜到 0 被剔除，IDLE 压根不在榜上 */
    @Test
    void 权重只留分数为正的人() {
        Map<Long, BigDecimal> weights = service.eligibleWeights(service.scoreBoard());

        assertThat(weights).containsOnlyKeys(ACE, MID, TIE);
        assertThat(weights.get(ACE)).isEqualByComparingTo(new BigDecimal("14.50"));
        assertThat(weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(new BigDecimal("26.50"));
    }

    /** 权重的和就是 myView 拿去做分母的那个数，两处不能各算各的 */
    @Test
    void 权重之和就是myView用的分母() {
        BigDecimal sum = service.eligibleWeights(service.scoreBoard()).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(service.myView(ACE).eligibleTotal()).isEqualByComparingTo(sum);
    }

    /**
     * claimable 是分配侧的防御闸：现行名单规则下它恒为 true，但真喂进来一个 false 的，
     * 权重里必须没有他 —— 发不出去的钱不能占着分母。
     * <p>
     * 这条直接喂手搓的榜单，因为 listEligibleUsers 的正则物理上产不出这种行。
     */
    @Test
    void 不可领取的人不进权重() {
        List<CampaignScore> board = List.of(
                score(ACE, true, new BigDecimal("10.00")),
                score(MID, false, new BigDecimal("90.00")));

        assertThat(service.eligibleWeights(board)).containsOnlyKeys(ACE);
    }

    // ==================== 我的视图 ====================

    /**
     * 预估到手 = 奖池 × 我的分 ÷ 有效总分，名次从 1 起。
     * <p>
     * 1000 × 14.50 ÷ 26.50 = 547.1698…，按 DOWN 截到分 → 547.16。
     * 用 DOWN 不用 HALF_UP：逐个人向上取整，加起来会超过奖池，最后一个人没钱发。
     */
    @Test
    void 预估等于奖池乘我的分除以有效总分且名次从一起() {
        MyCampaignView view = service.myView(ACE);

        assertThat(view.rank()).isEqualTo(1);
        assertThat(view.estimatedLdc()).isEqualByComparingTo(new BigDecimal("547.16"));
        assertThat(view.eligibleTotal()).isEqualByComparingTo(new BigDecimal("26.50"));
        assertThat(view.participants()).as("榜上人数，不含没上榜的 IDLE").isEqualTo(4);
        assertThat(view.prizePool()).isEqualByComparingTo(POOL);
        assertThat(view.campaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(view.startAt()).isEqualTo(START.toString());
        assertThat(view.endAt()).isEqualTo(END.toString());
        assertThat(view.voteBoard()).extracting(VoteBoard::symbol).containsExactly("BTCUSDT");
    }

    /**
     * 「我的积分」就是榜上那一行，不是另算一遍。
     * <p>
     * 这是整个任务的立身之本：三个视图同源。哪天有人图省事给 myView 单独写一套查询，
     * 这条会红 —— 而线上的表现是"我的积分 37、榜上写 36"，用户会来问，而且没法解释。
     */
    @Test
    void 我的视图里的me就是榜上那一行() {
        List<CampaignScore> board = service.scoreBoard();

        assertThat(service.myView(ACE).me()).isEqualTo(pick(board, ACE));
        assertThat(service.myView(REKT).me()).isEqualTo(pick(board, REKT));
    }

    /** 分数兜到 0 的人在榜上有名次，但预估是 0 —— 他不在分母里 */
    @Test
    void 兜到零分的人有名次但预估为零() {
        MyCampaignView view = service.myView(REKT);

        assertThat(view.rank()).isEqualTo(4);
        assertThat(view.estimatedLdc()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * 没上榜的人拿全零兜底而不是抛异常：活动刚开、一分没挣的人打开页面是最常见的情形。
     * rank = 0 表示"还没上榜"。
     */
    @Test
    void 没上榜的人拿全零兜底不抛异常() {
        MyCampaignView view = service.myView(IDLE);

        assertThat(view).isNotNull();
        assertThat(view.rank()).as("0 = 还没上榜").isZero();
        assertThat(view.estimatedLdc()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.me().userId()).isEqualTo(IDLE);
        assertThat(view.me().finalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.me().items()).isEmpty();
        assertThat(view.eligibleTotal()).as("分母是全场的，跟我上没上榜无关")
                .isEqualByComparingTo(new BigDecimal("26.50"));
    }

    /** 今日签到状态原样透传给前端 */
    @Test
    void 今日签到状态透传() {
        when(checkinService.checkedToday(CAMPAIGN_ID, ACE)).thenReturn(true);

        assertThat(service.myView(ACE).checkedToday()).isTrue();
        assertThat(service.myView(MID).checkedToday()).isFalse();
    }

    // ==================== 没有活动 ====================

    /** 没有进行中的活动：榜是空的、myView 是 null，且一次都不去扫业务表 */
    @Test
    void 没有活动时榜为空myView为null且不扫库() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThat(service.scoreBoard()).isEmpty();
        assertThat(service.myView(ACE)).isNull();

        verify(statsMapper, never()).listEligibleUsers();
        verify(tradeScorer, never()).scoreAll(any(), any(), any());
        verify(cacheService, never()).get(any());
    }

    // ==================== 缓存 ====================

    /** 缓存键是 campaign:board:{id}，TTL 60 秒 */
    @Test
    void 缓存键带活动id且TTL六十秒() {
        service.scoreBoard();

        verify(cacheService).set(eq(BOARD_KEY), any(String.class), eq(Duration.ofSeconds(60)));
    }

    /**
     * ★ 缓存命中不重算，且 JSON 能原样转回来 ★
     * <p>
     * 断言"反序列化后等于原对象"：JSON 对 record 的支持是运行期的事，
     * 错只在缓存命中那次请求暴露；equals 逐字段比且 BigDecimal 带 scale，顺带钉住 14.50 不变 14.5。
     */
    @Test
    void 缓存命中不重算且JSON原样转得回来() {
        List<CampaignScore> first = service.scoreBoard();

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(cacheService).set(eq(BOARD_KEY), json.capture(), any(Duration.class));
        when(cacheService.get(BOARD_KEY)).thenReturn(json.getValue());

        List<CampaignScore> second = service.scoreBoard();

        assertThat(second)
                .as("record 走 JSON 转一圈必须逐字段（含 BigDecimal 标度）不变")
                .isEqualTo(first);
        verify(tradeScorer, times(1)).scoreAll(CAMPAIGN_ID, START, END);
        verify(checkinService, times(1)).scoreAll(any(Campaign.class));
        verify(voteService, times(1)).voteScoreByUser(CAMPAIGN_ID);
        verify(statsMapper, times(1)).listEligibleUsers();
        verify(cacheService, times(1)).set(any(), any(), any());
    }

    /** 单独钉一次裸的 JSON 往返：上一条若因缓存逻辑先挂，这条能指出到底是哪一半坏了 */
    @Test
    void 积分记录的JSON往返逐字段不变() {
        List<CampaignScore> board = service.scoreBoard();

        List<CampaignScore> back = MAPPER.readValue(MAPPER.writeValueAsString(board), new TypeReference<List<CampaignScore>>() {});

        assertThat(back).isEqualTo(board);
        assertThat(back.getFirst().items()).isEqualTo(board.getFirst().items());
        assertThat(back.getFirst().claimable()).as("boolean 组件不能在往返里丢掉").isTrue();
        assertThat(back.getFirst().finalScore()).isEqualTo(new BigDecimal("14.50"));
    }

    /** 换库前 fastjson2 写进缓存的榜（字段字母序、BigDecimal 写数字），换库后还得读得回来 */
    @Test
    void fastjson2老缓存串仍能读回() {
        when(cacheService.get(BOARD_KEY)).thenReturn("[{\"claimable\":true,\"dailyScore\":3,\"finalScore\":14.50,"
                + "\"items\":[{\"code\":\"VOTE\",\"count\":2,\"label\":\"投票\",\"score\":1.50}],"
                + "\"penalty\":-5,\"tradeScore\":15,\"userId\":1,\"username\":\"u1\",\"voteScore\":1.50}]");

        assertThat(service.scoreBoard()).containsExactly(new CampaignScore(1L, "u1", true, 15, 3,
                new BigDecimal("1.50"), -5, new BigDecimal("14.50"),
                List.of(new ScoreItem("VOTE", "投票", 2, new BigDecimal("1.50")))));
        verify(statsMapper, never()).listEligibleUsers();
    }

    /** 缓存命中时连业务表都不碰 —— 缓存的意义就在这，破了等于每次请求全表扫一遍 */
    @Test
    void 缓存命中时一次库都不查() {
        when(cacheService.get(BOARD_KEY)).thenReturn(MAPPER.writeValueAsString(
                List.of(score(ACE, true, new BigDecimal("14.50")))));

        assertThat(service.scoreBoard()).extracting(CampaignScore::userId).containsExactly(ACE);

        verify(statsMapper, never()).listEligibleUsers();
        verify(tradeScorer, never()).scoreAll(any(), any(), any());
        verify(cacheService, never()).set(any(), any(), any());
    }

    /**
     * 结算走的 freshBoard 无视缓存重算，展示走的 scoreBoard 照样吃缓存。
     * 缓存喂成"只有 ACE、999 分"的假榜（真算是四个人），两条路径返回一比就分得清谁吃了缓存。
     */
    @Test
    void 结算用的freshBoard无视缓存重算而展示路径照样吃缓存() {
        when(cacheService.get(BOARD_KEY)).thenReturn(MAPPER.writeValueAsString(
                List.of(score(ACE, true, new BigDecimal("999.00")))));

        assertThat(service.freshBoard())
                .as("freshBoard 必须是真算出来的四个人，不是缓存里那份")
                .extracting(CampaignScore::userId).containsExactly(ACE, MID, TIE, REKT);
        verify(cacheService, never()).get(BOARD_KEY);
        verify(tradeScorer, times(1)).scoreAll(CAMPAIGN_ID, START, END);
        // 算完把新结果写回同一个键：结算之后用户看到的榜与真正发出去的钱是同一份
        verify(cacheService).set(eq(BOARD_KEY), any(String.class), eq(Duration.ofSeconds(60)));

        assertThat(service.scoreBoard())
                .as("展示路径不受影响，照样直接吃缓存")
                .extracting(CampaignScore::userId).containsExactly(ACE);
        verify(tradeScorer, times(1)).scoreAll(CAMPAIGN_ID, START, END);
    }

    /** 没有活动时 freshBoard 也是空的，不去扫库 */
    @Test
    void 没有活动时freshBoard也为空() {
        when(campaignMapper.selectActive()).thenReturn(null);

        assertThat(service.freshBoard()).isEmpty();
        verify(statsMapper, never()).listEligibleUsers();
    }

    // ---- 手搓行 ----

    private void running(LocalDateTime startAt, LocalDateTime endAt) {
        Campaign c = new Campaign();
        c.setId(CAMPAIGN_ID);
        c.setName("LDC 瓜分活动");
        c.setStartAt(startAt);
        c.setEndAt(endAt);
        c.setPrizePool(POOL);
        c.setStatus(Campaign.STATUS_RUNNING);
        when(campaignMapper.selectActive()).thenReturn(c);
    }

    private static EligibleUserRow user(long userId, String username) {
        EligibleUserRow r = new EligibleUserRow();
        r.setUserId(userId);
        r.setUsername(username);
        r.setLinuxDoId(String.valueOf(10000 + userId));   // 纯数字 → claimable
        return r;
    }

    private static CampaignScore score(long userId, boolean claimable, BigDecimal finalScore) {
        return new CampaignScore(userId, "u" + userId, claimable, 0, 0,
                BigDecimal.ZERO, 0, finalScore, List.of());
    }

    private static CampaignScore pick(List<CampaignScore> board, long userId) {
        return board.stream().filter(s -> s.userId() == userId).findFirst()
                .orElseThrow(() -> new AssertionError("榜上没有 " + userId + "：" + board));
    }

    private static ScoreItem item(List<ScoreItem> items, String code) {
        return items.stream().filter(i -> i.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("清单里没有 " + code + " 这条：" + items));
    }
}
