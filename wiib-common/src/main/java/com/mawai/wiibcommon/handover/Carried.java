package com.mawai.wiibcommon.handover;

import tools.jackson.databind.JsonNode;

/** 交接时带走的状态：旧实例让位时导出一格放进交接单，新实例接手时收下自己那一格。编排不看内容 */
public interface Carried {

    /** 在交接单里的格名 */
    String key();

    /** 此刻的状态。让位时取一次，收尾期间 /status 也会取 */
    JsonNode export();

    /** 收下对端交来的 */
    void accept(JsonNode state);
}
