package com.mawai.wiibagent.handover;

import com.mawai.wiibcommon.handover.PeerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 转问对端：对端在收尾才转，转来的不再往回转；带原请求的登录头、语言头，同一方法同一路径；转不通用本地的 */
class PeerRelayTest {

    private static final ParameterizedTypeReference<String> TEXT = new ParameterizedTypeReference<>() {
    };

    private final PeerClient client = mock(PeerClient.class);
    private final PeerProbe probe = mock(PeerProbe.class);
    private final PeerRelay relay = new PeerRelay(client, probe);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/workbench/cancel");

    {
        request.setQueryString("x=1");
        request.addHeader("satoken", "tok");
        request.addHeader("X-Lang", "en");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void relaysSameRequestWithLoginAndLangHeaders() {
        when(probe.peerDraining()).thenReturn(true);
        Map<String, String> body = Map.of("sessionId", "wb-1-a");
        when(client.relay(eq(HttpMethod.POST), eq("/api/ai/workbench/cancel?x=1"), any(), eq(body), eq(TEXT)))
                .thenReturn("peer");

        assertThat(relay.relay(body, TEXT)).contains("peer");

        ArgumentCaptor<HttpHeaders> headers = ArgumentCaptor.forClass(HttpHeaders.class);
        verify(client).relay(any(), any(), headers.capture(), any(), any());
        assertThat(headers.getValue().getFirst("satoken")).isEqualTo("tok");
        assertThat(headers.getValue().getFirst("X-Lang")).isEqualTo("en");
        assertThat(headers.getValue().getFirst(PeerRelay.RELAYED)).isNotNull();
    }

    @Test
    void doesNotRelayWhenPeerIsNotDraining() {
        assertThat(relay.relay(null, TEXT)).isEmpty();
        verifyNoInteractions(client);
    }

    /** 对端转来的请求不再往回转 */
    @Test
    void doesNotRelayBack() {
        when(probe.peerDraining()).thenReturn(true);
        request.addHeader(PeerRelay.RELAYED, "1");

        assertThat(relay.relay(null, TEXT)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void unreachablePeerMeansEmpty() {
        when(probe.peerDraining()).thenReturn(true);
        when(client.relay(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("连不上"));

        assertThat(relay.relay(null, TEXT)).isEmpty();
    }
}
