package com.mawai.wiibquant;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地回测/研究 runner 的 DB 密码加载：真实密码不进 git。
 * 取值顺序：-DLOCAL_PASSWORD → 环境变量 LOCAL_PASSWORD → 仓库根 .env.local 的 LOCAL_PASSWORD=。
 * 三处都没有则返回空串，连接失败走各 runner 既有的"postgres 不可达即跳过"路径。
 */
public final class LocalEnv {

    private LocalEnv() {
    }

    public static String dbPassword() {
        String v = System.getProperty("LOCAL_PASSWORD");
        if (v == null || v.isBlank()) v = System.getenv("LOCAL_PASSWORD");
        if (v == null || v.isBlank()) v = fromEnvLocal();
        return v == null ? "" : v.trim();
    }

    /** 从工作目录向上找 .env.local：mvn 跑在模块目录、IDE 可能在仓库根，向上四级足够。 */
    private static String fromEnvLocal() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path f = dir.resolve(".env.local");
            if (!Files.exists(f)) continue;
            try {
                for (String line : Files.readAllLines(f)) {
                    String t = line.trim();
                    if (t.startsWith("LOCAL_PASSWORD=")) {
                        return t.substring("LOCAL_PASSWORD=".length());
                    }
                }
            } catch (Exception ignored) {
            }
            return null;   // 找到文件但没有该键：不再向上找
        }
        return null;
    }
}
