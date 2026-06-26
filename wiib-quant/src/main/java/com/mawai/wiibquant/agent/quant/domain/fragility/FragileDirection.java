package com.mawai.wiibquant.agent.quant.domain.fragility;

/**
 * 脆弱方向：市场更易朝哪个方向破裂（拥挤反向推论）。
 *
 * <p><b>非"预测方向"</b>——只表达"若失衡，哪个方向更脆"，由持仓拥挤的反向决定。</p>
 */
public enum FragileDirection {
    UP("上行脆弱"),
    DOWN("下行脆弱"),
    NEUTRAL("方向不明");

    private final String cn;

    FragileDirection(String cn) {
        this.cn = cn;
    }

    public String cn() {
        return cn;
    }
}
