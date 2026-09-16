package com.mawai.wiibsim.campaign;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.model.CampaignScore;
import com.mawai.wiibsim.campaign.model.MyCampaignView;
import com.mawai.wiibsim.campaign.model.ScoreItem;
import com.mawai.wiibsim.campaign.service.CampaignScoreService;
import com.mawai.wiibsim.campaign.service.CampaignService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分总表的真跑验收：真库 + 真 Redis。
 * <p>
 * 这里要证的三件事，都是 mock 测证不了的：
 * <ol>
 *   <li><b>七条注解 SQL 一起发得出去、结果拼得成一张表。</b>
 *       {@link CampaignScoreService#scoreBoard()} 是全站唯一把交易 / 签到 / 投票三路汇到一起的
 *       地方，mock 测里三路的返回全是手搓的 —— 列名写错、类型对不上、某条在真 PG 上语法不过，
 *       一个都照不出来。<b>哪怕榜是空的，这条也在跑</b>：SQL 执行本身就是断言。</li>
 *   <li><b>record 经 JSON 存进真 Redis 再读回来逐字段不变。</b>{@link CampaignScore} 与
 *       {@link ScoreItem} 都是 record，JSON 对 record 的支持是纯运行期的事；
 *       而缓存命中只发生在第二个访问活动页的人身上 —— 坏了的表现是
 *       "第一个人看到榜、第二个人看到一堆 null"。这条用<b>合成键 + 手搓样本</b>钉，
 *       所以不依赖真库里有没有人挣到分，任何时候跑都在验。</li>
 *   <li><b>「我的积分」与「排行」同源。</b>这条得等榜上真有人，没数据时诚实地跳过。</li>
 * </ol>
 * <p>
 * 跑法（项目根）：
 * <pre>
 * set -a &amp;&amp; source .env.local &amp;&amp; set +a
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-sim -am -DskipTests=false \
 *   -Dtest=CampaignScoreRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * <p>
 * 前置：{@code sql/campaign.sql} 已落库且库里有一场 RUNNING 活动。
 * <p>
 * 写 Redis 的自律：往返用例用带 nanoTime 的合成键；{@code campaign:board:{id}} 真键
 * 前后各删一次——跑前删逼出真算，跑完删不给真环境留测试快照。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class CampaignScoreRealRunTest {

    @Autowired
    private CampaignScoreService scoreService;

    @Autowired
    private CampaignService campaignService;

    @Autowired
    private CacheService cacheService;

    /** 合成键。JUnit 每个用例新建实例，各拿一个，互不干扰 */
    private final String syntheticKey = "campaign:board:realruntest:" + System.nanoTime();

    @BeforeEach
    void 清掉缓存逼出一次真算() {
        boardKey().ifPresent(cacheService::delete);
    }

    @AfterEach
    void 清掉本次写进Redis的键() {
        boardKey().ifPresent(cacheService::delete);
        cacheService.delete(syntheticKey);
    }

    /**
     * 整表在真库上算得出来（七条 SQL 全发一遍），且每一行都满足榜单的三条不变量：
     * 能领取、总分不为负、按最终分降序同分按 userId 升序。
     * <p>
     * 榜空也不跳过：不变量此时空真，但"七条 SQL 在真 PG 上跑通"照样验到了。
     * claimable 恒为 true 是承重断言：名单正则与 claimable() 两处判据一旦漂移，
     * 会出现"算了分却领不到钱"的人。
     */
    @Test
    void 积分表在真库上算得出来且满足榜单不变量() {
        List<CampaignScore> board = scoreService.scoreBoard();

        assertThat(board).as("七条 SQL 任一条挂了这里根本走不到").isNotNull();
        assertThat(board)
                .as("名单已按纯数字 linux_do_id 筛过，榜上不该有任何一行是领不到钱的")
                .allMatch(CampaignScore::claimable);
        assertThat(board)
                .as("个人总分下限为 0，负分会把分母做小、抬高别人的到手额度")
                .allMatch(s -> s.finalScore().signum() >= 0);
        assertThat(board).isSortedAccordingTo(
                Comparator.comparing(CampaignScore::finalScore).reversed()
                        .thenComparing(CampaignScore::userId));

        // 算完必须落缓存，否则每个请求都要全表扫一遍
        assertThat(cacheService.get(boardKey().orElseThrow(
                () -> new AssertionError("库里应有一行 RUNNING 活动，先跑 sql/campaign.sql")))).isNotNull();
    }

    /**
     * ★ record 经 JSON 存进真 Redis、再读回来，逐字段（含 BigDecimal 标度）不变 ★
     * <p>
     * 走的是 {@link CacheService#set}/{@link CacheService#get} 这条真链路，
     * 与 {@link CampaignScoreService#scoreBoard()} 缓存那圈一模一样。
     * record 的 equals 是逐组件比的，BigDecimal.equals 还带标度，所以这一条同时钉住了
     * "字段没丢"（尤其 boolean claimable 与嵌套的 items 列表）和"14.50 没变成 14.5"。
     * <p>
     * 样本手搓：真库里榜可能是空的（活动排期没到），拿空数组转一圈什么都证不了。
     */
    @Test
    void 积分记录经真Redis往返逐字段不变() {
        List<CampaignScore> sample = List.of(
                new CampaignScore(-1L, "真跑样本", true, 10, 6, new BigDecimal("3.50"), -5,
                        new BigDecimal("14.50"),
                        List.of(ScoreItem.of("ROI40", "单仓位 ROI ≥ 40%", 2, 10),
                                ScoreItem.of("LIQ_TRIGGER", "触发强平", 1, -5),
                                new ScoreItem("VOTE", "每日多空投票", 0, new BigDecimal("3.50")))),
                new CampaignScore(-2L, "零分样本", true, 10, 0, BigDecimal.ZERO, -30,
                        new BigDecimal("0.00"),
                        List.of(ScoreItem.of("RESET_EXTRA", "付费重置账户", 1, -30))));

        cacheService.set(syntheticKey, MAPPER.writeValueAsString(sample), Duration.ofSeconds(60));
        String raw = cacheService.get(syntheticKey);

        assertThat(raw).as("写进 Redis 又读不出来，缓存那圈就是白写的").isNotNull();
        assertThat(MAPPER.readValue(raw, new TypeReference<List<CampaignScore>>() {}))
                .as("record 走 JSON + Redis 转一圈必须逐字段（含 BigDecimal 标度）不变")
                .isEqualTo(sample);
    }

    /**
     * 「我的积分」「排行」「预估 LDC」同源：myView 里的 me 就是榜上那一行，
     * 名次、分母、预估三者互相对得上。分开算迟早出现"我的积分 37、榜上写 36"。
     */
    @Test
    void 我的视图与榜单同源() {
        List<CampaignScore> board = scoreService.scoreBoard();
        Assumptions.assumeFalse(board.isEmpty(), "真库里还没人挣到分，榜是空的，无从比对");

        CampaignScore top = board.getFirst();
        MyCampaignView view = scoreService.myView(top.userId());

        assertThat(view.me()).as("me 必须就是榜上那一行，不是另算一遍").isEqualTo(top);
        assertThat(view.rank()).isEqualTo(1);
        assertThat(view.participants()).isEqualTo(board.size());

        Map<Long, BigDecimal> weights = scoreService.eligibleWeights(board);
        BigDecimal total = weights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(view.eligibleTotal()).isEqualByComparingTo(total);

        BigDecimal expected = weights.containsKey(top.userId()) && total.signum() > 0
                ? campaignService.current().getPrizePool()
                        .multiply(top.finalScore()).divide(total, 2, RoundingMode.DOWN)
                : BigDecimal.ZERO;
        assertThat(view.estimatedLdc()).isEqualByComparingTo(expected);
    }

    private Optional<String> boardKey() {
        Campaign c = campaignService.current();
        return c == null ? Optional.empty() : Optional.of("campaign:board:" + c.getId());
    }
}
