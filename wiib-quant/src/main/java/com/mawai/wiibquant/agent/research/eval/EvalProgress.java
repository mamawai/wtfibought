package com.mawai.wiibquant.agent.research.eval;

/** 本地 runner 进度回调；生产评估默认 NOOP，不把控制台输出混进业务路径。 */
@FunctionalInterface
interface EvalProgress {

    EvalProgress NOOP = (stage, done, total) -> {
    };

    void update(String stage, int done, int total);
}
