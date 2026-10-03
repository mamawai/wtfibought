package com.mawai.wiibsim.service;
import com.mawai.wiibcommon.cache.CacheService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mawai.wiibcommon.dto.RankingDTO;
import com.mawai.wiibcommon.entity.*;
import com.mawai.wiibsim.mapper.CryptoOrderMapper;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.PredictionBetMapper;
import com.mawai.wiibsim.mapper.PublicTradeMapper;
import com.mawai.wiibsim.service.impl.AssetValuationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RankingService {

    private final UserService userService;
    private final CryptoPositionService cryptoPositionService;
    private final CacheService cacheService;
    private final CryptoOrderMapper cryptoOrderMapper;
    private final FuturesPositionMapper futuresPositionMapper;
    private final FuturesOrderMapper futuresOrderMapper;
    private final PredictionBetMapper predictionBetMapper;
    private final AssetValuationService assetValuationService;
    private final PublicTradeMapper publicTradeMapper;

    private static final String RANKING_KEY = "ranking:top";
    /**
     * 入榜人数上限。原来是 50——榜要分页翻，卡 50 等于第 3 页往后永远空着。
     * 留个 500 是防缓存对象无限膨胀（整榜是一个 Redis value），不是业务上限。
     */
    private static final int MAX_RANKED = 500;

    /** 单页封顶，同 force-orders / 全站成交那条口径 */
    private static final int MAX_PAGE_SIZE = 100;

    @Value("${trading.initial-balance:10000}")
    private BigDecimal initialBalance;

    public List<RankingDTO> getRanking() {
        // DTO 字段演进后（如去掉 buffProfit），Redis 里旧结构的缓存反序列化会抛异常，
        // 当未命中处理刷新覆盖，别让一份 15 分钟就过期的缓存把榜打挂
        List<RankingDTO> cached = null;
        try {
            cached = cacheService.getList(RANKING_KEY);
        } catch (Exception e) {
            log.warn("排行榜缓存反序列化失败，按未命中刷新: {}", e.getMessage());
        }
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return refreshRanking();
    }

    public List<RankingDTO> refreshRanking() {
        log.info("开始刷新排行榜");
        long start = System.currentTimeMillis();

        // ────── 1. 用户与持仓快照 ──────
        // 只算交易过的人（有过 FILLED 现货或合约单）。没交易过的挂着初始资金进榜，
        // 排出来是一串一模一样的 10000，把真在交易的人挤到后面去
        Set<Long> tradedUserIds = new HashSet<>(publicTradeMapper.selectTradedUserIds());
        List<User> users = userService.list().stream()
                .filter(u -> tradedUserIds.contains(u.getId()))
                .toList();
        Map<Long, List<CryptoPosition>> cryptoPositionMap = cryptoPositionService.list().stream()
                .collect(Collectors.groupingBy(CryptoPosition::getUserId));
        List<FuturesPosition> allFuturesPositions = futuresPositionMapper.selectList(
                new LambdaQueryWrapper<FuturesPosition>().eq(FuturesPosition::getStatus, "OPEN"));
        Map<Long, List<FuturesPosition>> futuresPositionMap = allFuturesPositions.stream()
                .collect(Collectors.groupingBy(FuturesPosition::getUserId));

        // ────── 2. 价格 ──────
        Map<String, BigDecimal> cryptoPriceMap = cryptoPositionService.fetchCryptoPriceMap();
        Map<String, BigDecimal> futuresMarkPriceMap = buildFuturesMarkPriceMap(allFuturesPositions);

        // ────── 3. 预测持仓可变现价值(bid×contracts)，与资产页/快照/破产判定同口径 ──────
        Map<Long, List<PredictionBet>> activeBetMap = predictionBetMapper.selectList(
                        new LambdaQueryWrapper<PredictionBet>().eq(PredictionBet::getStatus, "ACTIVE")).stream()
                .collect(Collectors.groupingBy(PredictionBet::getUserId));
        Map<String, BigDecimal> predictionBidCache = new HashMap<>();

        // ────── 4. 交易盈利批量聚合（口径：交易净盈亏，优惠券折扣要剔掉） ──────
        Map<Long, BigDecimal> futuresNetMap = toUserAmountMap(futuresOrderMapper.sumNetPnlAfterCommissionAll());
        Map<Long, BigDecimal> futuresFundingFeeMap = toUserAmountMap(futuresPositionMapper.sumFundingFeeTotalAll());
        Map<Long, BigDecimal> predictionRealizedMap = toUserAmountMap(predictionBetMapper.sumRealizedProfitAfterBuyFeeAll());
        Map<Long, BigDecimal> cryptoBuyMap = toUserAmountMap(cryptoOrderMapper.sumBuyFilledAmountAll());
        Map<Long, BigDecimal> cryptoSellMap = toUserAmountMap(cryptoOrderMapper.sumSellFilledAmountAll());
        Map<Long, BigDecimal> cryptoDiscountMap = toUserAmountMap(cryptoOrderMapper.sumBuyDiscountAll());

        // ────── 5. 逐用户聚合 ──────
        List<RankingDTO> rankings = new ArrayList<>(users.size());
        for (User user : users) {
            Long uid = user.getId();

            BigDecimal balance = nz(user.getBalance());
            BigDecimal frozen = nz(user.getFrozenBalance());
            BigDecimal loanPrincipal = nz(user.getMarginLoanPrincipal());
            BigDecimal loanInterest = nz(user.getMarginInterestAccrued());

            BigDecimal cryptoMarketValue = cryptoMarketValue(cryptoPositionMap.get(uid), cryptoPriceMap);
            FuturesPnL futures = futuresPnL(futuresPositionMap.get(uid), futuresMarkPriceMap);

            BigDecimal predictionValue = assetValuationService.predictionMarketValue(activeBetMap.get(uid), predictionBidCache);

            BigDecimal totalAssets = balance.add(frozen).add(nz(user.getGameBalance()))
                    .add(cryptoMarketValue)
                    .add(futures.value()).add(predictionValue)
                    .subtract(loanPrincipal).subtract(loanInterest);

            // 交易盈利 = 合约净盈亏 + 现货现金流(扣优惠券折扣) + 预测已结算净盈亏
            BigDecimal buffDiscount = nz(cryptoDiscountMap.get(uid));
            BigDecimal futuresProfit = nz(futuresNetMap.get(uid))
                    .add(futures.unrealizedPnl())
                    .subtract(nz(futuresFundingFeeMap.get(uid)));
            BigDecimal cryptoProfit = nz(cryptoSellMap.get(uid))
                    .subtract(nz(cryptoBuyMap.get(uid)))
                    .add(cryptoMarketValue)
                    .subtract(buffDiscount);
            BigDecimal predictionProfit = nz(predictionRealizedMap.get(uid));
            BigDecimal tradingProfit = futuresProfit.add(cryptoProfit).add(predictionProfit);

            rankings.add(getRankingDTO(user, totalAssets, tradingProfit));
        }

        // ────── 6. 排序 / 截断 / 缓存 ──────
        rankings.sort(Comparator.comparing(RankingDTO::getTotalAssets).reversed());
        List<RankingDTO> ranked = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_RANKED, rankings.size()); i++) {
            RankingDTO dto = rankings.get(i);
            dto.setRank(i + 1);
            ranked.add(dto);
        }
        cacheService.setObject(RANKING_KEY, ranked, Duration.ofMinutes(15));

        log.info("排行榜刷新完成，交易过的用户{}人，入榜{}人，耗时{}ms",
                users.size(), ranked.size(), System.currentTimeMillis() - start);
        return ranked;
    }

    /**
     * 榜单排序维度。
     * 没有"收益率"档：初始资金全站同一常数，收益率与总资产同序，是同一张榜换个名字。
     * 没有"钱包余额"档：那是现金构成，不是成绩。
     */
    public enum RankingSort {
        /** 总资产。默认榜，含优惠券带来的便宜 */
        ASSETS(Comparator.comparing(RankingDTO::getTotalAssets)),
        /** 交易盈利。剔掉优惠券，只看靠交易赚到的钱 */
        TRADING_PROFIT(Comparator.comparing(RankingDTO::getTradingProfit));

        private final Comparator<RankingDTO> comparator;

        RankingSort(Comparator<RankingDTO> comparator) {
            this.comparator = comparator;
        }

        /** 认不出的取值一律退回默认榜，不给前端传错参就 500 的机会 */
        static RankingSort of(String name) {
            if (name == null) return ASSETS;
            for (RankingSort s : values()) {
                if (s.name().equalsIgnoreCase(name)) return s;
            }
            return ASSETS;
        }
    }

    /**
     * 整榜内存切片分页。排名是全局的，必须先算完整榜才有名次，所以不做 SQL 分页。
     * <p>
     * 换排序维度也在内存里重排，不重新查库——整榜本来就已经全在手上（最多 500 人）。
     * <b>名次跟着当前维度重算</b>（见 {@link #sortedBy}）：按交易盈利排却显示总资产名次，
     * 会排出 01、07、03 这种跳号，看的人只会以为榜坏了。
     */
    public Page<RankingDTO> getRankingPage(String sort, int pageNum, int pageSize) {
        int safeNum = Math.max(pageNum, 1);
        int safeSize = Math.clamp(pageSize, 1, MAX_PAGE_SIZE);

        List<RankingDTO> all = sortedBy(getRanking(), RankingSort.of(sort));

        int from = Math.min((safeNum - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());

        Page<RankingDTO> page = new Page<>(safeNum, safeSize, all.size());
        page.setRecords(all.subList(from, to));
        return page;
    }

    /**
     * 整榜按指定维度重排，名次跟着重编。ASSETS 就是刷榜时排好的那份，原样返回。
     * <p>
     * 改 rank 不会污染缓存：{@link #getRanking()} 命中时是从 Redis 反序列化出来的新对象，
     * 未命中时是 {@link #refreshRanking()} 刚 new 出来、且已经写完缓存的那批。
     */
    private static List<RankingDTO> sortedBy(List<RankingDTO> all, RankingSort dimension) {
        if (dimension == RankingSort.ASSETS) return all;
        List<RankingDTO> sorted = new ArrayList<>(all);
        sorted.sort(dimension.comparator.reversed());
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).setRank(i + 1);
        }
        return sorted;
    }

    /** 单个用户的榜单行，名次按总资产维度。等价于 {@code findRanking(userId, null)} */
    public RankingDTO findRanking(Long userId) {
        return findRanking(userId, null);
    }

    /**
     * 单个用户的榜单行，名次按 sort 维度算（认不出的取值退回 ASSETS，同分页那条口径）；没上榜返回 null。
     * <p>
     * 缓存最长 15 分钟，刚下完第一单的人还没进榜，直接返 null 会让他点自己的详情页扑空。
     * 所以缓存里没有时再刷一次，但要先过两道闸：
     * 一是<b>整榜没被 MAX_RANKED 截断</b>——截断了说明榜外还排着人，刷完他多半照样进不来，白刷一次全量；
     * 二是<b>一条 count 确认这人真交易过</b>——不设这道，拿不存在的 userId 循环打接口，每次请求都触发全量刷榜。
     */
    public RankingDTO findRanking(Long userId, String sort) {
        RankingSort dimension = RankingSort.of(sort);
        List<RankingDTO> all = getRanking();
        RankingDTO hit = lookup(sortedBy(all, dimension), userId);
        if (hit != null) return hit;
        if (all.size() >= MAX_RANKED) return null;
        if (publicTradeMapper.countByUser(userId) == 0) return null;
        return lookup(sortedBy(refreshRanking(), dimension), userId);
    }

    private static RankingDTO lookup(List<RankingDTO> list, Long userId) {
        return list.stream().filter(d -> userId.equals(d.getUserId())).findFirst().orElse(null);
    }

    private RankingDTO getRankingDTO(User user, BigDecimal totalAssets, BigDecimal tradingProfit) {
        BigDecimal profit = totalAssets.subtract(initialBalance);
        BigDecimal profitPct = initialBalance.compareTo(BigDecimal.ZERO) > 0
                ? profit.divide(initialBalance, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("100"))
                : BigDecimal.ZERO;

        RankingDTO dto = new RankingDTO();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setAvatar(user.getAvatar());
        dto.setTotalAssets(totalAssets.setScale(2, RoundingMode.HALF_UP));
        dto.setProfitPct(profitPct.setScale(2, RoundingMode.HALF_UP));
        dto.setTradingProfit(tradingProfit.setScale(2, RoundingMode.HALF_UP));
        dto.setBalanceWallet(balanceWalletOf(user));
        dto.setGameWallet(gameWalletOf(user));
        return dto;
    }

    /** 余额钱包 = 可用 + 冻结。冻结的钱（限价买单占用）仍属余额钱包，漏加会显示得比实际少 */
    static BigDecimal balanceWalletOf(User user) {
        return nz(user.getBalance()).add(nz(user.getFrozenBalance())).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal gameWalletOf(User user) {
        return nz(user.getGameBalance()).setScale(2, RoundingMode.HALF_UP);
    }

    /** 把 List<Map<"user_id"/"amount">> 转成 Map<userId, amount>，供批量聚合查询使用 */
    private static Map<Long, BigDecimal> toUserAmountMap(List<Map<String, Object>> rows) {
        Map<Long, BigDecimal> map = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            map.put(((Number) row.get("user_id")).longValue(), (BigDecimal) row.get("amount"));
        }
        return map;
    }

    /** null 兜 0，避免 user 字段或聚合 map 缺值时反复写三元 */
    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** 合约仓位估值同时产出 (margin+未实现盈亏) 与 单独的未实现盈亏，交易盈利计算两者都要 */
    private record FuturesPnL(BigDecimal value, BigDecimal unrealizedPnl) {}

    private static BigDecimal cryptoMarketValue(List<CryptoPosition> positions, Map<String, BigDecimal> priceMap) {
        if (positions == null) return BigDecimal.ZERO;
        BigDecimal sum = BigDecimal.ZERO;
        for (CryptoPosition cp : positions) {
            BigDecimal price = priceMap.getOrDefault(cp.getSymbol(), BigDecimal.ZERO);
            sum = sum.add(price.multiply(cp.getTotalQuantity()));
        }
        return sum;
    }

    private static FuturesPnL futuresPnL(List<FuturesPosition> positions, Map<String, BigDecimal> markPriceMap) {
        if (positions == null) return new FuturesPnL(BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (FuturesPosition fp : positions) {
            // 缺价保留保证金、浮盈亏按0(统一口径见 AssetValuationService)；旧版缺价按0计价会算出天文亏损
            BigDecimal markPrice = markPriceMap.get(fp.getSymbol());
            value = value.add(AssetValuationService.futuresPositionValue(fp, markPrice));
            unrealized = unrealized.add(AssetValuationService.futuresUnrealizedPnl(fp, markPrice));
        }
        return new FuturesPnL(value, unrealized);
    }

    /** 合约 mark 价，缺失退回现货价；都缺不入 map(旧版 toMap 遇 null 值会 NPE 炸掉整次刷新)，下游按"保留保证金"处理 */
    private Map<String, BigDecimal> buildFuturesMarkPriceMap(List<FuturesPosition> positions) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (String symbol : positions.stream().map(FuturesPosition::getSymbol).distinct().toList()) {
            BigDecimal p = assetValuationService.resolveFuturesPrice(symbol);
            if (p != null) map.put(symbol, p);
        }
        return map;
    }
}
