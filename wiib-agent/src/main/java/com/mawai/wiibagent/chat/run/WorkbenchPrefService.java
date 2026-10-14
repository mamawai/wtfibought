package com.mawai.wiibagent.chat.run;

import com.mawai.wiibcommon.entity.UserWorkbenchPref;
import com.mawai.wiibagent.mapper.UserWorkbenchPrefMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 工作台用户偏好的读写：后台子 agent 开关（默认开）与同时在跑上限（默认 3，服务端硬上限钳住）。
 * 每次派发现读，不进叶子缓存键——开关只影响 delegate 拒不拒，工具表不随它变。
 */
@Service
public class WorkbenchPrefService {

    /** 当前生效的偏好 */
    public record Prefs(boolean subAgentsEnabled, int subAgentLimit) {
    }

    private final UserWorkbenchPrefMapper mapper;
    private final int defaultLimit;
    /** 用户能调到的最大值 */
    private final int hardLimit;

    public WorkbenchPrefService(UserWorkbenchPrefMapper mapper,
                                @Value("${agent.workbench.sub-agent-limit:3}") int defaultLimit,
                                @Value("${agent.workbench.sub-agent-hard-limit:5}") int hardLimit) {
        this.mapper = mapper;
        this.defaultLimit = defaultLimit;
        this.hardLimit = hardLimit;
    }

    public int hardLimit() {
        return hardLimit;
    }

    /** 没行就是默认 */
    public Prefs of(long userId) {
        UserWorkbenchPref row = mapper.selectById(userId);
        if (row == null) {
            return new Prefs(true, defaultLimit);
        }
        return new Prefs(!Boolean.FALSE.equals(row.getSubAgentsEnabled()), clamp(row.getSubAgentLimit()));
    }

    /** 保存即覆盖；上限钳在 1..hardLimit */
    public Prefs save(long userId, boolean subAgentsEnabled, Integer subAgentLimit) {
        UserWorkbenchPref row = mapper.selectById(userId);
        boolean fresh = row == null;
        if (fresh) {
            row = new UserWorkbenchPref();
            row.setUserId(userId);
        }
        row.setSubAgentsEnabled(subAgentsEnabled);
        row.setSubAgentLimit(clamp(subAgentLimit));
        if (fresh) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return new Prefs(subAgentsEnabled, row.getSubAgentLimit());
    }

    private int clamp(Integer limit) {
        if (limit == null) {
            return defaultLimit;
        }
        return Math.max(1, Math.min(hardLimit, limit));
    }
}
