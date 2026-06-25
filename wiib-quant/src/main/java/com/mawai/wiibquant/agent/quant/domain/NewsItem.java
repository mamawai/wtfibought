package com.mawai.wiibquant.agent.quant.domain;

public record NewsItem(
        String title,
        String summary,
        String sourceKey,
        String guid,
        long publishedOn
) {}
