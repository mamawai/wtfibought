package com.mawai.wiibagent.promptgen;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mawai.wiibagent.chat.store.ChatContextCodec;
import com.mawai.wiibagent.chat.store.ChatContextStore;
import com.mawai.wiibagent.mapper.PromptGenSessionMapper;
import com.mawai.wiibagent.mapper.PromptGenVersionMapper;
import com.mawai.wiibcommon.entity.PromptGenSession;
import com.mawai.wiibcommon.entity.PromptGenVersion;
import com.mawai.wiibcommon.enums.AgentLang;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** 生成器的对话与版本读写；对话历史和版本快照都用工作台同一套编解码 */
@Component
@RequiredArgsConstructor
public class PromptGenStore {

    /** 每个用户留几版 */
    static final int KEEP_VERSIONS = 3;

    private final PromptGenSessionMapper sessionMapper;
    private final PromptGenVersionMapper versionMapper;

    public PromptGenSession session(long userId) {
        return sessionMapper.selectById(userId);
    }

    /** 开新对话：整行覆盖，版本不动 */
    public void open(long userId, long endpointId, AgentLang outputLang, List<Message> messages) {
        PromptGenSession session = new PromptGenSession();
        session.setUserId(userId);
        session.setEndpointId(endpointId);
        session.setOutputLang(outputLang.code());
        session.setState(ChatContextCodec.write(messages));
        sessionMapper.upsert(session);
    }

    /** @param prefix 这份对话是在哪套系统提示词与工具下跑出来的（{@link com.mawai.wiibagent.llm.LlmClient#fingerprint}） */
    public void saveState(long userId, List<Message> messages, String prefix) {
        sessionMapper.updateState(userId, ChatContextCodec.write(new ChatContextStore.State(messages, null, prefix)));
    }

    static List<Message> messages(byte[] state) {
        return state(state).messages();
    }

    /** 存着的对话的前缀指纹；新开的对话、回到某一版之后、老数据为 null */
    static String prefix(byte[] state) {
        return state(state).prefix();
    }

    private static ChatContextStore.State state(byte[] state) {
        try {
            return ChatContextCodec.readState(state);
        } catch (IOException e) {
            throw new UncheckedIOException("生成器对话历史解不开", e);
        }
    }

    /** 最近几版，新的在前；不带快照 */
    public List<PromptGenVersion> versions(long userId) {
        return versionMapper.selectList(new LambdaQueryWrapper<PromptGenVersion>()
                .select(PromptGenVersion::getVersionNo, PromptGenVersion::getBody,
                        PromptGenVersion::getNotes, PromptGenVersion::getIsFinal)
                .eq(PromptGenVersion::getUserId, userId)
                .orderByDesc(PromptGenVersion::getVersionNo));
    }

    public int nextNo(long userId) {
        List<PromptGenVersion> versions = versions(userId);
        return versions.isEmpty() ? 1 : versions.getFirst().getVersionNo() + 1;
    }

    /** 存一版，再把最近几版以外的删掉 */
    void saveVersion(long userId, PromptGenHistory.Drafted drafted, AgentLang outputLang, List<Message> snapshot) {
        PromptGenVersion version = new PromptGenVersion();
        version.setUserId(userId);
        version.setVersionNo(drafted.no());
        version.setBody(drafted.body());
        version.setNotes(drafted.notes());
        version.setIsFinal(drafted.finalVersion());
        version.setOutputLang(outputLang.code());
        version.setState(ChatContextCodec.write(snapshot));
        versionMapper.insert(version);

        List<PromptGenVersion> versions = versions(userId);
        if (versions.size() > KEEP_VERSIONS) {
            versionMapper.delete(new LambdaQueryWrapper<PromptGenVersion>()
                    .eq(PromptGenVersion::getUserId, userId)
                    .lt(PromptGenVersion::getVersionNo, versions.get(KEEP_VERSIONS - 1).getVersionNo()));
        }
    }

    /** 用某一版的快照和正文语言替换当前对话；那版已经被挤掉了返回 false */
    public boolean restore(long userId, int no) {
        PromptGenVersion version = versionMapper.selectOne(new LambdaQueryWrapper<PromptGenVersion>()
                .eq(PromptGenVersion::getUserId, userId)
                .eq(PromptGenVersion::getVersionNo, no));
        if (version == null) {
            return false;
        }
        sessionMapper.restore(userId, version.getState(), version.getOutputLang());
        return true;
    }
}
