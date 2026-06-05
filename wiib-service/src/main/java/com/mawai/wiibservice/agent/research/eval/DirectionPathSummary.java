package com.mawai.wiibservice.agent.research.eval;

import java.util.List;

/** 方向预测在未来 horizon 真实路径上的验证摘要：方向收益、MFE、MAE 都以 bps 计。 */
public record DirectionPathSummary(int tradedPoints,
                                   double avgDirectionalChangeBps,
                                   double avgMaxFavorableBps,
                                   double avgMaxAdverseBps) {

    public static DirectionPathSummary from(List<Integer> directions, List<HorizonPathOutcome> outcomes) {
        if (directions == null || outcomes == null || directions.size() != outcomes.size()) {
            throw new IllegalArgumentException("方向和路径结果长度必须一致");
        }
        int n = 0;
        double directional = 0.0;
        double favorable = 0.0;
        double adverse = 0.0;
        for (int i = 0; i < directions.size(); i++) {
            int dir = Integer.compare(directions.get(i), 0);
            if (dir == 0) {
                continue;
            }
            HorizonPathOutcome outcome = outcomes.get(i);
            directional += outcome.directionalChangeBps(dir);
            favorable += outcome.maxFavorableBps(dir);
            adverse += outcome.maxAdverseBps(dir);
            n++;
        }
        return n == 0
                ? new DirectionPathSummary(0, 0.0, 0.0, 0.0)
                : new DirectionPathSummary(n, directional / n, favorable / n, adverse / n);
    }
}
