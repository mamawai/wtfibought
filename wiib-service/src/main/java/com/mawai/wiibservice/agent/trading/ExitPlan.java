package com.mawai.wiibservice.agent.trading;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开仓时锁定的退出契约。
 * 入场字段不可变；运行态字段只记录退出进度，避免 Playbook 混用旧引擎记忆。
 */
public final class ExitPlan {

    private final ExitPath path;
    private final String side;
    private final BigDecimal entryPrice;
    private final BigDecimal initialSL;
    private final BigDecimal riskPerUnit;
    private final Duration timeLimit;
    private final LocalDateTime createdAt;
    private final BigDecimal atrAtEntry;
    private final Double entryBollPb;
    private final Double entryRsi;
    private final Integer entryMa1h;
    private final Integer entryMa15m;
    private final boolean recovered;

    private volatile boolean breakevenDone;
    private final Set<Integer> partialDoneAtR;
    private volatile boolean mrMidlineHalfDone;
    private volatile double highestProfitR;

    ExitPlan(ExitPath path,
             String side,
             BigDecimal entryPrice,
             BigDecimal initialSL,
             BigDecimal riskPerUnit,
             Duration timeLimit,
             LocalDateTime createdAt,
             BigDecimal atrAtEntry,
             Double entryBollPb,
             Double entryRsi,
             Integer entryMa1h,
             Integer entryMa15m,
             boolean breakevenDone,
             Set<Integer> partialDoneAtR,
             boolean mrMidlineHalfDone,
             double highestProfitR,
             boolean recovered) {
        this.path = path;
        this.side = side;
        this.entryPrice = entryPrice;
        this.initialSL = initialSL;
        this.riskPerUnit = riskPerUnit;
        this.timeLimit = timeLimit;
        this.createdAt = createdAt;
        this.atrAtEntry = atrAtEntry;
        this.entryBollPb = entryBollPb;
        this.entryRsi = entryRsi;
        this.entryMa1h = entryMa1h;
        this.entryMa15m = entryMa15m;
        this.breakevenDone = breakevenDone;
        this.partialDoneAtR = ConcurrentHashMap.newKeySet();
        if (partialDoneAtR != null) {
            this.partialDoneAtR.addAll(partialDoneAtR);
        }
        this.mrMidlineHalfDone = mrMidlineHalfDone;
        this.highestProfitR = Math.max(0.0, highestProfitR);
        this.recovered = recovered;
    }

    public ExitPath path() {
        return path;
    }

    public String side() {
        return side;
    }

    public BigDecimal entryPrice() {
        return entryPrice;
    }

    public BigDecimal initialSL() {
        return initialSL;
    }

    public BigDecimal riskPerUnit() {
        return riskPerUnit;
    }

    public Duration timeLimit() {
        return timeLimit;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public BigDecimal atrAtEntry() {
        return atrAtEntry;
    }

    public Double entryBollPb() {
        return entryBollPb;
    }

    public Double entryRsi() {
        return entryRsi;
    }

    public Integer entryMa1h() {
        return entryMa1h;
    }

    public Integer entryMa15m() {
        return entryMa15m;
    }

    public boolean breakevenDone() {
        return breakevenDone;
    }

    public void markBreakevenDone() {
        this.breakevenDone = true;
    }

    public Set<Integer> partialDoneAtR() {
        return Collections.unmodifiableSet(partialDoneAtR);
    }

    public boolean isPartialDoneAtR(int rTimes100) {
        return partialDoneAtR.contains(rTimes100);
    }

    public void markPartialDoneAtR(int rTimes100) {
        partialDoneAtR.add(rTimes100);
    }

    public boolean mrMidlineHalfDone() {
        return mrMidlineHalfDone;
    }

    public void markMrMidlineHalfDone() {
        this.mrMidlineHalfDone = true;
    }

    public double highestProfitR() {
        return highestProfitR;
    }

    public void recordHighestProfitR(double profitR) {
        if (profitR > highestProfitR && profitR > 0.0) {
            highestProfitR = profitR;
        }
    }

    public boolean recovered() {
        return recovered;
    }
}
