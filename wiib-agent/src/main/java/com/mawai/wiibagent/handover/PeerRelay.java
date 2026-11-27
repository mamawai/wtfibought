package com.mawai.wiibagent.handover;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.handover.PeerClient;
import com.mawai.wiibcommon.i18n.RequestLangFilter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * 转问对端：把浏览器的请求转给在收尾的对端，带上原请求的登录头和语言头，对端自己认用户。
 * 转过去的带 {@link #RELAYED} 头，对端看到就不再往回转。对端不在收尾、或者这个请求本来就是转来的，不转
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PeerRelay {

    public static final String RELAYED = "X-Wiib-Relayed";

    private final PeerClient client;
    private final PeerProbe probe;

    /** 当前请求原样转过去：同一方法、同一路径和查询串。不转或转不通是空 */
    public <T> Optional<T> relay(Object body, ParameterizedTypeReference<T> type) {
        HttpServletRequest req = request();
        String uri = req.getQueryString() == null ? req.getRequestURI() : req.getRequestURI() + "?" + req.getQueryString();
        return ask(HttpMethod.valueOf(req.getMethod()), uri, body, type);
    }

    /** 带着当前请求的登录头和语言头，问对端另一个接口。不转或转不通是空 */
    public <T> Optional<T> ask(HttpMethod method, String pathAndQuery, Object body, ParameterizedTypeReference<T> type) {
        HttpServletRequest req = request();
        if (!probe.peerDraining() || req.getHeader(RELAYED) != null) {
            return Optional.empty();
        }
        HttpHeaders headers = new HttpHeaders();
        copy(req, headers, StpUtil.getTokenName());
        copy(req, headers, RequestLangFilter.HEADER);
        headers.set(RELAYED, "1");
        try {
            return Optional.ofNullable(client.relay(method, pathAndQuery, headers, body, type));
        } catch (Exception e) {
            log.warn("[PeerRelay] 转给对端失败，用本地的结果 {} {} msg={}", method, pathAndQuery, e.toString());
            return Optional.empty();
        }
    }

    private static void copy(HttpServletRequest req, HttpHeaders headers, String name) {
        String value = req.getHeader(name);
        if (value != null) {
            headers.set(name, value);
        }
    }

    private static HttpServletRequest request() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}
