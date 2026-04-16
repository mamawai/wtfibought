package com.mawai.wiibservice.agent.quant.chart;

import com.alibaba.fastjson2.JSONArray;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * K线图生成器 — 将Binance OHLCV数据绘制为蜡烛图PNG。
 * 纯Java Graphics2D实现，零外部依赖。
 * 输出800×500px的PNG图片，包含蜡烛+EMA均线+成交量。
 */
@Slf4j
public class ChartImageGenerator {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 500;
    private static final int CHART_TOP = 30;
    private static final int CHART_BOTTOM = 380;  // 价格区域底部
    private static final int VOL_TOP = 400;        // 成交量区域顶部
    private static final int VOL_BOTTOM = 480;
    private static final int MARGIN_LEFT = 10;
    private static final int MARGIN_RIGHT = 70;    // 右侧留给价格标签

    private static final Color BG_COLOR = new Color(22, 26, 37);
    private static final Color GRID_COLOR = new Color(50, 55, 70);
    private static final Color TEXT_COLOR = new Color(180, 185, 195);
    private static final Color BULL_COLOR = new Color(38, 166, 91);   // 涨=绿
    private static final Color BEAR_COLOR = new Color(234, 57, 67);   // 跌=红
    private static final Color EMA5_COLOR = new Color(255, 193, 7);   // EMA5=黄
    private static final Color EMA20_COLOR = new Color(33, 150, 243); // EMA20=蓝
    private static final Color EMA60_COLOR = new Color(156, 39, 176); // EMA60=紫

    /**
     * 从Binance K线JSON生成蜡烛图PNG。
     *
     * @param klineJson Binance API返回的K线JSON字符串 [[openTime,O,H,L,C,volume,...],...]
     * @param symbol    交易对名称
     * @param interval  时间周期（如"1h","4h"）
     * @return PNG图片字节数组，失败返回null
     */
    public static byte[] generate(String klineJson, String symbol, String interval) {
        if (klineJson == null || klineJson.isBlank()) return null;

        try {
            List<Candle> candles = parseKlines(klineJson);
            if (candles.size() < 10) {
                log.warn("[Chart] K线数量不足: {}", candles.size());
                return null;
            }

            // 最多取最近168根（1h×7天=168）
            if (candles.size() > 168) {
                candles = candles.subList(candles.size() - 168, candles.size());
            }

            double[] ema5 = calcEma(candles, 5);
            double[] ema20 = calcEma(candles, 20);
            double[] ema60 = calcEma(candles, 60);

            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            drawBackground(g);
            drawTitle(g, symbol, interval);

            // 计算价格范围
            double minPrice = Double.MAX_VALUE, maxPrice = Double.MIN_VALUE;
            double maxVol = 0;
            for (Candle c : candles) {
                minPrice = Math.min(minPrice, c.low);
                maxPrice = Math.max(maxPrice, c.high);
                maxVol = Math.max(maxVol, c.volume);
            }
            double pricePad = (maxPrice - minPrice) * 0.05;
            minPrice -= pricePad;
            maxPrice += pricePad;

            drawGrid(g, minPrice, maxPrice);
            drawCandles(g, candles, minPrice, maxPrice, maxVol);
            drawEma(g, candles, ema5, minPrice, maxPrice, EMA5_COLOR);
            drawEma(g, candles, ema20, minPrice, maxPrice, EMA20_COLOR);
            drawEma(g, candles, ema60, minPrice, maxPrice, EMA60_COLOR);
            drawPriceLabels(g, minPrice, maxPrice);
            drawLegend(g);

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            byte[] result = baos.toByteArray();
            log.info("[Chart] 生成完成 {} {} candles={} size={}bytes", symbol, interval, candles.size(), result.length);
            return result;
        } catch (Exception e) {
            log.warn("[Chart] 生成失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== K线解析 ====================

    record Candle(long time, double open, double high, double low, double close, double volume) {}

    private static List<Candle> parseKlines(String json) {
        JSONArray arr = JSONArray.parseArray(json);
        List<Candle> candles = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JSONArray k = arr.getJSONArray(i);
            if (k.size() < 6) continue;
            candles.add(new Candle(
                    k.getLongValue(0),
                    k.getDoubleValue(1),
                    k.getDoubleValue(2),
                    k.getDoubleValue(3),
                    k.getDoubleValue(4),
                    k.getDoubleValue(5)
            ));
        }
        return candles;
    }

    // ==================== EMA计算 ====================

    private static double[] calcEma(List<Candle> candles, int period) {
        double[] ema = new double[candles.size()];
        if (candles.size() < period) return ema;

        double sum = 0;
        for (int i = 0; i < period; i++) sum += candles.get(i).close;
        ema[period - 1] = sum / period;

        double mult = 2.0 / (period + 1);
        for (int i = period; i < candles.size(); i++) {
            ema[i] = (candles.get(i).close - ema[i - 1]) * mult + ema[i - 1];
        }
        return ema;
    }

    // ==================== 绘制 ====================

    private static void drawBackground(Graphics2D g) {
        g.setColor(BG_COLOR);
        g.fillRect(0, 0, WIDTH, HEIGHT);
    }

    private static void drawTitle(Graphics2D g, String symbol, String interval) {
        g.setColor(TEXT_COLOR);
        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.drawString(symbol + " " + interval.toUpperCase(), MARGIN_LEFT + 5, 20);
    }

    private static void drawGrid(Graphics2D g, double minPrice, double maxPrice) {
        g.setColor(GRID_COLOR);
        g.setStroke(new BasicStroke(0.5f));
        int gridLines = 5;
        for (int i = 0; i <= gridLines; i++) {
            int y = CHART_TOP + (CHART_BOTTOM - CHART_TOP) * i / gridLines;
            g.drawLine(MARGIN_LEFT, y, WIDTH - MARGIN_RIGHT, y);
        }
    }

    private static void drawPriceLabels(Graphics2D g, double minPrice, double maxPrice) {
        g.setColor(TEXT_COLOR);
        g.setFont(new Font("Arial", Font.PLAIN, 10));
        int gridLines = 5;
        for (int i = 0; i <= gridLines; i++) {
            int y = CHART_TOP + (CHART_BOTTOM - CHART_TOP) * i / gridLines;
            double price = maxPrice - (maxPrice - minPrice) * i / gridLines;
            String label = formatPriceLabel(price);
            g.drawString(label, WIDTH - MARGIN_RIGHT + 5, y + 4);
        }
    }

    private static void drawCandles(Graphics2D g, List<Candle> candles,
                                     double minPrice, double maxPrice, double maxVol) {
        int chartWidth = WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        int n = candles.size();
        double candleWidth = Math.max(1, (double) chartWidth / n * 0.7);
        double gap = (double) chartWidth / n;

        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            boolean isBull = c.close >= c.open;
            Color color = isBull ? BULL_COLOR : BEAR_COLOR;

            double x = MARGIN_LEFT + i * gap + gap * 0.15;
            int priceChartHeight = CHART_BOTTOM - CHART_TOP;

            // 蜡烛实体
            int bodyTop = priceToY(Math.max(c.open, c.close), minPrice, maxPrice, priceChartHeight);
            int bodyBottom = priceToY(Math.min(c.open, c.close), minPrice, maxPrice, priceChartHeight);
            int bodyHeight = Math.max(1, bodyBottom - bodyTop);

            // 影线
            int wickTop = priceToY(c.high, minPrice, maxPrice, priceChartHeight);
            int wickBottom = priceToY(c.low, minPrice, maxPrice, priceChartHeight);
            int wickX = (int) (x + candleWidth / 2);

            g.setColor(color);
            g.setStroke(new BasicStroke(1));
            g.drawLine(wickX, wickTop, wickX, wickBottom);

            if (candleWidth >= 2) {
                if (isBull) {
                    g.drawRect((int) x, bodyTop, (int) candleWidth, bodyHeight);
                } else {
                    g.fillRect((int) x, bodyTop, (int) candleWidth, bodyHeight);
                }
            } else {
                g.drawLine((int) x, bodyTop, (int) x, bodyBottom);
            }

            // 成交量柱
            if (maxVol > 0) {
                int volHeight = (int) ((c.volume / maxVol) * (VOL_BOTTOM - VOL_TOP));
                Color volColor = isBull
                        ? new Color(38, 166, 91, 100)
                        : new Color(234, 57, 67, 100);
                g.setColor(volColor);
                g.fillRect((int) x, VOL_BOTTOM - volHeight, Math.max(1, (int) candleWidth), volHeight);
            }
        }
    }

    private static void drawEma(Graphics2D g, List<Candle> candles, double[] ema,
                                 double minPrice, double maxPrice, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f));
        int chartWidth = WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
        int n = candles.size();
        double gap = (double) chartWidth / n;
        int priceChartHeight = CHART_BOTTOM - CHART_TOP;

