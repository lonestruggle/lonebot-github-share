package net.storm.sdk.input;

/**
 * Runtime muis-instellingen (snelheid, random idle). Paneel/config schrijft hierheen.
 */
public final class MouseSettings {

    private MouseSettings() {
    }

    /** 0.25 = traag, 1.0 = normaal, 2.5 = snel (deelt step-delay). */
    private static volatile double speed = 1.0;
    private static volatile boolean randomMoveEnabled = true;

    /**
     * Hard policy: never activate/focus the game window (no Robot, no requestFocus).
     * Always on — bot must work in background like Storm synthetic input.
     */
    private static volatile boolean neverStealFocus = true;

    public static void setSpeed(double speedMultiplier) {
        if (speedMultiplier < 0.2) {
            speedMultiplier = 0.2;
        }
        if (speedMultiplier > 3.0) {
            speedMultiplier = 3.0;
        }
        speed = speedMultiplier;
    }

    /** Percent 25–300 → multiplier. */
    public static void setSpeedPercent(int percent) {
        setSpeed(Math.max(25, Math.min(300, percent)) / 100.0);
    }

    public static double getSpeed() {
        return speed;
    }

    public static int getSpeedPercent() {
        return (int) Math.round(speed * 100.0);
    }

    public static void setRandomMoveEnabled(boolean enabled) {
        randomMoveEnabled = enabled;
    }

    public static boolean isRandomMoveEnabled() {
        return randomMoveEnabled;
    }

    public static boolean neverStealFocus() {
        return neverStealFocus;
    }

    /** Reserved — always forced true for now (user requirement). */
    public static void setNeverStealFocus(boolean enabled) {
        neverStealFocus = true; // hard lock
    }

    /** Hogere speed → kortere delays. */
    public static int scaleDelay(int baseMs) {
        return Math.max(2, (int) Math.round(baseMs / speed));
    }

    /**
     * Terug naar minimap/walk-klik: minstens 2× normaal, of de paneel-snelheid als die hoger is.
     */
    public static int scaleDelayWalkReturn(int baseMs) {
        double s = Math.max(speed, 2.0);
        return Math.max(1, (int) Math.round(baseMs / s));
    }
}
