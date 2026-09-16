package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.mapper.CampaignStatsMapper;
import com.mawai.wiibsim.campaign.model.CampaignScore;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.campaign.model.MyCampaignView;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.model.SettlementBasis;
import com.mawai.wiibsim.campaign.score.TradeScorer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 把交易分、日常分、投票分并成一张全站积分表，「我的积分」「排行」「预估 LDC」全从这一份结果里取。
 * 单独成类为了避开 CampaignService ⇄ 签到/投票的构造器循环依赖；
 * 全站一次算完 + 缓存 60 秒，三个视图同源不对不上账。
 */
@Service
@RequiredArgsConstructor
public class CampaignScoreService {

    /** 投票分在明细里的 code，与 {@link TradeScorer}/{@link CampaignCheckinService} 那些同级 */
    public static final String CODE_VOTE = "VOTE";

    private static final String BOARD_KEY = "campaign:board:";
    private static final Duration BOARD_TTL = Duration.ofSeconds(60);

    private final CampaignService campaignService;
    private final CampaignStatsMapper statsMapper;
    private final TradeScorer tradeScorer;
    private final CampaignCheckinService checkinService;
    private final CampaignVoteService voteService;
    private final CacheService cacheService;

    /**
     * 全站积分表，按最终分降序。缓存 60 秒。<b>展示用</b>；发钱之前请改用 {@link #freshBoard()}。
     * 不落进度表，唯一真相是业务表，现扫现算；读路径用 current() 让开赛前后都能展示。
     */
    public List<CampaignScore> scoreBoard() {
        Campaign c = campaignService.current();
        if (c == null) return List.of();

        String cached = cacheService.get(BOARD_KEY + c.getId());
        if (cached != null) {
            return MAPPER.readValue(cached, new TypeReference<List<CampaignScore>>() {});
        }
        return computeAndCache(c);
    }

    /**
     * 无视缓存重算一份，并把新结果写回缓存。<b>结算必须走这个，不能走 {@link #scoreBoard()}。</b>
     * 缓存的榜可能不含结算当刻最后落库的那批分，拿它分池子发出去就纠不回来；
     * 覆写不先删，为了不留"键不在"的空窗引发读请求各自全表扫。
     */
    public List<CampaignScore> freshBoard() {
        Campaign c = campaignService.current();
        if (c == null) return List.of();
        return computeAndCache(c);
    }

    private List<CampaignScore> computeAndCache(Campaign c) {
        List<CampaignScore> board = computeBoard(c);
        cacheService.set(BOARD_KEY + c.getId(), MAPPER.writeValueAsString(board), BOARD_TTL);
        return board;
    }

    /**
     * 现扫现算一份积分表。
     * 循环以 listEligibleUsers 名单为轴、分数图取值——反过来遍历分数图会把机器人放进榜里。
     * 活动进行中的分是下限不是终值（WON/最后一天投票分要等结算才写），别当 bug 修。
     */
    private List<CampaignScore> computeBoard(Campaign c) {
        Map<Long, List<ScoreItem>> trade = tradeScorer.scoreAll(c.getId(), c.getStartAt(), c.getEndAt());
        Map<Long, List<ScoreItem>> daily = checkinService.scoreAll(c);
        Map<Long, BigDecimal> vote = voteService.voteScoreByUser(c.getId());

        List<CampaignScore> board = new ArrayList<>();
        for (EligibleUserRow u : statsMapper.listEligibleUsers()) {
            List<ScoreItem> tradeItems = trade.getOrDefault(u.getUserId(), List.of());
            List<ScoreItem> dailyItems = daily.getOrDefault(u.getUserId(), List.of());
            BigDecimal voteScore = vote.getOrDefault(u.getUserId(), BigDecimal.ZERO);

            // 罚分单独拎出来：正分与扣分分开存，出争议时能直接回答"为什么是这个分"。
            // 两路的负分都要收：今天罚分只出自交易侧，但 items 是把两路拼在一起给前端看的，
            // 只收交易侧的话，将来日常侧一旦加一条扣分规则，明细里会多出一条总分不认的负数，
            // 而面板解释不了那个差额。收全了这条不变量就是结构上成立的，不靠"碰巧没有"
            int tradeScore = sumPositive(tradeItems);
            int dailyScore = sumPositive(dailyItems);
            int penalty = sumNegative(tradeItems) + sumNegative(dailyItems);

            BigDecimal raw = BigDecimal.valueOf(tradeScore + dailyScore + penalty).add(voteScore);
            BigDecimal finalScore = raw.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

            List<ScoreItem> items = new ArrayList<>(tradeItems);
            items.addAll(dailyItems);
            if (voteScore.signum() > 0) {
                items.add(new ScoreItem(CODE_VOTE, "每日多空投票", 0, voteScore));
            }

            // 一分没有的人不上榜：榜单里挂一串 0 分只是噪音。
            // 判 items 是为了留住"扣到 0 分"的人 —— 他有明细，得让他看见自己被扣在哪
            if (finalScore.signum() == 0 && items.isEmpty()) continue;

            board.add(new CampaignScore(u.getUserId(), u.getUsername(), u.claimable(),
                    tradeScore, dailyScore, voteScore, penalty, finalScore, items));
        }

        board.sort(Comparator.comparing(CampaignScore::finalScore).reversed()
                .thenComparing(CampaignScore::userId));
        return board;
    }

