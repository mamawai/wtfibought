package com.mawai.wiibquant.agent.research.eval;

import java.util.ArrayList;
import java.util.List;

/** 前向 walk-forward 切分：每个 test 块前留 (horizonBars purge + embargo) 间隔，杜绝标签泄漏。 */
public final class WalkForwardEvaluator {

    private WalkForwardEvaluator() {
    }

    /**
     * total          决策点总数
     * testSize       每个样本外 test 块的大小（决策点数）
     * horizonBars    标签前视长度（purge）：三隔栏标签向前看 horizonBars 个 bar，
     *                不留此间隔则末段训练标签会与 test 重叠 → 泄漏
     * embargo        额外安全带（spec 默认≈1 个 horizon）
     * minTrain       训练集最小规模，不够则跳过该窗
     *
     * train 取 [0, testStart - horizonBars - embargo)（扩张窗），test 块连续前进、互不重叠。
     * 注：本刀前向 only（train 永在 test 之前），完整 CPCV/post-test embargo 推迟到后续 slice（spec §7）。
     */
    public static List<WalkForwardWindow> windows(int total, int testSize, int horizonBars,
                                                  int embargo, int minTrain) {
        List<WalkForwardWindow> out = new ArrayList<>();
        if (testSize <= 0 || total <= 0) return out;
        int gap = horizonBars + embargo;
        for (int testStart = minTrain + gap; testStart + testSize <= total; testStart += testSize) {
            int trainEnd = testStart - gap;
            if (trainEnd < minTrain) continue;
            out.add(new WalkForwardWindow(0, trainEnd, testStart, testStart + testSize));
        }
        return out;
    }
}
