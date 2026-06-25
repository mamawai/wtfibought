package com.mawai.wiibquant.agent.quant.util;

import com.mawai.wiibquant.agent.quant.domain.NewsItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NewsRelevance {

    private static final Map<String, List<String>> ASSET_ALIASES = createAliases();
    private static final List<String> MACRO_KEYWORDS = List.of(
            " crypto market ", " spot etf ", " etf inflows ", " institutional inflows ",
            " macro ", " federal reserve ", " risk assets ", " digital asset ",
            " whale ", " miners ", " stablecoin ", " regulation ", " sec "
    );

    private NewsRelevance() {}

    public static List<NewsItem> filterRelevant(String symbol, List<NewsItem> newsItems) {
        if (newsItems == null || newsItems.isEmpty()) {
            return List.of();
        }
        List<NewsItem> filtered = new ArrayList<>();
        for (NewsItem item : newsItems) {
            if (item == null) {
                continue;
            }
            if (isRelevant(symbol, item.title(), item.summary())) {
                filtered.add(item);
            }
        }
        if (!filtered.isEmpty()) {
            return filtered;
        }
        return newsItems.stream().limit(Math.min(5, newsItems.size())).toList();
    }

    public static boolean isRelevant(String symbol, String title, String summary) {
        String asset = baseAsset(symbol);
        String text = normalize(title, summary);
        if (containsAny(text, aliasesFor(asset))) {
            return true;
        }
        if (mentionsOtherAsset(text, asset)) {
            return false;
        }
        return containsAny(text, MACRO_KEYWORDS);
    }

    private static boolean mentionsOtherAsset(String text, String targetAsset) {
        for (var entry : ASSET_ALIASES.entrySet()) {
            if (entry.getKey().equals(targetAsset)) {
                continue;
            }
            if (containsAny(text, entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> aliasesFor(String asset) {
        return ASSET_ALIASES.getOrDefault(asset, List.of(" " + asset.toLowerCase(Locale.ROOT) + " "));
    }

    private static String baseAsset(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        String upper = symbol.toUpperCase(Locale.ROOT);
        for (String quote : List.of("USDT", "USDC", "BUSD", "FDUSD")) {
            if (upper.endsWith(quote) && upper.length() > quote.length()) {
                return upper.substring(0, upper.length() - quote.length());
            }
        }
        return upper;
    }

    private static String normalize(String title, String summary) {
        String combined = ((title == null ? "" : title) + " " + (summary == null ? "" : summary))
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        return " " + combined + " ";
    }

    private static Map<String, List<String>> createAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("BTC", List.of(" btc ", " bitcoin "));
        aliases.put("ETH", List.of(" eth ", " ethereum ", " ether "));
        aliases.put("PAXG", List.of(" paxg ", " pax gold "));
        aliases.put("SOL", List.of(" sol ", " solana "));
        aliases.put("XRP", List.of(" xrp ", " ripple "));
        aliases.put("BNB", List.of(" bnb ", " binance coin "));
        aliases.put("DOGE", List.of(" doge ", " dogecoin "));
        return aliases;
    }
}