        int prevX = -1, prevY = -1;
        for (int i = 0; i < n; i++) {
            if (ema[i] == 0) continue;
            int x = (int) (MARGIN_LEFT + i * gap + gap * 0.5);
            int y = priceToY(ema[i], minPrice, maxPrice, priceChartHeight);
            if (prevX >= 0) {
                g.drawLine(prevX, prevY, x, y);
            }
            prevX = x;
            prevY = y;
        }
    }

    private static void drawLegend(Graphics2D g) {
        g.setFont(new Font("Arial", Font.PLAIN, 10));
        int x = 200;
        int y = 18;

        g.setColor(EMA5_COLOR);
        g.fillRect(x, y - 8, 12, 3);
        g.setColor(TEXT_COLOR);
        g.drawString("EMA5", x + 15, y);

        g.setColor(EMA20_COLOR);
        g.fillRect(x + 60, y - 8, 12, 3);
        g.setColor(TEXT_COLOR);
        g.drawString("EMA20", x + 75, y);

        g.setColor(EMA60_COLOR);
        g.fillRect(x + 130, y - 8, 12, 3);
        g.setColor(TEXT_COLOR);
        g.drawString("EMA60", x + 145, y);
    }

    // ==================== 工具 ====================

    private static int priceToY(double price, double minPrice, double maxPrice, int chartHeight) {
        double ratio = (price - minPrice) / (maxPrice - minPrice);
        return CHART_TOP + (int) ((1 - ratio) * chartHeight);
    }

    private static String formatPriceLabel(double price) {
        if (price >= 10000) return String.format("%.0f", price);
        if (price >= 100) return String.format("%.1f", price);
        if (price >= 1) return String.format("%.2f", price);
        return String.format("%.4f", price);
    }
}
