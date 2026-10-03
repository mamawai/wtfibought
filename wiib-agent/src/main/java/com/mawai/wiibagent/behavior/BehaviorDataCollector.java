package com.mawai.wiibagent.behavior;

import com.mawai.wiibquant.external.sim.SimInternalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 行为分析的数据采集：并发拉 sim 的 7 个 {@code /internal/behavior} 端点（{@link SimInternalClient}），
 * quant 与 sim 编译解耦。
 * <p>这 7 段是固定的——每个端点的入参只有 userId、路径写死，模型在"查什么"上没有决策空间，
 * 所以不做成 @Tool 让它一轮轮来要，分析前一次性拉齐（见 {@link BehaviorAnalysisWorkflow}）。
 * <p>这里只出数据不出文案：段标题按语言取自提示词词表（{@code behavior.section.<端点名>}），
 * 端点名就是那条 key。
 * <p>老 GBM 股市/期权已下线，bStock 代币化美股为现役维度。
 */
@Component
@RequiredArgsConstructor
public class BehaviorDataCollector {

    /** 端点名，顺序即 prompt 段序；同时是词表里段标题的 key 后缀 */
    private static final List<String> ENDPOINTS = List.of(
            "user-profile", "portfolio-summary", "asset-snapshots", "crypto-stats", "bstock-stats",
            "futures-stats", "prediction-stats");

    /** 采集到的一段：json 可能是错误 JSON，见 {@link #collect} */
    public record Section(String endpoint, String json) {
    }

    /**
     * 采集进度回调：只报数，不报文案——文案要按语言取词表，而这里连 lang 都不该知道
     * （见类注释"只出数据不出文案"）。拼字的活归 {@link BehaviorAnalysisWorkflow}。
     * <p>会被多个线程回调，实现方自己保证线程安全。
     */
    public interface ProgressSink {
        void onCollected(int done, int total);
    }

    private final SimInternalClient simClient;

    /**
     * 并发拉齐全部 7 段。单段失败不抛——{@link SimInternalClient#getJson} 失败返回错误 JSON，
     * 这里原样带走：一个端点挂了只该让模型知道这块没有数据，不该整份报告作废。
     * <p>7 个都是纯阻塞 HTTP（连接 1s / 读 5s），虚拟线程直接一段一根，总耗时按最慢的那段算。
     */
    public List<Section> collect(long userId, ProgressSink onProgress) {
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Section>> tasks = ENDPOINTS.stream()
                    .map(endpoint -> CompletableFuture.supplyAsync(
                            () -> fetch(userId, endpoint, done, onProgress), pool))
                    .toList();
            // 按提交顺序 join：prompt 里的段序恒等于 ENDPOINTS 的顺序，不随网络快慢抖动
            return tasks.stream().map(CompletableFuture::join).toList();
        }
    }

    private Section fetch(long userId, String endpoint, AtomicInteger done, ProgressSink onProgress) {
        String json = simClient.getJson("/internal/behavior/" + userId + "/" + endpoint);
        if (onProgress != null) {
            onProgress.onCollected(done.incrementAndGet(), ENDPOINTS.size());
        }
        return new Section(endpoint, json);
    }
}
