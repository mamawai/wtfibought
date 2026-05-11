package com.mawai.wiibservice.agent.quant.domain.output;

import java.util.List;

public record LightNewsResponse(
        List<LightNewsVoteResponse> votes
) {
}
