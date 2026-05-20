package scripts.ufp;


import org.dreambot.api.input.Mouse;
import org.dreambot.api.methods.input.Camera;
import org.dreambot.api.methods.input.mouse.MouseSettings;
import org.dreambot.api.methods.tabs.Tab;
import org.dreambot.api.methods.tabs.Tabs;
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.utilities.Sleep;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AntiBan — progressive fatigue system for Universal Fighter Pro.
 *
 * Three layers:
 *   1. Micro antiban  — small random actions every few ticks (camera, tab checks)
 *   2. Break handler  — AFK breaks on a randomized interval
 *   3. Fatigue curve  — the longer the session runs, the more human-like degradation
 *
 * Usage:
 *   AntiBan antiBan = new AntiBan(config);
 *   antiBan.start();                    // call once in onStart()
 *   antiBan.processTick();              // call once per onLoop() tick
 *   boolean onBreak = antiBan.isOnBreak(); // if true, skip all bot logic this tick
 */
public class AntiBan {

    private final ScriptConfig config;

    // Session tracking
    private Instant sessionStart;
    private Instant lastBreakTime;
    private Instant breakEndTime;
    private int nextBreakIntervalMins;
    private boolean onBreak = false;

    // Micro antiban timing
    private Instant lastMicroAction = Instant.now();
    private int nextMicroActionSecs = randomInt(8, 25);

    // Fatigue tracking
    private double fatigueLevel = 0.0; // 0.0 = fresh, 1.0 = fully fatigued

    // Mouse speed — higher number = faster in DreamBot
    private static final int BASE_SPEED_MIN = 11;
    private static final int BASE_SPEED_MAX = 15;

