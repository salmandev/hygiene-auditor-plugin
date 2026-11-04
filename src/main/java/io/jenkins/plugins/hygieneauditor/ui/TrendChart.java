package io.jenkins.plugins.hygieneauditor.ui;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.jenkins.plugins.hygieneauditor.Messages;
import io.jenkins.plugins.hygieneauditor.store.ScanHistoryEntry;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the trend as a self-contained inline SVG: no JavaScript, no external library, themed through CSS classes
 * (see style.css), and described for screen readers with {@code <title>}/{@code <desc>}. The output is built only from
 * numbers, dates and localised static labels, never from finding content.
 */
public final class TrendChart {
    private static final int W = 640;
    private static final int H = 220;
    private static final int LEFT = 44;
    private static final int RIGHT = 12;
    private static final int TOP = 16;
    private static final int BOTTOM = 34;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private TrendChart() {}

    /** @return SVG markup, or an empty string if there are fewer than two scans to draw */
    @NonNull
    public static String render(@NonNull List<ScanHistoryEntry> history) {
        if (history.size() < 2) {
            return "";
        }
        List<ScanHistoryEntry> pts =
                history.size() > 120 ? history.subList(history.size() - 120, history.size()) : history;
        int max = 1;
        for (ScanHistoryEntry e : pts) {
            max = Math.max(max, e.totalOpen());
        }
        ScanHistoryEntry last = pts.get(pts.size() - 1);
        int lastHigh = last.openBySeverity().getOrDefault("HIGH", 0);

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<svg class=\"ha-trend\" role=\"img\" viewBox=\"0 0 ")
                .append(W)
                .append(' ')
                .append(H)
                .append("\" aria-labelledby=\"ha-trend-t ha-trend-d\">");
        sb.append("<title id=\"ha-trend-t\">").append(Messages.Trend_Title()).append("</title>");
        sb.append("<desc id=\"ha-trend-d\">")
                .append(Messages.Trend_Desc(pts.size(), last.totalOpen(), lastHigh))
                .append("</desc>");
        // axes
        int x0 = LEFT;
        int x1 = W - RIGHT;
        int yBase = H - BOTTOM;
        sb.append("<line class=\"ha-axis\" x1=\"")
                .append(x0)
                .append("\" y1=\"")
                .append(yBase)
                .append("\" x2=\"")
                .append(x1)
                .append("\" y2=\"")
                .append(yBase)
                .append("\"/>");
        sb.append("<line class=\"ha-axis\" x1=\"")
                .append(x0)
                .append("\" y1=\"")
                .append(TOP)
                .append("\" x2=\"")
                .append(x0)
                .append("\" y2=\"")
                .append(yBase)
                .append("\"/>");
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x0 - 6)
                .append("\" y=\"")
                .append(TOP + 4)
                .append("\" text-anchor=\"end\">")
                .append(max)
                .append("</text>");
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x0 - 6)
                .append("\" y=\"")
                .append(yBase)
                .append("\" text-anchor=\"end\">0</text>");
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x0)
                .append("\" y=\"")
                .append(H - 12)
                .append("\">")
                .append(DAY.format(pts.get(0).end()))
                .append("</text>");
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x1)
                .append("\" y=\"")
                .append(H - 12)
                .append("\" text-anchor=\"end\">")
                .append(DAY.format(last.end()))
                .append("</text>");
        // series
        sb.append(series("ha-line-total", pts, max, false));
        sb.append(series("ha-line-high", pts, max, true));
        // legend (text, so meaning does not depend on colour)
        sb.append("<text class=\"ha-label\" x=\"")
                .append(x0 + 8)
                .append("\" y=\"")
                .append(TOP + 4)
                .append("\">— ")
                .append(Messages.Trend_Total())
                .append("   ··· ")
                .append(Messages.Trend_High())
                .append("</text>");
        sb.append("</svg>");
        return sb.toString();
    }

    private static String series(String cls, List<ScanHistoryEntry> pts, int max, boolean high) {
        StringBuilder sb = new StringBuilder("<polyline class=\"").append(cls).append("\" fill=\"none\" points=\"");
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            ScanHistoryEntry e = pts.get(i);
            int v = high ? e.openBySeverity().getOrDefault("HIGH", 0) : e.totalOpen();
            double x = LEFT + (double) (W - RIGHT - LEFT) * i / (n - 1);
            double y = (H - BOTTOM) - (double) (H - BOTTOM - TOP) * v / max;
            sb.append(String.format(java.util.Locale.ROOT, "%.1f,%.1f ", x, y));
        }
        return sb.append("\"/>").toString();
    }
}
