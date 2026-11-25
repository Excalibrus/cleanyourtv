package local.dailywallpaper;

/**
 * How much Home darkens a picture so its white text stays readable: measured once per picture, from a small
 * sample of it. No Android types, so it is unit-tested.
 */
final class Brightness {
    static final int MIN_DIM = 20;
    static final int MAX_DIM = 55;
    /** At or below this mean brightness (0 to 1) the picture gets {@link #MIN_DIM}. */
    static final double DARK = 0.25;
    /** At or above this one it gets {@link #MAX_DIM}. */
    static final double BRIGHT = 0.65;

    private Brightness() {
    }

    /** The mean brightness, 0 to 1, of ARGB pixels: Rec. 709 weights on the 8-bit values. */
    static double mean(int[] pixels) {
        if (pixels == null || pixels.length == 0) return 0;
        double sum = 0;
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            sum += 0.2126 * r + 0.7152 * g + 0.0722 * b;
        }
        return sum / pixels.length / 255.0;
    }

    /** The dim in percent for a mean brightness: 20 for a dark picture, 55 for a bright one, a straight line between. */
    static int dimFor(double mean) {
        if (Double.isNaN(mean) || mean <= DARK) return MIN_DIM;
        if (mean >= BRIGHT) return MAX_DIM;
        return (int) Math.round(MIN_DIM + (mean - DARK) / (BRIGHT - DARK) * (MAX_DIM - MIN_DIM));
    }
}
