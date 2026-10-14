package services.quota;

public final class QuotaRules {
    private QuotaRules() { }
    public enum Level { UNKNOWN, LOW, WARNING, NORMAL }
    public static double remaining(double used) { return Double.isNaN(used)?Double.NaN:Math.max(0,100-used); }
    public static Level level(double remaining) {
        if(Double.isNaN(remaining)) return Level.UNKNOWN;
        if(remaining<=10) return Level.LOW;
        return remaining<=30?Level.WARNING:Level.NORMAL;
    }
    public static int progress(double remaining) { return (int)Math.max(0,Math.min(1000,remaining*10)); }
    public static boolean days(long seconds) { return seconds>=86400; }
    public static long duration(long seconds) { return seconds/(days(seconds)?86400:3600); }
}
