package com.mawai.wiibsim.service;

/**
 * 全仓强平引擎。
 *
 * <p>与逐仓的"静态强平价 + ZSet 价格穿越"不同：全仓强平线随余额和所有仓位浮盈亏实时变化、
 * 跨 symbol 耦合，无法预存静态价。改用账户级健康检查：
 * 价格 tick 定向触发（只查该 symbol 上有全仓仓位的用户）+ 低频兜底轮询。
 * 轮询制天然没有"错过价格穿越"问题，宕机重启也无需补偿。</p>
 *
 * <p>爆仓 = 全组爆：equity ≤ Σ维持保证金时，该用户所有全仓仓位按 mark 价一次性强平，
 * 盈亏净额直接结算进余额钱包（可为负）；结算后余额 &lt; 0 即穿仓 → 立即破产
 * （清空两个钱包，次一交易日重置初始资金）。</p>
 */
public interface CrossLiquidationService {

    /** markprice tick：定向检查该 symbol 上持有全仓仓位的用户 */
    void onPriceTick(String symbol);

    /** 单用户健康检查，触线则全组爆（带用户级锁，可安全并发调用） */
    void checkUser(Long userId);

    /** 兜底轮询：检查所有持有全仓仓位的用户 */
    void sweepAll();
}
