package com.mawai.wiibservice.agent.research.eval;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** research eval 报告目录约定：一次运行一个 run 目录，避免旧报告混进 DSR。 */
public final class ResearchEvalArtifacts {

    public static final String BASE_DIR_PROPERTY = "research.eval.dir";
    public static final String RUN_ID_PROPERTY = "research.eval.runId";
    public static final String RUNS_DIR = "runs";
    private static final Path DEFAULT_BASE_DIR = Path.of("target", "research-eval");
    private static final DateTimeFormatter RUN_ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
    private static final String DEFAULT_RUN_ID = RUN_ID_FORMAT.format(Instant.now());

    private ResearchEvalArtifacts() {
    }

    public static Path baseDir() {
        String configured = System.getProperty(BASE_DIR_PROPERTY);
        return configured == null || configured.isBlank() ? DEFAULT_BASE_DIR : Path.of(configured.trim());
    }

    public static String runId() {
        String configured = System.getProperty(RUN_ID_PROPERTY);
        return sanitizeRunId(configured == null || configured.isBlank() ? DEFAULT_RUN_ID : configured);
    }

    public static Path runDir() {
        return runDir(baseDir(), runId());
    }

    public static Path runDir(Path baseDir, String runId) {
        return runsDir(baseDir).resolve(sanitizeRunId(runId));
    }

    public static Path runsDir(Path baseDir) {
        return baseDir.resolve(RUNS_DIR);
    }

    public static String sanitizeRunId(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_RUN_ID;
        }
        return raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
