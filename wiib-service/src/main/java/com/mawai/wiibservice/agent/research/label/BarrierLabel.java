package com.mawai.wiibservice.agent.research.label;

/** 三隔栏标签：先触上栏=+1，先触下栏=−1，到期都没触=0。 */
public enum BarrierLabel {
    UPPER(1), LOWER(-1), VERTICAL(0);

    private final int value;

    BarrierLabel(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
