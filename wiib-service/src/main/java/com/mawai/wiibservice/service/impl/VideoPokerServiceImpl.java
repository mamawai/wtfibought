package com.mawai.wiibservice.service.impl;

import com.mawai.wiibcommon.dto.VideoPokerGameStateDTO;
import com.mawai.wiibcommon.dto.VideoPokerStatusDTO;
import com.mawai.wiibcommon.entity.VideoPokerGame;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.mapper.VideoPokerGameMapper;
import com.mawai.wiibservice.service.UserService;
import com.mawai.wiibservice.service.VideoPokerService;
import com.mawai.wiibservice.util.GameLockExecutor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoPokerServiceImpl implements VideoPokerService {

    private final VideoPokerGameMapper videoPokerGameMapper;
    private final UserService userService;
    private final GameLockExecutor gameLock;

    private static final BigDecimal MIN_BET = new BigDecimal("100");
    private static final BigDecimal MAX_BET = new BigDecimal("50000");

    private static final String SK = "vp:session:";
    private static final String LK = "videopoker:user:";
    private static final long SESSION_TTL = 2;

    private static final String PHASE_DEALING = "DEALING";
    private static final String PHASE_SETTLED = "SETTLED";

    private static final String[] SUITS = {"H", "D", "C", "S"};
    private static final String[] RANKS = {"2", "3", "4", "5", "6", "7", "8", "9", "T", "J", "Q", "K", "A"};
    private static final Set<Integer> ROYAL_RANKS = Set.of(10, 11, 12, 13, 14);

    private static final Random RANDOM = new SecureRandom();

    private static final LinkedHashMap<String, BigDecimal> PAYOUTS = new LinkedHashMap<>();
    static {
        PAYOUTS.put("Natural Royal Flush", new BigDecimal("800"));
        PAYOUTS.put("Joker Royal Flush", new BigDecimal("100"));
        PAYOUTS.put("Five of a Kind", new BigDecimal("50"));
        PAYOUTS.put("Straight Flush", new BigDecimal("50"));
        PAYOUTS.put("Four of a Kind", new BigDecimal("20"));
        PAYOUTS.put("Full House", new BigDecimal("7"));
        PAYOUTS.put("Flush", new BigDecimal("5"));
        PAYOUTS.put("Straight", new BigDecimal("3.5"));
        PAYOUTS.put("Three of a Kind", new BigDecimal("2.5"));
        PAYOUTS.put("Two Pair", new BigDecimal("1.5"));
        PAYOUTS.put("Jacks or Better", new BigDecimal("1"));
    }

    @Data
    public static class VPSession implements java.io.Serializable {
        private long gameId;
        private BigDecimal betAmount;
        private List<String> deck;
        private List<String> cards;
        private String phase;
    }

    // ==================== 公开接口 ====================

    @Override
    public VideoPokerStatusDTO getStatus(Long userId) {
        return gameLock.executeInLock(LK, userId, () -> {
            VideoPokerStatusDTO dto = new VideoPokerStatusDTO();
            dto.setBalance(userService.getUserPortfolio(userId).getBalance());
            VPSession session = gameLock.getSession(SK, userId);
            if (session != null) {
                dto.setActiveGame(buildDealingState(session, dto.getBalance()));
            }
            return dto;
        });
    }

    @Override
    public VideoPokerGameStateDTO bet(Long userId, BigDecimal amount) {
        return gameLock.executeInLockTx(LK, userId, () -> {
            if (amount == null || amount.compareTo(MIN_BET) < 0 || amount.compareTo(MAX_BET) > 0) {
                throw new BizException(ErrorCode.VP_INVALID_BET);
            }
            if (gameLock.getSession(SK, userId) != null) {
                throw new BizException(ErrorCode.VP_GAME_IN_PROGRESS);
            }
            BigDecimal balance = userService.getUserPortfolio(userId).getBalance();
            if (balance.compareTo(amount) < 0) {
                throw new BizException(ErrorCode.VP_BALANCE_NOT_ENOUGH);
            }

            userService.updateBalance(userId, amount.negate());

            List<String> deck = buildDeck();
            Collections.shuffle(deck, RANDOM);
            List<String> cards = new ArrayList<>(deck.subList(0, 5));

            VideoPokerGame game = new VideoPokerGame();
            game.setUserId(userId);
            game.setBetAmount(amount);
            game.setInitialCards(String.join(",", cards));
            game.setHeldPositions("");
            game.setFinalCards("");
            game.setHandRank("");
            game.setMultiplier(BigDecimal.ZERO);
            game.setPayout(BigDecimal.ZERO);
            game.setStatus(PHASE_DEALING);
            game.setCreatedAt(LocalDateTime.now());
            game.setUpdatedAt(LocalDateTime.now());
            videoPokerGameMapper.insert(game);

            VPSession session = new VPSession();
            session.setGameId(game.getId());
            session.setBetAmount(amount);
            session.setDeck(deck);
            session.setCards(cards);
            session.setPhase(PHASE_DEALING);
            gameLock.saveSession(SK, userId, session, SESSION_TTL);

            BigDecimal newBalance = userService.getUserPortfolio(userId).getBalance();
            return buildDealingState(session, newBalance);
        });
    }

    @Override
    public VideoPokerGameStateDTO draw(Long userId, List<Integer> held) {
        List<Integer> heldList = held != null ? held : Collections.emptyList();
        return gameLock.executeInLockTx(LK, userId, () -> {
            VPSession session = gameLock.requireSession(SK, userId, ErrorCode.VP_NO_ACTIVE_GAME);
            if (!PHASE_DEALING.equals(session.getPhase())) {
                throw new BizException(ErrorCode.VP_NO_ACTIVE_GAME);
            }

            Set<Integer> heldSet = new LinkedHashSet<>(heldList);
            if (heldSet.size() != heldList.size()) {
                throw new BizException(ErrorCode.VP_INVALID_HOLD);
            }
            for (int pos : heldSet) {
                if (pos < 0 || pos > 4) {
                    throw new BizException(ErrorCode.VP_INVALID_HOLD);
                }
            }

            List<String> finalCards = new ArrayList<>(session.getCards());
            int nextIdx = 5;
            for (int i = 0; i < 5; i++) {
                if (!heldSet.contains(i)) {
                    finalCards.set(i, session.getDeck().get(nextIdx++));
                }
            }

            String handRank = evaluateHand(finalCards);
            BigDecimal multiplier = PAYOUTS.getOrDefault(handRank, BigDecimal.ZERO);
            BigDecimal payout = session.getBetAmount().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);

            if (payout.compareTo(BigDecimal.ZERO) > 0) {
                userService.updateBalance(userId, payout);
            }

            VideoPokerGame game = videoPokerGameMapper.selectById(session.getGameId());
            game.setHeldPositions(heldSet.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
            game.setFinalCards(String.join(",", finalCards));
            game.setHandRank(handRank);
            game.setMultiplier(multiplier);
            game.setPayout(payout);
            game.setStatus(PHASE_SETTLED);
            game.setUpdatedAt(LocalDateTime.now());
            videoPokerGameMapper.updateById(game);

            gameLock.deleteSession(SK, userId);

            BigDecimal balance = userService.getUserPortfolio(userId).getBalance();

            VideoPokerGameStateDTO dto = new VideoPokerGameStateDTO();
            dto.setGameId(session.getGameId());
            dto.setBetAmount(session.getBetAmount());
            dto.setCards(finalCards);
            dto.setHeldPositions(new ArrayList<>(heldSet));
            dto.setHandRank(handRank);
            dto.setMultiplier(multiplier);
            dto.setPayout(payout);
            dto.setPhase(PHASE_SETTLED);
            dto.setBalance(balance);
            return dto;
        });
    }

    // ==================== 牌型评估 ====================

    static String evaluateHand(List<String> cards) {
        List<Integer> ranks = new ArrayList<>();
        List<String> suits = new ArrayList<>();
        int jokers = 0;

        for (String c : cards) {
            if (c.startsWith("JK")) {
                jokers++;
            } else {
                ranks.add(rankToValue(c.substring(0, 1)));
                suits.add(c.substring(1));
            }
        }

        Map<Integer, Integer> countMap = new HashMap<>();
        for (int r : ranks) countMap.merge(r, 1, Integer::sum);
        int maxCount = countMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        boolean isFlush = ranks.isEmpty() || suits.stream().distinct().count() == 1;
        boolean isStraight = canFormStraight(ranks, jokers);
        boolean allRoyal = ROYAL_RANKS.containsAll(ranks);
        boolean noDupRanks = ranks.stream().distinct().count() == ranks.size();

        if (jokers == 0 && isFlush && allRoyal && ranks.size() == 5) {
            return "Natural Royal Flush";
        }
        if (jokers > 0 && isFlush && allRoyal && noDupRanks) {
            return "Joker Royal Flush";
        }
        if (maxCount + jokers >= 5 && countMap.size() == 1) {
            return "Five of a Kind";
        }
        if (isFlush && isStraight) {
            return "Straight Flush";
        }
        if (maxCount + jokers >= 4) {
            return "Four of a Kind";
        }
        if (jokers == 0) {
            List<Integer> counts = new ArrayList<>(countMap.values());
            counts.sort(Collections.reverseOrder());
            if (counts.size() >= 2 && counts.get(0) == 3 && counts.get(1) == 2) {
                return "Full House";
            }
        } else if (jokers == 1) {
            long pairCount = countMap.values().stream().filter(c -> c == 2).count();
            if (pairCount == 2) {
                return "Full House";
            }
        }
        if (isFlush) {
            return "Flush";
        }
        if (isStraight) {
            return "Straight";
        }
        if (maxCount + jokers >= 3) {
            return "Three of a Kind";
        }
        if (jokers == 0) {
            long pairCount = countMap.values().stream().filter(c -> c >= 2).count();
            if (pairCount >= 2) {
                return "Two Pair";
            }
        }
        if (jokers == 0) {
            for (var e : countMap.entrySet()) {
                if (e.getValue() >= 2 && e.getKey() >= 11) {
                    return "Jacks or Better";
                }
            }
        } else if (jokers >= 1 && ranks.stream().anyMatch(r -> r >= 11)) {
            return "Jacks or Better";
        }

        return "No Win";
    }

    private static boolean canFormStraight(List<Integer> naturalRanks, int jokers) {
        for (int low = 1; low <= 10; low++) {
            int high = low + 4;
            boolean allFit = true;
            Set<Integer> covered = new HashSet<>();
            for (int r : naturalRanks) {
                int eff = (r == 14 && low == 1) ? 1 : r;
                if (eff < low || eff > high) { allFit = false; break; }
                covered.add(eff);
            }
            if (allFit && 5 - covered.size() <= jokers) {
                return true;
            }
        }
        return false;
    }

    private static int rankToValue(String rank) {
        return switch (rank) {
            case "A" -> 14;
            case "K" -> 13;
            case "Q" -> 12;
            case "J" -> 11;
            case "T" -> 10;
            default -> Integer.parseInt(rank);
        };
    }

    // ==================== 辅助 ====================

    private static List<String> buildDeck() {
        List<String> deck = new ArrayList<>(53);
        for (String s : SUITS) {
            for (String r : RANKS) {
                deck.add(r + s);
            }
        }
        deck.add("JK1");
        return deck;
    }

    private VideoPokerGameStateDTO buildDealingState(VPSession session, BigDecimal balance) {
        VideoPokerGameStateDTO dto = new VideoPokerGameStateDTO();
        dto.setGameId(session.getGameId());
        dto.setBetAmount(session.getBetAmount());
        dto.setCards(new ArrayList<>(session.getCards()));
        dto.setHeldPositions(Collections.emptyList());
        dto.setHandRank("");
        dto.setMultiplier(BigDecimal.ZERO);
        dto.setPayout(BigDecimal.ZERO);
        dto.setPhase(PHASE_DEALING);
        dto.setBalance(balance);
        return dto;
    }
}
