package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AccountState;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 地址池认证规则，纯函数（docs/hyperliquid-whale.md §4）。只按大小筛，不按盈亏筛。
 * <p>
 * 门 1 {@link #gate1}：持仓数 ≤ maxPositions，且（链上净值 ≥ minAccountValue 或 持有名义 ≥ minPositionValue 的盯盘币仓位）。
 * 净值小的高杠杆大仓位靠后一条进池；在池账户净值缩水但仓位还在，也靠它留下。
 * 门 2 + 入池 {@link #rank}：vault 不合格；其余按"有盯盘币大仓位的在前 → 净值降序"取前 cap 个进池，其余 OVER_CAP。
 */
public final class WhaleQualifier {

    /** 净值不够，也没有够大的盯盘币仓位 */
    public static final String SMALL = "SMALL";
    public static final String TOO_MANY_POSITIONS = "TOO_MANY_POSITIONS";
    public static final String VAULT = "VAULT";
    public static final String OVER_CAP = "OVER_CAP";

    /** 规则参数，来自 WhaleProperties */
    public record Rules(BigDecimal minAccountValue, int maxPositions, BigDecimal minPositionValue,
                        Set<String> coins, int cap) {
    }

    /** 一个待认证的交易账户：链上状态 + 库里已知的信息。role 为 null 表示主地址还没查过角色 */
    public record Candidate(String address, String parentAddress, String role, AccountState state) {
        public boolean isMaster() {
            return parentAddress == null;
        }

        public Candidate withRole(String newRole) {
            return new Candidate(address, parentAddress, newRole, state);
        }
    }

    /** 认证结论：rejectReason 为 null 即合格；inPool 只在 rank 之后有意义 */
    public record Verdict(Candidate candidate, BigDecimal trackedMaxPosition, String rejectReason, boolean inPool) {
        public boolean passed() {
            return rejectReason == null;
        }

        public boolean hasTrackedPosition(Rules rules) {
            return trackedMaxPosition.compareTo(rules.minPositionValue()) >= 0;
        }
    }

    private WhaleQualifier() {
    }

    /** 门 1：持仓数先过，再看净值或仓位 */
    public static Verdict gate1(Candidate c, Rules rules) {
        AccountState s = c.state();
        BigDecimal trackedMax = trackedMaxPosition(s, rules.coins());
        if (s.positions().size() > rules.maxPositions()) {
            return new Verdict(c, trackedMax, TOO_MANY_POSITIONS, false);
        }
        boolean bigAccount = s.accountValue().compareTo(rules.minAccountValue()) >= 0;
        boolean bigPosition = trackedMax.compareTo(rules.minPositionValue()) >= 0;
        if (!bigAccount && !bigPosition) {
            return new Verdict(c, trackedMax, SMALL, false);
        }
        return new Verdict(c, trackedMax, null, false);
    }

    /** 盯盘币里最大一笔仓位名义；没有回 0 */
    public static BigDecimal trackedMaxPosition(AccountState s, Set<String> coins) {
        BigDecimal max = BigDecimal.ZERO;
        for (Position p : s.positions()) {
            if (coins.contains(p.coin()) && p.positionValue().compareTo(max) > 0) {
                max = p.positionValue();
            }
        }
        return max;
    }

    public static boolean isVault(String role) {
        return "vault".equals(role);
    }

    /**
     * 门 2 + 入池排序 + cap。输入过了门 1 且 role 已补齐的结论，输出带最终 rejectReason 与 inPool 的结论。
     * 顺序：有盯盘币大仓位的在前，没仓位的排后面，组内净值降序。
     */
    public static List<Verdict> rank(List<Verdict> passedGate1, Rules rules) {
        List<Verdict> out = new ArrayList<>(passedGate1.size());
        List<Verdict> eligible = new ArrayList<>(passedGate1.size());
        for (Verdict v : passedGate1) {
            if (isVault(v.candidate().role())) {
                out.add(new Verdict(v.candidate(), v.trackedMaxPosition(), VAULT, false));
            } else {
                eligible.add(v);
            }
        }
        eligible.sort(Comparator.comparing((Verdict v) -> v.hasTrackedPosition(rules)).reversed()
                .thenComparing(v -> v.candidate().state().accountValue(), Comparator.reverseOrder()));
        for (int i = 0; i < eligible.size(); i++) {
            Verdict v = eligible.get(i);
            boolean in = i < rules.cap();
            out.add(new Verdict(v.candidate(), v.trackedMaxPosition(), in ? null : OVER_CAP, in));
        }
        return out;
    }
}
