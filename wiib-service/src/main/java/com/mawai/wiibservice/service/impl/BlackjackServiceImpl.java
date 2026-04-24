package com.mawai.wiibservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibcommon.dto.*;
import com.mawai.wiibcommon.entity.BlackjackAccount;
import com.mawai.wiibcommon.entity.BlackjackConvertLog;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.BlackjackAccountMapper;
import com.mawai.wiibservice.mapper.BlackjackConvertLogMapper;
import com.mawai.wiibservice.service.BlackjackService;
import com.mawai.wiibservice.service.CacheService;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.util.GameLockExecutor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Blackjack 服务实现。
 * <p>
 * 核心设计原则：
 * 1) DB 管资金与统计，Redis 管牌局过程态；
 * 2) 同一用户所有 Blackjack 请求串行化，避免并发错乱；
 * 3) 庄家回合使用硬规则：&lt;17 必须 HIT，&gt;=17 必须 STAND。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlackjackServiceImpl implements BlackjackService {

    private final BlackjackAccountMapper accountMapper;
    private final BlackjackConvertLogMapper convertLogMapper;
    private final CacheService cacheService;
    private final UserService userService;
    private final GameLockExecutor gameLock;

    /** 用户初始积分，同时也是每日保底重置目标值。 */
    private static final long INITIAL_CHIPS = 20_000L;
    /** 每日最多可从 Blackjack 转出的积分上限。 */
    private static final long DAILY_CONVERT_LIMIT = 20_000L;
    /** 单次下注最小积分。 */
    private static final long MIN_BET = 100L;
    /** 单次下注最大积分。 */
    private static final long MAX_BET = 10_000L;
    /** 一局牌靴使用的副数（当前为单副牌）。 */
    private static final int SHOE_DECKS = 1;
    private static final Random RANDOM = new SecureRandom();

    /** 每日积分池总额度。 */
    private static final long DAILY_POOL = 400_000L;
    /** 积分池 Redis 键前缀。 */
    private static final String POOL_KEY_PREFIX = "bj:pool:";

    private static final String SK = "bj:session:";
    private static final String LK = "blackjack:user:";
    private static final long SESSION_TTL_HOURS = 4;

    /** 牌面点数字符集合（T 表示 10）。 */
    private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K"};
    /** 花色字符集合：红桃/方块/梅花/黑桃。 */
    private static final String[] SUITS = {"H", "D", "C", "S"};
    /** 庄家暗牌在前端可见态中的占位符。 */
    private static final String HIDDEN_CARD = "??";

    /** 牌局阶段：玩家操作中。 */
    private static final String PHASE_PLAYER_TURN = "PLAYER_TURN";
    /** 牌局阶段：结算完成。 */
    private static final String PHASE_SETTLED = "SETTLED";

    /** 可用动作：下注。 */
    private static final String ACTION_BET = "BET";
    /** 可用动作：要牌。 */
    private static final String ACTION_HIT = "HIT";
    /** 可用动作：停牌。 */
    private static final String ACTION_STAND = "STAND";
    /** 可用动作：加倍。 */
    private static final String ACTION_DOUBLE = "DOUBLE";
    /** 可用动作：分牌。 */
    private static final String ACTION_SPLIT = "SPLIT";
    /** 可用动作：买保险。 */
    private static final String ACTION_INSURANCE = "INSURANCE";

    /** 保险只允许在首回合（仍是两张牌）窗口内购买。 */
    private static final int INSURANCE_MAX_CARD_COUNT = 2;

    // ==================== 会话数据结构 ====================

    /**
     * 一局 Blackjack 会话快照（存 Redis）。
     */
    @Data
    public static class BlackjackSession implements java.io.Serializable {
        /** 当前牌靴（已洗牌），按顺序发牌。 */
        private List<String> shoe;
        /** 下一张待发牌在牌靴中的索引位置。 */
        private int shoeIndex;
        /** 玩家所有手牌（分牌后可能多手）。 */
        private List<SessionHand> playerHands;
        /** 当前轮到操作的手牌下标。 */
        private int activeHandIndex;
        /** 庄家手牌（服务端完整牌面）。 */
        private List<String> dealerCards;
        /** 开局每手基础下注额（用于分牌/保险计算）。 */
        private long betPerHand;
        /** 是否已购买保险。 */
        private boolean insuranceTaken;
        /** 保险下注金额。 */
        private long insuranceBet;
        /** 当前牌局阶段（PLAYER_TURN / SETTLED）。 */
        private String phase;

        /** 是否仍处于首决策窗口（用于约束保险购买时机）。 */
        private boolean firstDecisionRound;

        /** 开局判定庄家是否自然 Blackjack（服务端隐藏态）。 */
        private boolean dealerHasNaturalBlackjack;

        /** 开局判定玩家是否自然 Blackjack。 */
        private boolean playerHasNaturalBlackjack;
    }

    @Data
    public static class SessionHand implements java.io.Serializable {
        /** 该手的牌面列表。 */
        private List<String> cards;
        /** 该手当前下注额（加倍后会翻倍）。 */
        private long bet;
        /** 该手是否已停牌。 */
        private boolean stood;
        /** 该手是否已爆牌。 */
        private boolean busted;
        /** 该手是否执行过加倍。 */
        private boolean isDoubled;
    }

    // ==================== 公开接口 ====================

    @Override
    public BlackjackStatusDTO getStatus(Long userId) {
        return gameLock.executeInLock(LK, userId, () -> {
            BlackjackAccount account = getOrCreateAccount(userId);

            // 仅在无活动牌局时执行每日重置，避免跨天中途套利。
            if (getSession(userId) == null) {
                checkDailyReset(account);
            }

            BlackjackStatusDTO dto = new BlackjackStatusDTO();
            dto.setChips(account.getChips());
            dto.setTodayConverted(getTodayConverted(account));
            dto.setConvertable(Math.max(0, account.getChips() - INITIAL_CHIPS));
            dto.setTodayConvertLimit(DAILY_CONVERT_LIMIT);
            dto.setTotalHands(account.getTotalHands());
            dto.setTotalWon(account.getTotalWon());
            dto.setTotalLost(account.getTotalLost());
            dto.setBiggestWin(account.getBiggestWin());
            dto.setDailyPool(getPoolRemaining());

            BlackjackSession session = getSession(userId);
            if (session != null) {
                dto.setActiveGame(buildGameState(session, account.getChips()));
            }
            return dto;
        });
    }

    @Override
    public GameStateDTO bet(Long userId, long amount) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            validateBetAmount(amount);

            if (getSession(userId) != null) {
                throw new BizException(ErrorCode.BJ_GAME_IN_PROGRESS);
            }

            if (getPoolRemaining() <= 0) {
                throw new BizException(ErrorCode.BJ_POOL_EXHAUSTED);
            }

            BlackjackAccount account = getOrCreateAccount(userId);
            checkDailyReset(account);

            if (account.getChips() < amount) {
                throw new BizException(ErrorCode.BJ_CHIPS_NOT_ENOUGH);
            }

            account.setChips(account.getChips() - amount);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);

            BlackjackSession session = new BlackjackSession();
            session.setShoe(createShoe());
            session.setShoeIndex(0);
            session.setBetPerHand(amount);
            session.setPhase(PHASE_PLAYER_TURN);
            session.setInsuranceTaken(false);
            session.setInsuranceBet(0L);
            session.setFirstDecisionRound(true);

            session.setDealerCards(new ArrayList<>());
            SessionHand playerHand = createNewHand(amount);
            session.setPlayerHands(new ArrayList<>(List.of(playerHand)));
            session.setActiveHandIndex(0);

            // 发牌顺序：玩家-庄家-玩家-庄家。
            dealCard(session, playerHand.getCards());
            dealCard(session, session.getDealerCards());
            dealCard(session, playerHand.getCards());
            dealCard(session, session.getDealerCards());

            boolean playerBJ = isBlackjack(playerHand.getCards());
            boolean dealerBJ = isBlackjack(session.getDealerCards());
            session.setPlayerHasNaturalBlackjack(playerBJ);
            session.setDealerHasNaturalBlackjack(dealerBJ);

            if (playerBJ && dealerBJ) {
                session.setPhase(PHASE_SETTLED);
                account.setChips(account.getChips() + amount);
                account.setTotalHands(account.getTotalHands() + 1);
                account.setUpdatedAt(LocalDateTime.now());
                accountMapper.updateById(account);

                GameStateDTO state = buildSettledState(
                        session,
                        account.getChips(),
                        List.of(makeResult(0, "PUSH", amount, 0))
                );
                deleteSession(userId);
                return state;
            }

            if (playerBJ) {
                String dealerUpCard = session.getDealerCards().get(1);
                if (cardRank(dealerUpCard).equals("A")) {
                    // 玩家自然BJ且庄家明牌A：只允许买保险/停牌，不可继续普通动作。
                    saveSession(userId, session);
                    return buildGameState(session, account.getChips());
                }

                session.setPhase(PHASE_SETTLED);
                long payout = amount + amount * 3 / 2;
                long net = amount * 3 / 2;

                long granted = adjustPoolCapped(net);
                if (granted < net) {
                    long cut = net - granted;
                    payout -= cut;
                    net = granted;
                }

                account.setChips(account.getChips() + payout);
                account.setTotalHands(account.getTotalHands() + 1);
                if (net > 0) {
                    account.setTotalWon(account.getTotalWon() + net);
                    account.setBiggestWin(Math.max(account.getBiggestWin(), net));
                }
                account.setUpdatedAt(LocalDateTime.now());
                accountMapper.updateById(account);

                GameStateDTO state = buildSettledState(
                        session,
                        account.getChips(),
                        List.of(makeResult(0, "BLACKJACK", payout, net))
                );
                deleteSession(userId);
                return state;
            }

            if (dealerBJ && !"A".equals(cardRank(session.getDealerCards().get(1)))) {
                // 庄家自然BJ且明牌非A：无保险窗口，直接结算。
                session.setPhase(PHASE_SETTLED);
                long bet = playerHand.getBet();

                account.setTotalHands(account.getTotalHands() + 1);
                account.setTotalLost(account.getTotalLost() + bet);
                account.setUpdatedAt(LocalDateTime.now());
                accountMapper.updateById(account);

                adjustPoolLoss(-bet);
                GameStateDTO state = buildSettledState(
                        session,
                        account.getChips(),
                        List.of(makeResult(0, "LOSE", 0, -bet))
                );
                deleteSession(userId);
                return state;
            }

            saveSession(userId, session);
            return buildGameState(session, account.getChips());
        });
    }

    @Override
    public GameStateDTO hit(Long userId) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            BlackjackSession session = requireSession(userId);
            requirePhase(session);

            SessionHand hand = currentActiveHand(session);
            ensureHandCanAct(hand);

            session.setFirstDecisionRound(false);

            dealCard(session, hand.getCards());
            int score = bestScore(hand.getCards());
            if (score > 21) {
                hand.setBusted(true);
                return advanceOrSettle(userId, session);
            }
            if (score == 21) {
                hand.setStood(true);
                return advanceOrSettle(userId, session);
            }

            saveSession(userId, session);
            BlackjackAccount account = getAccount(userId);
            return buildGameState(session, account.getChips());
        });
    }

    @Override
    public GameStateDTO stand(Long userId) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            BlackjackSession session = requireSession(userId);
            requirePhase(session);

            SessionHand hand = currentActiveHand(session);
            ensureHandCanStand(hand);

            session.setFirstDecisionRound(false);
            hand.setStood(true);
            return advanceOrSettle(userId, session);
        });
    }

    @Override
    public GameStateDTO doubleDown(Long userId) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            BlackjackSession session = requireSession(userId);
            requirePhase(session);

            SessionHand hand = currentActiveHand(session);
            if (hand.isStood() || hand.isBusted() || hand.getCards().size() != 2 || hand.isDoubled()) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }
            if (isBlackjack(hand.getCards())) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }

            BlackjackAccount account = getAccount(userId);
            long extraBet = hand.getBet();
            if (account.getChips() < extraBet) {
                throw new BizException(ErrorCode.BJ_CHIPS_NOT_ENOUGH);
            }

            account.setChips(account.getChips() - extraBet);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);

            hand.setBet(hand.getBet() + extraBet);
            hand.setDoubled(true);
            session.setFirstDecisionRound(false);

            dealCard(session, hand.getCards());
            int score = bestScore(hand.getCards());
            if (score > 21) {
                hand.setBusted(true);
            } else {
                hand.setStood(true);
            }

            return advanceOrSettle(userId, session);
        });
    }

    @Override
    public GameStateDTO split(Long userId) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            BlackjackSession session = requireSession(userId);
            requirePhase(session);

            SessionHand hand = currentActiveHand(session);
            if (hand.isStood() || hand.isBusted() || hand.getCards().size() != 2) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }
            if (isBlackjack(hand.getCards())) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }
            if (!canSplit(hand.getCards())) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }

            BlackjackAccount account = getAccount(userId);
            long extraBet = session.getBetPerHand();
            if (account.getChips() < extraBet) {
                throw new BizException(ErrorCode.BJ_CHIPS_NOT_ENOUGH);
            }

            account.setChips(account.getChips() - extraBet);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);

            String card1 = hand.getCards().get(0);
            String card2 = hand.getCards().get(1);

            hand.setCards(new ArrayList<>(List.of(card1)));

            SessionHand hand2 = createNewHand(extraBet);
            hand2.setCards(new ArrayList<>(List.of(card2)));

            int idx = session.getActiveHandIndex();
            session.getPlayerHands().add(idx + 1, hand2);

            // 分牌属于首轮动作，一旦执行，保险窗口关闭。
            session.setFirstDecisionRound(false);

            dealCard(session, hand.getCards());
            dealCard(session, hand2.getCards());

            saveSession(userId, session);
            return buildGameState(session, account.getChips());
        });
    }

    @Override
    public GameStateDTO insurance(Long userId) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            BlackjackSession session = requireSession(userId);
            requirePhase(session);

            if (session.isInsuranceTaken()) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }
            if (!session.isFirstDecisionRound()) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }

            String dealerUpCard = session.getDealerCards().get(1);
            if (!cardRank(dealerUpCard).equals("A")) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }

            SessionHand active = currentActiveHand(session);
            if (active.getCards().size() > INSURANCE_MAX_CARD_COUNT) {
                throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
            }

            BlackjackAccount account = getAccount(userId);
            long insuranceCost = session.getBetPerHand() / 2;
            if (account.getChips() < insuranceCost) {
                throw new BizException(ErrorCode.BJ_CHIPS_NOT_ENOUGH);
            }

            account.setChips(account.getChips() - insuranceCost);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);

            session.setInsuranceTaken(true);
            session.setInsuranceBet(insuranceCost);

            // 若庄家实际已是自然BJ，买完保险后可直接结算。
            if (session.isDealerHasNaturalBlackjack()) {
                return dealerTurnAndSettle(userId, session);
            }

            saveSession(userId, session);
            return buildGameState(session, account.getChips());
        });
    }

    @Override
    public GameStateDTO forfeit(Long userId) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            BlackjackSession session = getSession(userId);
            if (session == null) {
                throw new BizException(ErrorCode.BJ_NO_ACTIVE_GAME);
            }

            BlackjackAccount account = getAccount(userId);

            long totalBet = session.getPlayerHands().stream().mapToLong(SessionHand::getBet).sum()
                    + session.getInsuranceBet();
            account.setTotalHands(account.getTotalHands() + 1);
            account.setTotalLost(account.getTotalLost() + totalBet);
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);

            adjustPoolLoss(-totalBet);
            deleteSession(userId);

            GameStateDTO state = new GameStateDTO();
            state.setPhase(PHASE_SETTLED);
            state.setChips(account.getChips());
            state.setActions(List.of(ACTION_BET));
            state.setPlayerHands(List.of());
            state.setDealerCards(List.of());
            state.setResults(List.of());
            state.setDealerScore(null);
            state.setInsurance(session.isInsuranceTaken() ? session.getInsuranceBet() : null);
            state.setActiveHandIndex(0);
            return state;
        });
    }

    @Override
    public ConvertResultDTO convert(Long userId, long amount) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            if (amount <= 0) {
                throw new BizException(ErrorCode.PARAM_ERROR);
            }
            if (getSession(userId) != null) {
                throw new BizException(ErrorCode.BJ_GAME_IN_PROGRESS);
            }

            BlackjackAccount account = getOrCreateAccount(userId);
            checkDailyReset(account);

            long convertable = Math.max(0, account.getChips() - INITIAL_CHIPS);
            if (amount > convertable) {
                throw new BizException(ErrorCode.BJ_CONVERT_INSUFFICIENT);
            }

            long todayConverted = getTodayConverted(account);
            if (todayConverted + amount > DAILY_CONVERT_LIMIT) {
                throw new BizException(ErrorCode.BJ_CONVERT_LIMIT);
            }

            long chipsBefore = account.getChips();
            BigDecimal balanceBefore = userService.getById(userId).getBalance();

            account.setChips(account.getChips() - amount);
            account.setTodayConverted(todayConverted + amount);
            account.setLastConvertDate(LocalDate.now());
            account.setUpdatedAt(LocalDateTime.now());
            accountMapper.updateById(account);

            userService.updateBalance(userId, BigDecimal.valueOf(amount));

            BlackjackConvertLog logEntry = new BlackjackConvertLog();
            logEntry.setUserId(userId);
            logEntry.setAmount(amount);
            logEntry.setChipsBefore(chipsBefore);
            logEntry.setChipsAfter(account.getChips());
            logEntry.setBalanceBefore(balanceBefore);
            logEntry.setBalanceAfter(balanceBefore.add(BigDecimal.valueOf(amount)));
            logEntry.setCreatedAt(LocalDateTime.now());
            convertLogMapper.insert(logEntry);

            ConvertResultDTO result = new ConvertResultDTO();
            result.setChips(account.getChips());
            result.setBalance(userService.getById(userId).getBalance().doubleValue());
            result.setTodayConverted(account.getTodayConverted());
            return result;
        });
    }

    // ==================== 游戏引擎内部 ====================

    // 当前手结束后：还有未行动的手则切过去，否则进庄家回合
    private GameStateDTO advanceOrSettle(Long userId, BlackjackSession session) {
        int nextActive = findNextActiveHand(session);
        if (nextActive >= 0) {
            session.setActiveHandIndex(nextActive);
            saveSession(userId, session);
            BlackjackAccount account = getAccount(userId);
            return buildGameState(session, account.getChips());
        }
        return dealerTurnAndSettle(userId, session);
    }

    private int findNextActiveHand(BlackjackSession session) {
        for (int i = session.getActiveHandIndex() + 1; i < session.getPlayerHands().size(); i++) {
            SessionHand hand = session.getPlayerHands().get(i);
            if (!hand.isStood() && !hand.isBusted()) {
                return i;
            }
        }
        return -1;
    }

    private GameStateDTO dealerTurnAndSettle(Long userId, BlackjackSession session) {
        boolean allBusted = session.getPlayerHands().stream().allMatch(SessionHand::isBusted);

        // 玩家全爆则庄家不用补牌
        if (!allBusted) {
            runDealerTurn(session);
        }

        session.setPhase(PHASE_SETTLED);

        int dealerScore = bestScore(session.getDealerCards());
        boolean dealerBust = dealerScore > 21;
        boolean dealerBJ = isBlackjack(session.getDealerCards());

        BlackjackAccount account = getAccount(userId);
        List<HandResultDTO> results = new ArrayList<>();
        long totalPayout = 0;
        long totalNet = 0;

        // 结算优先级：爆牌 > 自然BJ > 庄爆 > 比点数
        for (int i = 0; i < session.getPlayerHands().size(); i++) {
            SessionHand hand = session.getPlayerHands().get(i);
            int playerScore = bestScore(hand.getCards());
            boolean playerBJ = isBlackjack(hand.getCards());

            String resultType;
            long payout;
            long net;

            if (hand.isBusted()) {
                resultType = "LOSE";
                payout = 0;
                net = -hand.getBet();
            } else if (playerBJ) {
                if (dealerBJ) {
                    resultType = "PUSH";
                    payout = hand.getBet();
                    net = 0;
                } else {
                    resultType = "BLACKJACK";
                    payout = hand.getBet() + hand.getBet() * 3 / 2;
                    net = hand.getBet() * 3 / 2;
                }
            } else if (dealerBust) {
                resultType = "WIN";
                payout = hand.getBet() * 2;
                net = hand.getBet();
            } else if (playerScore > dealerScore) {
                resultType = "WIN";
                payout = hand.getBet() * 2;
                net = hand.getBet();
            } else if (playerScore == dealerScore) {
                resultType = "PUSH";
                payout = hand.getBet();
                net = 0;
            } else {
                resultType = "LOSE";
                payout = 0;
                net = -hand.getBet();
            }

            totalPayout += payout;
            totalNet += net;
            results.add(makeResult(i, resultType, payout, net));
        }

        // 保险结算：庄家BJ赔3倍，否则没收
        if (session.isInsuranceTaken()) {
            if (dealerBJ) {
                long insurancePayout = session.getInsuranceBet() * 3;
                totalPayout += insurancePayout;
                totalNet += session.getInsuranceBet() * 2;
            } else {
                totalNet -= session.getInsuranceBet();
            }
        }

        // 积分池封顶：用户赢时只赔池子剩余的部分
        if (totalNet > 0) {
            long granted = adjustPoolCapped(totalNet);
            if (granted < totalNet) {
                long cut = totalNet - granted;
                totalPayout -= cut;
                applyPoolCapToResults(results, cut);
                totalNet = granted;
            }
        } else {
            adjustPoolLoss(totalNet);
        }

        account.setChips(account.getChips() + totalPayout);
        account.setTotalHands(account.getTotalHands() + 1);
        if (totalNet > 0) {
            account.setTotalWon(account.getTotalWon() + totalNet);
            account.setBiggestWin(Math.max(account.getBiggestWin(), totalNet));
        } else if (totalNet < 0) {
            account.setTotalLost(account.getTotalLost() + Math.abs(totalNet));
        }
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);

        GameStateDTO state = buildSettledState(session, account.getChips(), results);
        deleteSession(userId);
        return state;
    }

    /** 庄家硬规则：&lt;17 HIT，&gt;=17 STAND。 */
    private void runDealerTurn(BlackjackSession session) {
        while (bestScore(session.getDealerCards()) < 17) {
            dealCard(session, session.getDealerCards());
        }
    }

    // ==================== 牌组操作 ====================

    private List<String> createShoe() {
        List<String> shoe = new ArrayList<>(SHOE_DECKS * 52);
        for (int deck = 0; deck < SHOE_DECKS; deck++) {
            for (String rank : RANKS) {
                for (String suit : SUITS) {
                    shoe.add(rank + suit);
                }
            }
        }
        Collections.shuffle(shoe, RANDOM);
        return shoe;
    }

    private void dealCard(BlackjackSession session, List<String> target) {
        target.add(session.getShoe().get(session.getShoeIndex()));
        session.setShoeIndex(session.getShoeIndex() + 1);
    }

    // ==================== 点数计算 ====================

    private static int cardValue(String card) {
        String rank = cardRank(card);
        return switch (rank) {
            case "A" -> 11;
            case "T", "J", "Q", "K" -> 10;
            default -> Integer.parseInt(rank);
        };
    }

    private static String cardRank(String card) {
        return card.substring(0, 1);
    }

    // A先按11算，爆了再逐张降为1
    static int bestScore(List<String> cards) {
        int total = 0;
        int aces = 0;
        for (String card : cards) {
            int value = cardValue(card);
            total += value;
            if (value == 11) {
                aces++;
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        return total;
    }

    private static boolean isBlackjack(List<String> cards) {
        return cards.size() == 2 && bestScore(cards) == 21;
    }

    private static boolean canSplit(List<String> cards) {
        if (cards.size() != 2) {
            return false;
        }
        return cardValue(cards.get(0)) == cardValue(cards.get(1));
    }

    // ==================== 会话管理（委托 GameLockExecutor） ====================

    private BlackjackSession getSession(Long userId) {
        return gameLock.getSession(SK, userId);
    }

    private BlackjackSession requireSession(Long userId) {
        return gameLock.requireSession(SK, userId, ErrorCode.BJ_NO_ACTIVE_GAME);
    }

    private void saveSession(Long userId, BlackjackSession session) {
        gameLock.saveSession(SK, userId, session, SESSION_TTL_HOURS);
    }

    private void deleteSession(Long userId) {
        gameLock.deleteSession(SK, userId);
    }

    private void requirePhase(BlackjackSession session) {
        if (!BlackjackServiceImpl.PHASE_PLAYER_TURN.equals(session.getPhase())) {
            throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
        }
    }

    // ==================== 账户管理 ====================

    private BlackjackAccount getAccount(Long userId) {
        BlackjackAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<BlackjackAccount>().eq(BlackjackAccount::getUserId, userId)
        );
        if (account == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return account;
    }

    private BlackjackAccount getOrCreateAccount(Long userId) {
        BlackjackAccount account = accountMapper.selectOne(
                new LambdaQueryWrapper<BlackjackAccount>().eq(BlackjackAccount::getUserId, userId)
        );
        if (account != null) {
            return account;
        }

        BlackjackAccount newAccount = new BlackjackAccount();
        newAccount.setUserId(userId);
        newAccount.setChips(INITIAL_CHIPS);
        newAccount.setTodayConverted(0L);
        newAccount.setTotalHands(0L);
        newAccount.setTotalWon(0L);
        newAccount.setTotalLost(0L);
        newAccount.setBiggestWin(0L);
        newAccount.setCreatedAt(LocalDateTime.now());
        newAccount.setUpdatedAt(LocalDateTime.now());

        try {
            accountMapper.insert(newAccount);
            return newAccount;
        } catch (DuplicateKeyException ex) {
            log.warn("创建Blackjack账户发生唯一键冲突，回查账户: userId={}, error={}", userId, ex.getMessage());
            BlackjackAccount existed = accountMapper.selectOne(
                    new LambdaQueryWrapper<BlackjackAccount>().eq(BlackjackAccount::getUserId, userId)
            );
            if (existed != null) {
                return existed;
            }
            throw ex;
        }
    }

    private void checkDailyReset(BlackjackAccount account) {
        LocalDate today = LocalDate.now();
        if (account.getLastResetDate() != null && account.getLastResetDate().equals(today)) {
            return;
        }
        boolean needReset = account.getChips() < INITIAL_CHIPS;
        if (needReset) {
            account.setChips(INITIAL_CHIPS);
            log.info("用户{}积分每日重置为{}", account.getUserId(), INITIAL_CHIPS);
        }
        account.setLastResetDate(today);
        account.setUpdatedAt(LocalDateTime.now());
        accountMapper.updateById(account);
    }

    private long getTodayConverted(BlackjackAccount account) {
        LocalDate today = LocalDate.now();
        if (account.getLastConvertDate() == null || !account.getLastConvertDate().equals(today)) {
            return 0;
        }
        return account.getTodayConverted();
    }

    // ==================== DTO 构建 ====================

    // 玩家操作阶段隐藏庄家暗牌
    private GameStateDTO buildGameState(BlackjackSession session, long chips) {
        GameStateDTO dto = new GameStateDTO();
        dto.setPhase(session.getPhase());
        dto.setActiveHandIndex(session.getActiveHandIndex());
        dto.setChips(chips);
        dto.setInsurance(session.isInsuranceTaken() ? session.getInsuranceBet() : null);

        dto.setPlayerHands(session.getPlayerHands().stream().map(hand -> {
            HandDTO hd = new HandDTO();
            hd.setCards(hand.getCards());
            hd.setBet(hand.getBet());
            hd.setScore(bestScore(hand.getCards()));
            hd.setBust(bestScore(hand.getCards()) > 21);
            hd.setBlackjack(isBlackjack(hand.getCards()));
            hd.setDoubled(hand.isDoubled());
            return hd;
        }).toList());

        if (PHASE_PLAYER_TURN.equals(session.getPhase())) {
            dto.setDealerCards(List.of(HIDDEN_CARD, session.getDealerCards().get(1)));
            dto.setDealerScore(null);
        } else {
            dto.setDealerCards(session.getDealerCards());
            dto.setDealerScore(bestScore(session.getDealerCards()));
        }

        dto.setActions(computeActions(session));
        dto.setResults(null);
        return dto;
    }

    private GameStateDTO buildSettledState(BlackjackSession session, long chips, List<HandResultDTO> results) {
        GameStateDTO dto = new GameStateDTO();
        dto.setPhase(PHASE_SETTLED);
        dto.setActiveHandIndex(0);
        dto.setChips(chips);
        dto.setInsurance(session.isInsuranceTaken() ? session.getInsuranceBet() : null);

        dto.setPlayerHands(session.getPlayerHands().stream().map(hand -> {
            HandDTO hd = new HandDTO();
            hd.setCards(hand.getCards());
            hd.setBet(hand.getBet());
            hd.setScore(bestScore(hand.getCards()));
            hd.setBust(bestScore(hand.getCards()) > 21);
            hd.setBlackjack(isBlackjack(hand.getCards()));
            hd.setDoubled(hand.isDoubled());
            return hd;
        }).toList());

        dto.setDealerCards(session.getDealerCards());
        dto.setDealerScore(bestScore(session.getDealerCards()));
        dto.setActions(List.of(ACTION_BET));
        dto.setResults(results);
        return dto;
    }

    private List<String> computeActions(BlackjackSession session) {
        if (PHASE_SETTLED.equals(session.getPhase())) {
            return List.of(ACTION_BET);
        }

        SessionHand hand = session.getPlayerHands().get(session.getActiveHandIndex());
        if (hand.isStood() || hand.isBusted()) {
            return List.of();
        }

        if (isBlackjack(hand.getCards())) {
            if (canBuyInsurance(session)) {
                return List.of(ACTION_STAND, ACTION_INSURANCE);
            }
            return List.of(ACTION_STAND);
        }

        List<String> actions = new ArrayList<>();
        actions.add(ACTION_HIT);
        actions.add(ACTION_STAND);

        if (hand.getCards().size() == 2 && !hand.isDoubled()) {
            actions.add(ACTION_DOUBLE);
            if (canSplit(hand.getCards())) {
                actions.add(ACTION_SPLIT);
            }
        }

        if (canBuyInsurance(session)) {
            actions.add(ACTION_INSURANCE);
        }
        return actions;
    }

    private boolean canBuyInsurance(BlackjackSession session) {
        if (session.isInsuranceTaken()) {
            return false;
        }
        if (!session.isFirstDecisionRound()) {
            return false;
        }
        String dealerUpCard = session.getDealerCards().get(1);
        if (!"A".equals(cardRank(dealerUpCard))) {
            return false;
        }
        SessionHand hand = session.getPlayerHands().get(session.getActiveHandIndex());
        return hand.getCards().size() <= INSURANCE_MAX_CARD_COUNT;
    }

    private HandResultDTO makeResult(int handIndex, String result, long payout, long net) {
        HandResultDTO dto = new HandResultDTO();
        dto.setHandIndex(handIndex);
        dto.setResult(result);
        dto.setPayout(payout);
        dto.setNet(net);
        return dto;
    }

    private void applyPoolCapToResults(List<HandResultDTO> results, long cut) {
        long positiveNet = results.stream()
                .filter(r -> r.getNet() > 0)
                .mapToLong(HandResultDTO::getNet)
                .sum();
        if (cut <= 0 || positiveNet <= 0) return;

        long remainingCut = cut;
        for (HandResultDTO result : results) {
            if (result.getNet() <= 0) continue;
            long handCut = Math.min(remainingCut,
                    cut * result.getNet() / positiveNet);
            remainingCut -= handCut;
            result.setPayout(result.getPayout() - handCut);
            result.setNet(result.getNet() - handCut);
        }
        for (HandResultDTO result : results) {
            if (remainingCut <= 0) break;
            if (result.getNet() <= 0) continue;
            result.setPayout(result.getPayout() - 1);
            result.setNet(result.getNet() - 1);
            remainingCut--;
        }
    }

    // ==================== 规则与工具 ====================

    private void validateBetAmount(long amount) {
        if (amount < MIN_BET || amount > MAX_BET) {
            throw new BizException(ErrorCode.BJ_INVALID_BET);
        }
    }

    private SessionHand createNewHand(long bet) {
        SessionHand hand = new SessionHand();
        hand.setCards(new ArrayList<>());
        hand.setBet(bet);
        hand.setStood(false);
        hand.setBusted(false);
        hand.setDoubled(false);
        return hand;
    }

    private SessionHand currentActiveHand(BlackjackSession session) {
        return session.getPlayerHands().get(session.getActiveHandIndex());
    }

    /**
     * 校验当前手牌是否允许继续动作。
     */
    private void ensureHandCanAct(SessionHand hand) {
        if (hand.isStood() || hand.isBusted()) {
            throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
        }
        if (isBlackjack(hand.getCards())) {
            throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
        }
    }

    /**
     * 校验当前手牌是否允许停牌。
     * 停牌允许用于自然 Blackjack 手（例如分牌后 A+10）。
     */
    private void ensureHandCanStand(SessionHand hand) {
        if (hand.isStood() || hand.isBusted()) {
            throw new BizException(ErrorCode.BJ_ACTION_NOT_ALLOWED);
        }
    }

    // ==================== 每日积分池 ====================

    private String dailyPoolKey() {
        return POOL_KEY_PREFIX + LocalDate.now();
    }

    private void ensurePoolKey() {
        cacheService.setIfAbsent(dailyPoolKey(), String.valueOf(DAILY_POOL), 24, TimeUnit.HOURS);
    }

    private long getPoolRemaining() {
        ensurePoolKey();
        String val = cacheService.get(dailyPoolKey());
        return Long.parseLong(val);
    }

    /**
     * 原子扣减积分池，不允许低于0。
     * @param userNet 用户净收益（正=用户赢，负=用户输）
     * @return 实际从池中扣出的量（0 ~ userNet），用于封顶 payout
     */
    private long adjustPoolCapped(long userNet) {
        if (userNet <= 0) {
            // 用户输了，池子增加
            if (userNet < 0) {
                ensurePoolKey();
                cacheService.increment(dailyPoolKey(), -userNet);
            }
            return 0;
        }
        // 用户赢了，原子扣减，不低于0
        ensurePoolKey();
        return cacheService.decrementWithFloor(dailyPoolKey(), userNet, 0);
    }

    /** 用户输钱时简单回填池子（无需封顶） */
    private void adjustPoolLoss(long userNet) {
        if (userNet >= 0) return;
        ensurePoolKey();
        cacheService.increment(dailyPoolKey(), -userNet);
    }
}
