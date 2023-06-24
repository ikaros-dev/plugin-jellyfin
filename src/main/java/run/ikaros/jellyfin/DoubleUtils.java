package run.ikaros.jellyfin;

public class DoubleUtils {

    public static boolean isInt(double num) {
        return Math.abs(num - Math.round(num)) < Double.MIN_VALUE;
    }

    public static int castInt(double num) {
        return (int) num;
    }
}
