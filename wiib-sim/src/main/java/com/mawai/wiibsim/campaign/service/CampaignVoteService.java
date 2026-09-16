package com.mawai.wiibsim.campaign.service;

import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.i18n.MessageCatalog;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.entity.CampaignVote;
import com.mawai.wiibsim.campaign.mapper.CampaignVoteMapper;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import com.mawai.wiibsim.campaign.model.VoteTally;
import com.mawai.wiibsim.campaign.score.ScoreRules;
import com.mawai.wiibsim.campaign.score.VoteScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.mawai.wiibcommon.util.JsonUtils.MAPPER;

/**
 * 每日多空投票，标的固定 BTC + 黄金。
 * <p>
 * 投票日用 UTC 日，与结算依据（Binance 1d K 线，UTC 日切）同时区，边界票才对得上；
 * 签到日是服务器本地日（见 {@link CampaignCheckinService}），两个口径刻意不同。
 * <p>
 * <b>投的是明天，不是今天</b>：票盖 {@code utcToday() + 1}（见 {@link #votingDate()}），
 * 收票在目标日开始前截止（UTC 23:55，见 {@link #requireNotLocked}），杜绝照着当日 K 线填答案。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignVoteService {

    /**
     * 投票标的，顺序即前端卡片顺序。
     * <p>只存 symbol 不存展示名：展示名要跟界面语言，而这里下发的是给所有人共用的一份数据。
     * 卡片标题由前端查自己的 {@code market:coinName.*}；后端提示里要用到名字的（{@link #vote}
     * 的重复投票拒因）现查 {@code campaign.vote.symbol.*}。
     */
    public static final List<String> SYMBOLS = List.of(CampaignVote.SYMBOL_BTC, CampaignVote.SYMBOL_GOLD);

    private final CampaignVoteMapper voteMapper;
    private final CampaignService campaignService;
    private final BinanceRestClient binanceRestClient;
    /** 投票拦阻文案跟界面语言 */
    private final MessageCatalog messages;

    /** 结算锁盘窗口的两端（UTC 时刻），半开区间 [23:55, 00:05) */
    private static final LocalTime LOCK_FROM = LocalTime.of(23, 55);
    private static final LocalTime LOCK_UNTIL = LocalTime.of(0, 5);

    /** 当前 UTC 交易日 */
    public static LocalDate utcToday() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /**
     * 此刻投出的票落在哪个 UTC 交易日 —— <b>明天</b>。
     * <p>
     * 下票与看板必须共用这一个式子：两边各写各的，看板显示的票况就不是你正要投的那天的，
     * "我投了没"和"两边多少票"会各说各话。
     */
    public static LocalDate votingDate() {
        return votingDate(Instant.now());
    }

    /** 同上，时刻由调用方给 —— 测试用它把日界钉死，不必等到真的跨 UTC 0 点 */
    static LocalDate votingDate(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).plusDays(1);
    }

    /**
     * 投票。多空二选一由唯一索引 (campaign,user,date,symbol) 保证 —— 选了多就插不进空。
     */
    public void vote(Long userId, String symbol, String direction) {
        vote(userId, symbol, direction, Instant.now());
    }

    /**
     * 同上，时刻由调用方给。<b>只有测试该调这个重载</b>，生产走上面那个三参的。
     * 时刻走参数为了测得到翻页/锁盘边界，且比加 Clock 字段轻。
     */
    void vote(Long userId, String symbol, String direction, Instant now) {
        Campaign c = campaignService.requireRunning();
        if (!SYMBOLS.contains(symbol)) throw new BizException(messages.get("campaign.vote.unsupportedSymbol"));
        if (!CampaignVote.UP.equals(direction) && !CampaignVote.DOWN.equals(direction)) {
            throw new BizException(messages.get("campaign.vote.badDirection"));
        }
        requireNotLocked(now);

        LocalDate day = votingDate(now);
        CampaignVote v = new CampaignVote();
        v.setCampaignId(c.getId());
        v.setUserId(userId);
        v.setVoteDate(day);
        v.setSymbol(symbol);
        v.setDirection(direction);
        try {
            voteMapper.insert(v);
        } catch (DuplicateKeyException e) {
            // 带上日期：投的是明天，用户点下去的那一刻和那一票管的那一天不是同一天，
            // 只说"今天已经投过了"会让人以为自己投的是当天
            throw new BizException(messages.get("campaign.vote.alreadyVoted",
                    Map.of("day", day, "symbol", messages.get("campaign.vote.symbol." + symbol))));
        }
    }

    /**
     * 锁盘：UTC 23:55 - 00:05 这 10 分钟不收票。
     * 这段横跨"票落在哪一天"的翻页点，封上它，点按钮那几秒目标日不会被悄悄换掉。
     */
    private void requireNotLocked(Instant now) {
        LocalTime t = now.atZone(ZoneOffset.UTC).toLocalTime();
        if (!t.isBefore(LOCK_FROM) || t.isBefore(LOCK_UNTIL)) {
            throw new BizException(messages.get("campaign.vote.lockWindow"));
        }
    }

    /**
     * 明日票况：两个标的各一条。看的是 {@link #votingDate()} 那一天，也就是正要投的那天。
     * 用 current() 不用 requireRunning()：读路径开赛前后都要能展示；锁盘闸只拦 {@link #vote} 侧。
     */
    public List<VoteBoard> board(Long userId) {
        Campaign c = campaignService.current();
        if (c == null) return List.of();
        LocalDate day = votingDate();

        Map<String, String> mine = new HashMap<>();
        for (CampaignVote v : voteMapper.listMine(c.getId(), userId, day)) {
            mine.put(v.getSymbol(), v.getDirection());
        }

        List<VoteBoard> out = new ArrayList<>(SYMBOLS.size());
        for (String symbol : SYMBOLS) {
            // GROUP BY 某方向没票就不出行，起手 0
            long up = 0, down = 0;
            for (Map<String, Object> row : voteMapper.countByDirection(c.getId(), day, symbol)) {
                long cnt = ((Number) row.get("cnt")).longValue();
                // 两方向各判一次不用 else 兜底：库里出现第三种方向时宁可不显示
                if (CampaignVote.UP.equals(row.get("direction"))) up = cnt;
                else if (CampaignVote.DOWN.equals(row.get("direction"))) down = cnt;
            }
            out.add(new VoteBoard(symbol, up, down, mine.get(symbol)));
        }
        return out;
    }

    // ==================== 结算 ====================

    /**
     * 结算某个 UTC 交易日的投票。幂等：回填走 CAS（result IS NULL），重跑不覆盖不重发；
     * 漏结算隔天补跑即可。
     * <p>
     * 可分池靠反推不存状态：pool = 100 × (活动首日到投出日的天数) − 截至该日已发出的分，
     * 顺延/没人猜对/封顶剩余都自动含在差里（口径细节见 {@link #poolOf}）。
     * <p>
     * 用 current() 不用 requireRunning()：最后一张票管的 UTC 日落在 endAt 之后，
     * 要等活动结束次日的回扫才结得上，判了窗口这些票永远发不出分。
     * 只结已过完的 UTC 日：当天日线还在长，半根蜡烛发分 CAS 落了改不回来。
     */
    public void settleDay(LocalDate utcDay) {
        if (!utcDay.isBefore(utcToday())) {
            log.info("活动投票结算：{} 还没过完，不结", utcDay);
            return;
        }

        Campaign c = campaignService.current();
        if (c == null) return;

        List<CampaignVote> votes = voteMapper.listUnsettled(c.getId(), utcDay);
        if (votes.isEmpty()) {
            log.info("活动投票结算：{} 无待结算票", utcDay);
            return;
        }

        // 每个标的当日的涨跌：日线收盘 vs 前日收盘
        Map<String, String> outcome = new HashMap<>();
        for (String symbol : SYMBOLS) {
            String o = resolveOutcome(symbol, utcDay);
            if (o == null) {
                log.warn("活动投票结算：{} 取不到 {} 的日线，本日整体推迟结算", utcDay, symbol);
                return;   // 拿不到价就整天不结算，下次任务重跑；绝不用残缺数据发分
            }
            outcome.put(symbol, o);
        }

        // 先判每票输赢，再按赢家均分
        Map<Long, Integer> correct = new LinkedHashMap<>();
        Map<Long, List<CampaignVote>> winners = new LinkedHashMap<>();
        List<CampaignVote> losers = new ArrayList<>();
        List<CampaignVote> deferred = new ArrayList<>();

        for (CampaignVote v : votes) {
            // 库里出了 SYMBOLS 之外的 symbol 时这里 NPE —— 是有意留响的。
            // vote() 已把 symbol 白名单校验过，唯一入口不产生这种行；真出现了（手工塞库）
            // 宁可炸出来记进日志，也不能用 Objects.equals 把它悄悄判成 LOSE 并标成已结算 ——
            // CAS 之后就再也纠不回来了。别"顺手修"成 Objects.equals。
            String o = outcome.get(v.getSymbol());
            if (CampaignVote.DEFERRED.equals(o)) {
                deferred.add(v);
            } else if (o.equals(v.getDirection())) {
                correct.merge(v.getUserId(), 1, Integer::sum);
                winners.computeIfAbsent(v.getUserId(), k -> new ArrayList<>()).add(v);
            } else {
                losers.add(v);
            }
        }

        BigDecimal pool = poolOf(c, utcDay);
        VoteTally tally = VoteScorer.allocate(correct, pool);

        // 一个人当日的分摊到他那几张赢票上：末票兜差额，保证逐票之和等于该人应得
        winners.forEach((userId, list) -> {
            BigDecimal total = tally.awarded().getOrDefault(userId, BigDecimal.ZERO);
            BigDecimal each = total.divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.DOWN);
            BigDecimal used = BigDecimal.ZERO;
            for (int i = 0; i < list.size(); i++) {
                BigDecimal s = (i == list.size() - 1) ? total.subtract(used) : each;
                used = used.add(s);
                voteMapper.settle(list.get(i).getId(), CampaignVote.WIN, s);
            }
        });
        losers.forEach(v -> voteMapper.settle(v.getId(), CampaignVote.LOSE, BigDecimal.ZERO));
        deferred.forEach(v -> voteMapper.settle(v.getId(), CampaignVote.DEFERRED, BigDecimal.ZERO));

        log.info("活动投票结算完成 {}：池={} 赢家={}人 顺延={}", utcDay, pool, winners.size(), tally.carryOver());
    }

    /**
     * 当日可分池 = 100 × 已过天数 − <b>截至这一天</b>已发出的分；下限 0。
     * 减"截至这天"让补跑与顺序结算逐位相同，漏结算隔多久补都是同一个结果。
     * 乱序补结会让总额略超发，有意取舍；防线是 {@link com.mawai.wiibsim.campaign.CampaignTask}
     * 从最老一天往回扫，保证不乱序。
     * <p>
     * 天数按票<b>投出那天</b>算（投票日 = 投出日 + 1，故 {@code minusDays(1)}），
     * 跟着 utcDay 走整条链会多算一天；{@code Math.max(days, 1)} 兜住活动首日不足一整天的票。
     * start_at 是服务器本地时间、投出日是 UTC 日，正偏移时区下直接减恰好守恒；
     * 迁到负偏移时区须先把 start_at 折成 UTC 日再减，否则末日上限多发 100。
     */
    private BigDecimal poolOf(Campaign c, LocalDate utcDay) {
        // minusDays(1)：这批票是投票日的前一个 UTC 日投出来的，额度按投出那天算
        long days = ChronoUnit.DAYS.between(c.getStartAt().toLocalDate(), utcDay.minusDays(1)) + 1;
        BigDecimal entitled = ScoreRules.VOTE_DAILY_POOL.multiply(BigDecimal.valueOf(Math.max(days, 1)));
        return entitled.subtract(voteMapper.sumScoreUpTo(c.getId(), utcDay)).max(BigDecimal.ZERO);
    }

    /**
     * 该 UTC 日的涨跌：日线收盘 vs 前日收盘。平盘、或该标的这天压根没开市，返回 DEFERRED；
     * 取不到价返回 null。
     * <p>
     * 走 Binance 不走库里 kline_history：那张表只落 5m 且无 XAUUSDT；1d K 线 UTC 日切与投票日同时区。
     * Binance 返回的是 endTime 之前的最后几根，休市日会给上一交易日那根——
     * 所以要拿 openTime 对一次请求日，对不上即判这天无交易日。
     * <p>
     * DEFERRED 与 null 是两回事：DEFERRED = 该标的这天没结果（判平不计分，另一标的照常结算）；
     * null = 没拿到数据（整天不结算，等任务重跑）。合并任一方向都会错结算。
     */
    private String resolveOutcome(String symbol, LocalDate utcDay) {
        try {
            long dayStartMs = utcDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long endMs = utcDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
            String json = binanceRestClient.getFuturesKlinesLight(symbol, "1d", 3, endMs);
            if (json == null || json.isBlank()) return null;
            ArrayNode rows = MAPPER.readValue(json, ArrayNode.class);
            if (rows.size() < 2) return null;

            JsonNode lastRow = rows.get(rows.size() - 1);
            Long openTime = openTimeOf(lastRow);
            if (openTime == null) return null;
            if (openTime.longValue() != dayStartMs) {
                log.info("活动投票结算：{} {} 当日无日线（最后一根 openTime={}），该标的判顺延",
                        symbol, utcDay, openTime);
                return CampaignVote.DEFERRED;
            }

            BigDecimal close = closeOf(lastRow);
            BigDecimal prevClose = closeOf(rows.get(rows.size() - 2));
            if (close == null || prevClose == null) return null;

            int cmp = close.compareTo(prevClose);
            if (cmp > 0) return CampaignVote.UP;
            if (cmp < 0) return CampaignVote.DOWN;
            return CampaignVote.DEFERRED;
        } catch (Exception e) {
            log.warn("活动投票结算：取 {} {} 日线失败: {}", symbol, utcDay, e.getMessage());
            return null;
        }
    }

    /**
     * 收盘价固定在下标 4：getFuturesKlinesLight 只裁 Binance 12 元组的尾部 8-11，
     * 前 8 位原序原位，精简失败回退原始串 close 也在 4。
     */
    private static BigDecimal closeOf(JsonNode row) {
        return row.size() < 5 ? null : row.path(4).asDecimal(null);
    }

    /** openTime 在下标 0（同样是 Binance 原始下标，见 {@link #closeOf}）；1d 线的它就是该 UTC 日 0 点 */
    private static Long openTimeOf(JsonNode row) {
        return row.hasNonNull(0) ? row.get(0).asLong() : null;
    }

    /** 全场投票分：userId → 累计得分 */
    public Map<Long, BigDecimal> voteScoreByUser(Long campaignId) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> row : voteMapper.sumScoreByUser(campaignId)) {
            out.put(((Number) row.get("user_id")).longValue(), (BigDecimal) row.get("total"));
        }
        return out;
    }
}
