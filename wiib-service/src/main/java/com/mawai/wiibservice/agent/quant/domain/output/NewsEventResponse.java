package com.mawai.wiibservice.agent.quant.domain.output;

import java.util.List;

public record NewsEventResponse(
        List<NewsEventFilteredNewsResponse> filteredNews,
        List<NewsEventVoteResponse> votes
) {
}
