package com.mawai.wiibagent.controller;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibagent.llm.SseChannel;
import com.mawai.wiibagent.replay.ReplayCoachRequest;
import com.mawai.wiibagent.replay.ReplayCoachService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 手动复盘的 AI 教练：局中盘面提示 / 结算后评估用户看法。
 * 校验、准入、流式调用全在 {@link ReplayCoachService}，这里只管 HTTP 那层。
 */
@Tag(name = "复盘 AI 教练")
@RestController
@RequestMapping("/api/ai/backtest/replay")
@RequiredArgsConstructor
public class ReplayCoachController {

    private final ReplayCoachService coachService;

    @PostMapping(value = "/coach", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "复盘 AI 教练（SSE：HINT 盘面提示 / REVIEW 评估用户看法）")
    public SseEmitter coach(@CurrentUserId long userId, @RequestBody ReplayCoachRequest request,
                            HttpServletResponse response) {
        SseChannel.noProxyBuffering(response);
        return coachService.start(userId, request);
    }
}
