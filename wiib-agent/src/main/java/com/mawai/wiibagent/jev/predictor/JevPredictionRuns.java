package com.mawai.wiibagent.jev.predictor;

import com.mawai.wiibagent.mapper.JevPredictionRunMapper;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.mawai.wiibcommon.entity.JevPredictionRun.ARMS;
import static com.mawai.wiibcommon.entity.JevPredictionRun.ARM_JUMP_CODE;

/**
 * 预测员的局：全部局、在跑的局、开新局。一次开局建三局，三组对照各一局一个账户；
 * 每组局号最大的那一局在跑，还没开过带组的局时预测员不跑。表里至少有 R1（init.sql 种好）
 */
@Component
@RequiredArgsConstructor
public class JevPredictionRuns {

    private final JevPredictionRunMapper mapper;
    private final JevPredictionAccount account;
    private final JevPredictionConfig cfg;

    /** 新的在前 */
    public List<JevPredictionRun> all() {
        return mapper.selectAllDesc();
    }

    /** 在跑的局，按 v5-1、v5-2、v5-3 排 */
    public List<JevPredictionRun> active() {
        return active(all());
    }

    /** 在 {@link #all()} 的结果里取每组局号最大的那一局；all 是新的在前，没开过的组不在里面 */
    public static List<JevPredictionRun> active(List<JevPredictionRun> all) {
        List<JevPredictionRun> out = new ArrayList<>();
        for (String arm : ARMS) {
            all.stream().filter(r -> arm.equals(r.getArm())).findFirst().ifPresent(out::add);
        }
        return out;
    }

    /** 在 {@link #all()} 的结果里按局号找；没传或没有这一局，看 v5-1 在跑的那一局，还没开过带组的局就看最新一局 */
    public static JevPredictionRun find(List<JevPredictionRun> runs, Integer runNo) {
        return runs.stream().filter(r -> r.getRunNo().equals(runNo)).findFirst()
                .orElseGet(() -> runs.stream().filter(r -> ARM_JUMP_CODE.equals(r.getArm())).findFirst()
                        .orElse(runs.getFirst()));
    }

    /** 开新局：三组各一局，局号连着，先向 sim 把三个新号都建好注资再落三行；旧局的账户和决策原样留着 */
    public List<JevPredictionRun> startNew(String label) {
        int next = all().getFirst().getRunNo() + 1;
        String text = label == null || label.isBlank() ? null : label.strip();
        long now = System.currentTimeMillis();
        List<JevPredictionRun> created = new ArrayList<>();
        for (int i = 0; i < ARMS.size(); i++) {
            created.add(new JevPredictionRun(next + i, text, now, ARMS.get(i), cfg.getInitialBalance()));
        }
        // sim 没起来就一行都不落
        created.forEach(r -> account.userId(r.getRunNo()));
        created.forEach(mapper::insert);
        return created;
    }
}
