package com.mawai.wiibagent.chat.gate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRegistryTest {

    private final ApprovalRegistry registry = new ApprovalRegistry();

    private static final String TOOL = "run_deep_analysis";

    /** peek 是给"发确认卡片"用的：读完 pending 必须还在，否则 approve 时就没有 symbol 可绑了 */
    @Test
    void peek不消费pending() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");

        assertThat(registry.peekPending("s1")).isPresent();
        assertThat(registry.peekPending("s1")).isPresent();
    }

    /** 授权必须绑到具体的工具+标的：卡片上批的是 BTC，模型改口要 ETH 就得重新弹卡 */
    @Test
    void 授权只对被批准的标的生效() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String id = registry.peekPending("s1").orElseThrow().requestId();

        assertThat(registry.approve("s1", id)).isTrue();

        assertThat(registry.consumeApproval("s1", TOOL, "ETHUSDT")).isFalse();
        assertThat(registry.consumeApproval("s1", TOOL, "BTCUSDT")).isTrue();
    }

    /** 一次授权只放行一次执行，防止后续误触发 */
    @Test
    void 授权只能消费一次() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        registry.approve("s1", registry.peekPending("s1").orElseThrow().requestId());

        assertThat(registry.consumeApproval("s1", TOOL, "BTCUSDT")).isTrue();
        assertThat(registry.consumeApproval("s1", TOOL, "BTCUSDT")).isFalse();
    }

    /** approve 成功后 pending 才该被清掉——清早了就绑不上 symbol */
    @Test
    void approve成功后清掉pending() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String id = registry.peekPending("s1").orElseThrow().requestId();

        registry.approve("s1", id);

        assertThat(registry.peekPending("s1")).isEmpty();
    }

    /**
     * 用户无视旧卡片先问了新问题、再回头点旧卡片：此刻 pending 已被新请求覆盖，
     * 若不比对标识，用户看着"深研判 BTC"点的同意会授权给 ETH。
     * <p>
     * 标识必须是 UUID 不能是时间戳：两次 requestApproval 之间是微秒级，
     * JDK 25 实测 200/200 落在同一毫秒，用时间戳比对恒成立、这条防线等于不存在
     */
    @Test
    void 旧卡片的标识对不上时拒绝授权() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String staleId = registry.peekPending("s1").orElseThrow().requestId();
        registry.requestApproval("s1", TOOL, "ETHUSDT", "贵操作");

        assertThat(registry.approve("s1", staleId)).isFalse();
        assertThat(registry.consumeApproval("s1", TOOL, "ETHUSDT")).isFalse();
        assertThat(registry.peekPending("s1")).isPresent();
    }

    /** 连发两次也必须拿到不同标识——标识是 UUID，不是时间戳/序号这类可撞可推的值 */
    @Test
    void 连续两次登记的标识不同() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String first = registry.peekPending("s1").orElseThrow().requestId();
        registry.requestApproval("s1", TOOL, "ETHUSDT", "贵操作");

        assertThat(registry.peekPending("s1").orElseThrow().requestId()).isNotEqualTo(first);
    }

    /** 拒绝后 pending 清掉，并留一条一次性标记让模型知道"用户拒了"，避免反复弹卡 */
    @Test
    void 拒绝后留下一次性标记() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String id = registry.peekPending("s1").orElseThrow().requestId();

        assertThat(registry.reject("s1", id)).isTrue();

        assertThat(registry.peekPending("s1")).isEmpty();
        assertThat(registry.consumeRejected("s1")).isPresent();
        // 一次性：读过就没了，用户改主意重新问时不该还被挡着
        assertThat(registry.consumeRejected("s1")).isEmpty();
    }

    /** 拒绝标记不能盖住之后的新授权：用户拒了又改主意批准，批准必须真的算数 */
    @Test
    void 批准时清掉残留的拒绝标记() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        registry.reject("s1", registry.peekPending("s1").orElseThrow().requestId());
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");

        registry.approve("s1", registry.peekPending("s1").orElseThrow().requestId());

        assertThat(registry.consumeRejected("s1")).isEmpty();
    }

    /**
     * 未被消费的授权必须能主动丢弃。三元组化之后 consumeApproval 有可能一直对不上
     *（模型改口换 symbol、或续跑轮压根没调工具），残留的那条会在十分钟内放行模型再调同一件事，
     * 用户以为自己没点过头
     */
    @Test
    void 丢弃授权后不再残留() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        registry.approve("s1", registry.peekPending("s1").orElseThrow().requestId());

        registry.discardApprovals("s1");

        assertThat(registry.consumeApproval("s1", TOOL, "BTCUSDT")).isFalse();
    }

    /**
     * approve 与 requestApproval 并发时的 check-then-act：get 之后无条件 remove
     * 会把期间新写入的 pending 一起抹掉，卡片素材就没了，用户再也点不出那张卡。
     * <p>
     * 计划原本想用"批准旧卡片"那个顺序序列钉这条，但那条序列在 requestId 比对那一关
     * 就返回了，压根走不到 CAS——把 CAS 拆成无条件 remove，12 条用例全绿（实测）。
     * 所以必须借 {@code beforePendingRemoval} 把并发写精确插进"比对通过、还没摘掉"那一瞬
     */
    @Test
    void 摘pending期间被新请求覆盖则批准失败() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String id = registry.peekPending("s1").orElseThrow().requestId();
        registry.beforePendingRemoval = () -> {
            registry.beforePendingRemoval = () -> { };   // 只插一次，否则嵌套登记会无限递归
            registry.requestApproval("s1", TOOL, "ETHUSDT", "贵操作");
        };

        assertThat(registry.approve("s1", id)).isFalse();

        // 新卡片素材必须完好无损，且旧卡片没有偷偷发出授权
        assertThat(registry.peekPending("s1")).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("ETHUSDT"));
        assertThat(registry.consumeApproval("s1", TOOL, "BTCUSDT")).isFalse();
    }

    /** 用户点了被覆盖掉的旧卡片：授权不能落到新标的上，新卡片也不能被这一点给清掉 */
    @Test
    void 批准旧卡片不会抹掉新的pending() {
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        String staleId = registry.peekPending("s1").orElseThrow().requestId();
        registry.requestApproval("s1", TOOL, "ETHUSDT", "贵操作");

        registry.approve("s1", staleId);

        assertThat(registry.peekPending("s1")).isPresent()
                .get().satisfies(p -> assertThat(p.symbol()).isEqualTo("ETHUSDT"));
    }

    /** 批了但一直没被消费的过期票，下一次批准时顺手清掉，不然一直堆着 */
    @Test
    void 批准时清掉过期票() {
        registry.nowMs = () -> 0L;
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        registry.approve("s1", registry.peekPending("s1").orElseThrow().requestId());

        registry.nowMs = () -> ApprovalRegistry.APPROVAL_TTL_MS + 1;
        registry.requestApproval("s2", TOOL, "ETHUSDT", "贵操作");
        registry.approve("s2", registry.peekPending("s2").orElseThrow().requestId());

        assertThat(registry.approvedCount()).isEqualTo(1);
    }

    /** 过期票不是票：消费时自己判 TTL，否则十分钟前批的那次同意能在任意久之后放行一次深研判 */
    @Test
    void 过期授权不能被消费() {
        registry.nowMs = () -> 0L;
        registry.requestApproval("s1", TOOL, "BTCUSDT", "贵操作");
        registry.approve("s1", registry.peekPending("s1").orElseThrow().requestId());

        registry.nowMs = () -> ApprovalRegistry.APPROVAL_TTL_MS + 1;

        assertThat(registry.consumeApproval("s1", TOOL, "BTCUSDT")).isFalse();
    }
}
