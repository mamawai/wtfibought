package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.AccountState;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient.Position;
import com.mawai.wiibquant.whale.WhaleQualifier.Candidate;
import com.mawai.wiibquant.whale.WhaleQualifier.Rules;
import com.mawai.wiibquant.whale.WhaleQualifier.Verdict;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证规则：净值、持仓数各一个刚好过/刚好不过；净值不够但仓位够大照过；入池排序（有仓位的排在净值更高但无仓位的前面）；cap 截断。
 */
class WhaleQualifierTest {

    private static final Rules RULES = new Rules(new BigDecimal("1000000"), 10, new BigDecimal("100000"),
            Set.of("BTC", "ETH"), 1000);

    private static Position pos(String coin, String value) {
        return new Position(coin, BigDecimal.ONE, BigDecimal.ONE, new BigDecimal(value), 10, null, BigDecimal.ZERO);
    }

    private static AccountState state(String accountValue, Position... positions) {
        return new AccountState(new BigDecimal(accountValue), List.of(positions));
    }

    private static Candidate master(String addr, String role, AccountState s) {
        return new Candidate(addr, null, role, s);
    }

    private static String gate1(AccountState s) {
        return WhaleQualifier.gate1(master("0x1", "user", s), RULES).rejectReason();
    }

    // ---------- 门 1 ----------

    @Test
    void 净值刚好够过_差一分不过() {
        assertThat(gate1(state("1000000"))).isNull();
        assertThat(gate1(state("999999.99"))).isEqualTo(WhaleQualifier.SMALL);
    }

    @Test
    void 持仓10个过_11个不过() {
        Position[] ten = new Position[10];
        Position[] eleven = new Position[11];
        for (int i = 0; i < 11; i++) {
            Position p = pos("C" + i, "1000");
            if (i < 10) {
                ten[i] = p;
            }
            eleven[i] = p;
        }
        assertThat(gate1(state("5000000", ten))).isNull();
        assertThat(gate1(state("5000000", eleven))).isEqualTo(WhaleQualifier.TOO_MANY_POSITIONS);
    }

    @Test
    void 净值不够但盯盘币仓位够大_照过_差一块不过_非盯盘币不算() {
        assertThat(gate1(state("50000", pos("BTC", "100000")))).isNull();
        assertThat(gate1(state("50000", pos("BTC", "99999")))).isEqualTo(WhaleQualifier.SMALL);
        assertThat(gate1(state("50000", pos("SOL", "5000000")))).isEqualTo(WhaleQualifier.SMALL);
    }

    @Test
    void 仓位够大_持仓数那条仍要过() {
        List<Position> ps = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            ps.add(pos(i == 0 ? "BTC" : "C" + i, "500000"));
        }
        AccountState s = new AccountState(new BigDecimal("800000"), ps);
        assertThat(gate1(s)).isEqualTo(WhaleQualifier.TOO_MANY_POSITIONS);
    }

    @Test
    void 盯盘币最大仓位_只看配置里的币() {
        AccountState s = state("2000000", pos("BTC", "300000"), pos("ETH", "700000"), pos("SOL", "9000000"));
        assertThat(WhaleQualifier.gate1(master("0x1", "user", s), RULES).trackedMaxPosition()).isEqualByComparingTo("700000");
        assertThat(WhaleQualifier.gate1(master("0x1", "user", state("2000000")), RULES).trackedMaxPosition()).isEqualByComparingTo("0");
    }

    // ---------- 门 2 + 入池 ----------

    @Test
    void 入池排序_有仓位的排在净值更高但无仓位的前面_组内净值降序() {
        Verdict rich = WhaleQualifier.gate1(master("0xrich", "user", state("50000000")), RULES);
        Verdict small = WhaleQualifier.gate1(master("0xsmall", "user", state("1500000", pos("ETH", "120000"))), RULES);
        Verdict mid = WhaleQualifier.gate1(master("0xmid", "user", state("3000000", pos("BTC", "200000"))), RULES);
        // 仓位不够 min-position-value 的算"无仓位"组
        Verdict tiny = WhaleQualifier.gate1(master("0xtiny", "user", state("60000000", pos("BTC", "50000"))), RULES);

        List<Verdict> out = WhaleQualifier.rank(List.of(rich, small, mid, tiny), RULES);

        assertThat(out).extracting(v -> v.candidate().address()).containsExactly("0xmid", "0xsmall", "0xtiny", "0xrich");
        assertThat(out).allMatch(Verdict::inPool);
    }

    @Test
    void cap截断_超出的标OVER_CAP() {
        Rules cap2 = new Rules(RULES.minAccountValue(), RULES.maxPositions(), RULES.minPositionValue(), RULES.coins(), 2);
        List<Verdict> in = List.of(
                WhaleQualifier.gate1(master("0xa", "user", state("2000000")), cap2),
                WhaleQualifier.gate1(master("0xb", "user", state("3000000")), cap2),
                WhaleQualifier.gate1(master("0xc", "user", state("4000000")), cap2),
                WhaleQualifier.gate1(master("0xv", "vault", state("9000000")), cap2));

        List<Verdict> out = WhaleQualifier.rank(in, cap2);

        assertThat(out).filteredOn(Verdict::inPool).extracting(v -> v.candidate().address()).containsExactly("0xc", "0xb");
        Verdict a = out.stream().filter(x -> x.candidate().address().equals("0xa")).findFirst().orElseThrow();
        assertThat(a.rejectReason()).isEqualTo(WhaleQualifier.OVER_CAP);
        assertThat(a.inPool()).isFalse();
        // vault 不占 cap 名额
        assertThat(out).filteredOn(v -> WhaleQualifier.VAULT.equals(v.rejectReason())).hasSize(1);
    }
}
