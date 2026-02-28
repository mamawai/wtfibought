package com.mawai.wiibservice.fouronefour.controller;

import com.mawai.wiibcommon.util.Result;
import io.agora.media.RtcTokenBuilder2;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "声网语音接口")
@RestController
@RequestMapping("/api/agora")
public class AgoraController {

    @Value("${agora.app-id}")
    private String appId;

    @Value("${agora.app-certificate}")
    private String appCertificate;

    @GetMapping("/token")
    public Result<Map<String, Object>> getToken(
            @RequestParam String channel,
            @RequestParam int uid
    ) {
        int tokenExpire = 3600 * 6;
        int privilegeExpire = 3600 * 6;

        RtcTokenBuilder2 builder = new RtcTokenBuilder2();
        String token = builder.buildTokenWithUid(
                appId,
                appCertificate,
                channel,
                uid,
                RtcTokenBuilder2.Role.ROLE_PUBLISHER,
                tokenExpire,
                privilegeExpire
        );

        return Result.ok(Map.of(
                "token", token,
                "appId", appId,
                "channel", channel,
                "uid", uid
        ));
    }
}
