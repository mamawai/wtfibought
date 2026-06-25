package com.mawai.wiibquant.agent.research.eval;

/** 单个滚动窗：train=[trainStart,trainEnd)，test=[testStart,testEnd)，二者间有 purge+embargo 间隔。 */
public record WalkForwardWindow(int trainStart, int trainEnd, int testStart, int testEnd) {

    public int trainSize() {
        return trainEnd - trainStart;
    }

    public int testSize() {
        return testEnd - testStart;
    }
}
