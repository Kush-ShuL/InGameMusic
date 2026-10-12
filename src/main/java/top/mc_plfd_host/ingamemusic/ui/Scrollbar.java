package top.mc_plfd_host.ingamemusic.ui;

/** Track geometry accepts pixel extents, including partially visible rows. */
public record Scrollbar(double top, double height, double count, double visible, double offset) {
    public double maximum() { return Math.max(0, count - visible); }
    public double thumbHeight() { return count <= visible ? height : Math.min(height, Math.max(24, height * visible / count)); }
    public double thumbTop() { return top + (height - thumbHeight()) * Math.clamp(offset, 0, maximum()) / Math.max(1, maximum()); }
    public double offsetAt(double pointer, double grabOffset) {
        double travel = height - thumbHeight();
        return travel <= 0 ? 0 : Math.clamp((pointer - top - grabOffset) / travel * maximum(), 0, maximum());
    }
}