    public AntiBan(ScriptConfig config) {
        this.config = config;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void start() {
        sessionStart      = Instant.now();
        lastBreakTime     = Instant.now();
        breakEndTime      = null;
        onBreak           = false;
        fatigueLevel      = 0.0;
        nextBreakIntervalMins = randomInt(config.minBreakMins, config.maxBreakMins);
        applyMouseSpeed();
        Logger.log("[AntiBan] Started. First break in ~" + nextBreakIntervalMins + " mins.");
    }

    // -----------------------------------------------------------------------
    // Main tick — call this once per onLoop()
    // Returns true if the script should skip its logic this tick (on break)
    // -----------------------------------------------------------------------

    public boolean processTick() {
        updateFatigue();

        // If we're mid-break, check if it's over
        if (onBreak) {
            if (Instant.now().isAfter(breakEndTime)) {
                onBreak = false;
                lastBreakTime = Instant.now();
                nextBreakIntervalMins = randomInt(config.minBreakMins, config.maxBreakMins);
                Logger.log("[AntiBan] Break over. Next break in ~" + nextBreakIntervalMins + " mins.");
                applyMouseSpeed();
            }
            return true; // still on break
        }

        // Check if it's time for a new break
        long minsSinceBreak = Duration.between(lastBreakTime, Instant.now()).toMinutes();
        if (minsSinceBreak >= nextBreakIntervalMins) {
            startBreak();
            return true;
        }

        // Run micro antiban actions
        long secsSinceLastMicro = Duration.between(lastMicroAction, Instant.now()).getSeconds();
        if (secsSinceLastMicro >= nextMicroActionSecs) {
            runMicroAction();
            lastMicroAction     = Instant.now();
            nextMicroActionSecs = randomInt(8, 25);
        }

        return false; // not on break, proceed normally
    }

    public boolean isOnBreak() {
        return onBreak;
    }

    // -----------------------------------------------------------------------
    // Fatigue curve
    // Fatigue grows over the session. At full fatigue:
    //   - Mouse speed slows down
    //   - Reaction delays increase
    //   - Micro actions happen more often (distracted player)
    // -----------------------------------------------------------------------

    private void updateFatigue() {
        if (!config.fatigueCurve) {
            fatigueLevel = 0.0;
            return;
        }
        long sessionMins = Duration.between(sessionStart, Instant.now()).toMinutes();
        // Fatigue ramps from 0 to 1 over 4 hours, caps at 1
        fatigueLevel = Math.min(1.0, sessionMins / 240.0);
    }

    /**
     * Returns a reaction delay in ms, scaled by fatigue.
     * Fresh: 150-400ms. Fatigued: 300-700ms.
     * Call this before every click/attack action.
     */
    public int getReactionDelay() {
        int min = (int) (150 + fatigueLevel * 150);
        int max = (int) (400 + fatigueLevel * 300);
        return randomInt(min, max);
    }

    /**
     * Returns true occasionally — use this to randomly skip an action
     * as if the player got briefly distracted. Scales with fatigue.
     */
    public boolean shouldMissClick() {
        // Fresh: 0.5% chance. Fatigued: 3% chance.
        double chance = 0.005 + fatigueLevel * 0.025;
        return Math.random() < chance;
    }

    // -----------------------------------------------------------------------
    // Break handler
    // -----------------------------------------------------------------------

    private void startBreak() {
        // Break duration scales slightly with fatigue — tired players take longer breaks
        int baseDuration = config.breakDurationSecs;
        int fatiguedExtra = (int) (fatigueLevel * 60); // up to 60 extra seconds
        int duration = randomInt(baseDuration, baseDuration + fatiguedExtra + 30);

        onBreak      = true;
        breakEndTime = Instant.now().plusSeconds(duration);
        Logger.log("[AntiBan] Taking a " + duration + "s break. Fatigue: " + (int)(fatigueLevel * 100) + "%");

        // Occasionally rotate camera or check a tab during the break
        // so it doesn't look like a frozen client
        new Thread(() -> {
            try {
                Thread.sleep(randomInt(3000, 10000));
                if (Math.random() < 0.4) randomCameraRotation();
                Thread.sleep(randomInt(5000, 15000));
                if (Math.random() < 0.3) glanceAtTab();
            } catch (InterruptedException ignored) {}
        }).start();
    }

    // -----------------------------------------------------------------------
    // Micro antiban actions — small human-like behaviours between fights
    // -----------------------------------------------------------------------

    private void runMicroAction() {
        double roll = Math.random();

        if (roll < 0.35) {
            // Most common: subtle camera nudge
            randomCameraRotation();
        } else if (roll < 0.55) {
            // Check inventory or skills tab
            glanceAtTab();
        } else if (roll < 0.65) {
            // Mouse drift — move mouse to a random screen position and back
            mouseDrift();
        } else if (roll < 0.75) {
            // Zoom in/out slightly
            randomZoom();
        }
        // ~25% chance: do nothing — just wait. Players zone out.
    }

    private void randomCameraRotation() {
        try {
            int yawDelta  = randomInt(-35, 35);
            int newPitch  = Math.max(128, Math.min(383, Camera.getPitch() + randomInt(-20, 20)));
            Camera.rotateTo(Camera.getYaw() + yawDelta, newPitch);
        } catch (Exception ignored) {}
    }

    private void glanceAtTab() {
        try {
            Tab[] glanceTabs = {Tab.INVENTORY, Tab.SKILLS, Tab.EQUIPMENT};
            Tab target = glanceTabs[randomInt(0, glanceTabs.length - 1)];
            Tab current = Tabs.getOpen();
            Tabs.open(target);
            Sleep.sleep(randomInt(600, 2200));
            if (current != null) Tabs.open(current);
        } catch (Exception ignored) {}
    }

    private void mouseDrift() {
        try {
            Mouse.move(new java.awt.Point(randomInt(50, 750), randomInt(50, 500)));
            Sleep.sleep(randomInt(200, 600));
        } catch (Exception ignored) {}
    }

    private void randomZoom() {
        try {
            // DreamBot zoom range is roughly 128-1008
            int current = Camera.getZoom();
            int newZoom = Math.max(128, Math.min(1008, current + randomInt(-15, 15)));
            Camera.setZoom(newZoom);
        } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Mouse speed — applied on start and after each break
    // Slower when fatigued
    // -----------------------------------------------------------------------

    private void applyMouseSpeed() {
        try {
            int min = (int) (BASE_SPEED_MIN + fatigueLevel * 3);
            int max = (int) (BASE_SPEED_MAX + fatigueLevel * 5);
            // MouseSettings.setMouseSpeed expects a value roughly 4-13
            // Higher = slower mouse movement
            MouseSettings.setSpeed(randomInt(min, max));
        } catch (Exception ignored) {
            // If the method signature differs in this build, skip silently
        }
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private int randomInt(int min, int max) {
        if (min >= max) return min;
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    public double getFatigueLevel() { return fatigueLevel; }
    public boolean isFatigueCurveEnabled() { return config.fatigueCurve; }
    public long getSessionMinutes() {
        return sessionStart == null ? 0 : Duration.between(sessionStart, Instant.now()).toMinutes();
    }
}