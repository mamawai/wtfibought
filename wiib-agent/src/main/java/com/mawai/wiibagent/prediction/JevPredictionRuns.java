package com.mawai.wiibagent.prediction;

import com.mawai.wiibagent.mapper.JevPredictionRunMapper;
import com.mawai.wiibcommon.entity.JevPredictionRun;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 预测员的局：当前局、全部局、开新局。表里至少有 R1（init.sql 种好） */
@Component
@RequiredArgsConstructor
public class JevPredictionRuns {

    private final JevPredictionRunMapper mapper;
    private final JevPredictionAccount account;

    public JevPredictionRun current() {
        return all().getFirst();
    }

    /** 新的在前 */
    public List<JevPredictionRun> all() {
        return mapper.selectAllDesc();
    }

    /** 在 {@link #all()} 的结果里按局号找；没有这一局回当前局 */
    public static JevPredictionRun find(List<JevPredictionRun> runs, Integer runNo) {
        return runs.stream().filter(r -> r.getRunNo().equals(runNo)).findFirst().orElse(runs.getFirst());
    }

    /** 开新局：先向 sim 建新号注资，再落一行；旧局的账户和决策原样留着 */
    public JevPredictionRun startNew(String label) {
        int next = current().getRunNo() + 1;
        account.userId(next);
        JevPredictionRun run = new JevPredictionRun(next, label == null || label.isBlank() ? null : label.strip(),
                System.currentTimeMillis());
        mapper.insert(run);
        return run;
    }
}
