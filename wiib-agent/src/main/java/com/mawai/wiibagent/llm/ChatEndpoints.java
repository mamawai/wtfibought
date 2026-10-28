package com.mawai.wiibagent.llm;

import com.mawai.wiibcommon.entity.UserLlmEndpoint;

/**
 * 对话轨一次建模的全部要素：这个用户 + 主模型端点 + 轻模型端点。
 * <p>
 * light 可空 = 用户没单独绑轻模型，派出去的 worker 复用主模型；{@link #lightOrDeep()} 给下游一个非空视图。
 * userId 单独带着不是冗余：叶子里有按用户烤死的工具（trader 只读工具读的是"这个人的 trader"），
 * 缓存指纹必须含它，见 ChatModelFactory.fingerprint。
 */
public record ChatEndpoints(long userId, UserLlmEndpoint deep, UserLlmEndpoint light) {

    public UserLlmEndpoint lightOrDeep() {
        return light == null ? deep : light;
    }
}