    private static int sumPositive(List<ScoreItem> items) {
        return items.stream().map(ScoreItem::score)
                .filter(s -> s.signum() > 0).mapToInt(BigDecimal::intValue).sum();
    }

    private static int sumNegative(List<ScoreItem> items) {
        return items.stream().map(ScoreItem::score)
                .filter(s -> s.signum() < 0).mapToInt(BigDecimal::intValue).sum();
    }

    /**
     * 参与 LDC 分配的权重：能领取且分数为正的人。分母只算这些人。
     * <p>
     * <b>结算别直接调这个，调 {@link #settlementBasis()}</b>；本方法保持公开只为
     * {@link #myView} 那条读路径（它要的正是缓存榜的分母）。
     * 用 LinkedHashMap 保住榜单顺序，结算按名次逐个发放才能对着日志核账。
     */
    public Map<Long, BigDecimal> eligibleWeights(List<CampaignScore> board) {
        Map<Long, BigDecimal> weights = new LinkedHashMap<>();
        for (CampaignScore s : board) {
            if (s.claimable() && s.finalScore().signum() > 0) weights.put(s.userId(), s.finalScore());
        }
        return weights;
    }

    /**
     * 结算专用：现算一份榜 + 从它筛出权重，一次给全。<b>结算只许走这个入口。</b>
     * 合成一个方法让"吃了缓存的榜"和"榜与权重取自两次计算"这两种误用在类型上不可表达。
     * 调用方须先算榜再翻活动状态，别反过来依赖 selectActive 的 WHERE 恰好放行 SETTLING。
     */
    public SettlementBasis settlementBasis() {
        List<CampaignScore> board = freshBoard();
        return new SettlementBasis(board, eligibleWeights(board));
    }

    /**
     * 活动页一次要的全部数据。没有活动返回 null（前端据此隐藏活动入口）。
     * 没上榜的人给全零兜底不抛：rank = 0 表示"还没上榜"。
     * checkedToday 不筛活动时间窗（计分侧筛），挪过窗口会出现"显示已签到但不计分"——
     * 已知口径缺口，只影响对勾，要补该补在 checkedToday 里而不是这里抄日界判断。
     */
    public MyCampaignView myView(Long userId) {
        Campaign c = campaignService.current();
        if (c == null) return null;

        List<CampaignScore> board = scoreBoard();
        // 兜底行里的 username 与 claimable 是占位，不是事实：没上榜的人有两种，
        // 一种是在参与名单里但一分没挣（claimable 真该是 true），另一种压根不在名单里
        // —— 邀请码用户（linux_do_id 为 NULL）照样能登录、能调 /me，对他们 false 才是对的。
        // 这里分不出是哪种，就取保守的那个；反正兜底行永远进不了 eligibleWeights，
        // 这两个字段对发钱没有任何影响，前端也不该据此分支
        CampaignScore me = board.stream().filter(s -> s.userId().equals(userId)).findFirst()
                .orElse(new CampaignScore(userId, null, false, 0, 0, BigDecimal.ZERO, 0,
                        BigDecimal.ZERO, List.of()));

        Map<Long, BigDecimal> weights = eligibleWeights(board);
        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal estimated = (total.signum() > 0 && weights.containsKey(userId))
                ? c.getPrizePool().multiply(me.finalScore()).divide(total, 2, RoundingMode.DOWN)
                : BigDecimal.ZERO;

        int rank = 0;
        for (int i = 0; i < board.size(); i++) {
            if (board.get(i).userId().equals(userId)) { rank = i + 1; break; }
        }

        return new MyCampaignView(c.getId(), c.getName(),
                c.getStartAt().toString(), c.getEndAt().toString(), c.getPrizePool(),
                me, total, estimated, rank, board.size(),
                checkinService.checkedToday(c.getId(), userId),
                voteService.board(userId));
    }
}
