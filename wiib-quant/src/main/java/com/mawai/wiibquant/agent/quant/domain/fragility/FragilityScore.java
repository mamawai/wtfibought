package com.mawai.wiibquant.agent.quant.domain.fragility;

/**
 * 市场脆弱度合成结果（确定性，简报头条产物）。
 *
 * @param score        0-100 综合脆弱度
 * @param level        分级
 * @param crowding     持仓拥挤分量 [0,1]
 * @param deleveraging 去杠杆剧烈度分量 [0,1]
 * @param volState     波动状态分量 [0,1]
 * @param direction    脆弱方向（拥挤反向推论，非预测）
 * @param headline     人话头条（确定性模板）
 */
public record FragilityScore(
        int score,
        FragilityLevel level,
        double crowding,
        double deleveraging,
        double volState,
        FragileDirection direction,
        String headline
) {
    /** 数据不足时的占位结果。 */
    public static FragilityScore empty() {
        return new FragilityScore(0, FragilityLevel.LOW, 0, 0, 0,
                FragileDirection.NEUTRAL, "数据不足，暂无脆弱度评估");
    }
}
