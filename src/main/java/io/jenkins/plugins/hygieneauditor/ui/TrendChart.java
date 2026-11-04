package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.store.ScanHistoryEntry;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders the trend as a self-contained inline SVG: no JavaScript, no external library, themed through CSS classes
 * (see style.css), and described for screen readers with {@code <title>}/{@code <desc>}. The output is built only from
 * numbers, dates and localised static labels, never from finding content.
 *
 * <p>Two series: all open findings (filled area) and high-severity findings (dotted), with the latest value of each
 * marked. The full variant adds gridlines, y-axis ticks and dates; the compact one is for a side card. The legend is
 * HTML, next to the chart.
 */
public final class TrendChart {
    /** Geometry of one rendering. */
    private record Dims(int w, int h, int left, int right, int top, int bottom, boolean ticks) {}

    private static final Dims FULL = new Dims(720, 260, 40, 56, 14, 34, true);
    private static final Dims COMPACT = new Dims(360, 150, 8, 40, 10, 26, false);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private TrendChart() {}

    /** @return SVG markup, or an empty string if there are fewer than two scans to draw */
    @NonNull
    public static String render(@NonNull List<ScanHistoryEntry> history) {
        return render(history, false);
    }

    /** @param compact a small variant for a side card: no gridline labels, same data */
    @NonNull
    public static String render(@NonNull List<ScanHistoryEntry> history, boolean compact) {
        if (history.size() < 2) {
            return "";
        }
        Dims d = compact ? COMPACT : FULL;
        List<ScanHistoryEntry> pts =
                history.size() > 120 ? history.subList(history.size() - 120, history.size()) : history;
        int max = 1;
        for (ScanHistoryEntry e : pts) {
            max = Math.max(max, e.totalOpen());
        }
        int top = niceCeiling(max);
        ScanHistoryEntry last = pts.get(pts.size() - 1);
        int lastTotal = last.totalOpen();
        int lastHigh = last.openBySeverity().getOrDefault("HIGH", 0);
        int x0 = d.left();
        int x1 = d.w() - d.right();
        int yBase = d.h() - d.bottom();

        StringBuilder sb = new StringBuilder(3072);
        sb.append("<svg class=\"ha-trend")
                .append(compact ? " ha-trend-compact" : "")
                .append("\" role=\"img\" viewBox=\"0 0 ")
                .append(d.w())
                .append(' ')
                .append(d.h())
                .append("\" aria-labelledby=\"ha-trend-t ha-trend-d\">");
        sb.append("<title id=\"ha-trend-t\">").append(Messages.Trend_Title()).append("</title>");
        sb.append("<desc id=\"ha-trend-d\">")
                .append(Messages.Trend_Desc(pts.size(), lastTotal, lastHigh))
                .append("</desc>");
        for (int i = 0; i <= 2; i++) { // baseline plus, in the full chart, two dashed gridlines with their values
            int v = top * i / 2;
            if (i > 0 && !d.ticks()) {
                continue;
            }
            double y = yOf(d, v, top);
            sb.append("<line class=\"")
                    .append(i == 0 ? "ha-axis" : "ha-grid")
                    .append("\" x1=\"")
                    .append(x0)
                    .append("\" y1=\"")
                    .append(fmt(y))
                    .append("\" x2=\"")
                    .append(x1)
                    .append("\" y2=\"")
                    .append(fmt(y))
                    .append("\"/>");
            if (d.ticks()) {
                sb.append("<text class=\"ha-label\" x=\"")
                        .append(x0 - 8)
                        .append("\" y=\"")
                        .append(fmt(y + 4))
                        .append("\" text-anchor=\"end\">")
                        .append(v)
                        .append("</text>");
            }
        }
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x0)
                .append("\" y=\"")
                .append(d.h() - 8)
                .append("\">")
                .append(DAY.format(pts.get(0).end()))
                .append("</text>");
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x1)
                .append("\" y=\"")
                .append(d.h() - 8)
                .append("\" text-anchor=\"end\">")
                .append(DAY.format(last.end()))
                .append("</text>");
        sb.append("<polygon class=\"ha-area\" points=\"")
                .append(fmt(x0))
                .append(',')
                .append(yBase)
                .append(' ')
                .append(points(d, pts, top, false))
                .append(fmt(x1))
                .append(',')
                .append(yBase)
                .append("\"/>");
        sb.append("<polyline class=\"ha-line-total\" fill=\"none\" points=\"")
                .append(points(d, pts, top, false))
                .append("\"/>");
        sb.append("<polyline class=\"ha-line-high\" fill=\"none\" points=\"")
                .append(points(d, pts, top, true))
                .append("\"/>");
        double yTotal = yOf(d, lastTotal, top);
        double yHigh = yOf(d, lastHigh, top);
        sb.append("<circle class=\"ha-dot-total\" cx=\"")
                .append(x1)
                .append("\" cy=\"")
                .append(fmt(yTotal))
                .append("\" r=\"4\"/>");
        sb.append("<text class=\"ha-value\" x=\"")
                .append(x1 + 9)
                .append("\" y=\"")
                .append(fmt(yTotal + 4))
                .append("\">")
                .append(lastTotal)
                .append("</text>");
        sb.append("<circle class=\"ha-dot-high\" cx=\"")
                .append(x1)
                .append("\" cy=\"")
                .append(fmt(yHigh))
                .append("\" r=\"4\"/>");
        if (Math.abs(yHigh - yTotal) > 14) { // do not print two numbers on top of each other
            sb.append("<text class=\"ha-value ha-value-high\" x=\"")
                    .append(x1 + 9)
                    .append("\" y=\"")
                    .append(fmt(yHigh + 4))
                    .append("\">")
                    .append(lastHigh)
                    .append("</text>");
        }
        sb.append("</svg>");
        return sb.toString();
    }

    /** The smallest "round" axis maximum at or above {@code max}, even so the midline is a whole number. */
    static int niceCeiling(int max) {
        if (max <= 2) {
            return 2;
        }
        int pow = 1;
        while (pow * 10 <= max) {
            pow *= 10;
        }
        for (int step : new int[] {1, 2, 4, 6, 8, 10}) {
            if (step * pow >= max && (step * pow) % 2 == 0) {
                return step * pow;
            }
        }
        return 10 * pow;
    }

    private static double yOf(Dims d, int value, int top) {
        return (d.h() - d.bottom()) - (double) (d.h() - d.bottom() - d.top()) * value / top;
    }

    private static String points(Dims d, List<ScanHistoryEntry> pts, int top, boolean high) {
        StringBuilder sb = new StringBuilder();
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            ScanHistoryEntry e = pts.get(i);
            int v = high ? e.openBySeverity().getOrDefault("HIGH", 0) : e.totalOpen();
            double x = d.left() + (double) (d.w() - d.right() - d.left()) * i / (n - 1);
            sb.append(fmt(x)).append(',').append(fmt(yOf(d, v, top))).append(' ');
        }
        return sb.toString();
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }
}
