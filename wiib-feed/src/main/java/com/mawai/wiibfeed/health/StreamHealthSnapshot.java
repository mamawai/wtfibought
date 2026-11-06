package com.mawai.wiibfeed.health;

import java.util.List;

/** 全部 WS 流的健康快照；version 越大越新，推送和拉取都带着 */
public record StreamHealthSnapshot(long version, List<StreamHealth> streams) {}
