package scripts.ufp;

import org.dreambot.api.utilities.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * DiscordWebhook — sends status updates + screenshots to a Discord channel.
 *
 * Usage:
 *   DiscordWebhook webhook = new DiscordWebhook(config);
 *   webhook.start(screenshotProvider);   // call in onStart()
 *   webhook.stop();                      // call in onStop()
 *
 * The script passes a ScreenshotProvider lambda so this class
 * doesn't need to know anything about the DreamBot client directly.
 */
public class DiscordWebhook {

    public interface ScreenshotProvider {
        BufferedImage capture();
    }

    private final ScriptConfig config;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;
    private ScreenshotProvider screenshotProvider;
    private Instant sessionStart;

    // Stats updated by the main script each tick
    private volatile String currentStatus = "Starting...";
    private volatile long xpGained        = 0;
    private volatile int  killCount        = 0;

    public DiscordWebhook(ScriptConfig config) {
        this.config = config;
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    public void start(ScreenshotProvider provider) {
        if (config.webhookUrl == null || config.webhookUrl.trim().isEmpty()) {
            Logger.log("[Discord] No webhook URL set — notifications disabled.");
            return;
        }

        this.screenshotProvider = provider;
        this.sessionStart       = Instant.now();
        this.scheduler          = Executors.newSingleThreadScheduledExecutor();

        long intervalMs = config.webhookInterval * 60_000L;
        task = scheduler.scheduleAtFixedRate(
                this::sendUpdate,
                intervalMs,   // first send after one full interval
                intervalMs,
                TimeUnit.MILLISECONDS
        );

        Logger.log("[Discord] Webhook active. Sending updates every " + config.webhookInterval + " mins.");
    }

    public void stop() {
        if (task != null)      task.cancel(false);
        if (scheduler != null) scheduler.shutdownNow();
        // Send a final "session ended" message
        if (config.webhookUrl != null && !config.webhookUrl.trim().isEmpty()) {
            sendFinalMessage();
        }
    }

    // -----------------------------------------------------------------------
    // Called by the main script to keep stats current
    // -----------------------------------------------------------------------

    public void updateStatus(String status)  { this.currentStatus = status; }
    public void incrementKills()             { this.killCount++;             }
    public void addXp(long xp)              { this.xpGained += xp;         }

    // -----------------------------------------------------------------------
    // Send a status update with screenshot
    // -----------------------------------------------------------------------

    private void sendUpdate() {
        try {
            String runtime  = formatDuration(Duration.between(sessionStart, Instant.now()));
            String message  = buildMessage(runtime, false);
            BufferedImage screenshot = screenshotProvider != null ? screenshotProvider.capture() : null;

            if (screenshot != null) {
                sendWithScreenshot(message, screenshot);
            } else {
                sendTextOnly(message);
            }
        } catch (Exception e) {
            Logger.log("[Discord] Failed to send update: " + e.getMessage());
        }
    }

    private void sendFinalMessage() {
        try {
            String runtime = formatDuration(Duration.between(sessionStart, Instant.now()));
            sendTextOnly(buildMessage(runtime, true));
        } catch (Exception e) {
            Logger.log("[Discord] Failed to send final message: " + e.getMessage());
        }
    }

    private String buildMessage(String runtime, boolean isFinal) {
        StringBuilder sb = new StringBuilder();
        if (isFinal) {
            sb.append("**Universal Fighter Pro — Session Ended**\n");
        } else {
            sb.append("**Universal Fighter Pro — Status Update**\n");
        }
        sb.append("```\n");
        sb.append("Status  : ").append(currentStatus).append("\n");
        sb.append("Runtime : ").append(runtime).append("\n");
        sb.append("Kills   : ").append(killCount).append("\n");
        sb.append("XP      : ").append(String.format("%,d", xpGained)).append("\n");
        sb.append("```");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // HTTP — multipart form for screenshot, plain JSON for text-only
    // Uses only java.net — no external libraries needed
    // -----------------------------------------------------------------------

    private void sendWithScreenshot(String message, BufferedImage image) throws IOException {
        String boundary = "----WebhookBoundary" + System.currentTimeMillis();
        URL url = new URL(config.webhookUrl.trim());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        // Convert image to PNG bytes
        ByteArrayOutputStream imgBytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", imgBytes);

        try (OutputStream out = conn.getOutputStream()) {
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);

            // Text part
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"content\"").append("\r\n\r\n");
            writer.append(message).append("\r\n");

            // Screenshot part
            writer.append("--").append(boundary).append("\r\n");
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"screenshot.png\"").append("\r\n");
            writer.append("Content-Type: image/png").append("\r\n\r\n");
            writer.flush();
            out.write(imgBytes.toByteArray());
            out.flush();

            writer.append("\r\n--").append(boundary).append("--").append("\r\n");
            writer.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 204) {
            Logger.log("[Discord] Unexpected response: " + responseCode);
        }
        conn.disconnect();
    }

    private void sendTextOnly(String message) throws IOException {
        String json = "{\"content\": " + jsonEscape(message) + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        URL url = new URL(config.webhookUrl.trim());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Content-Length", String.valueOf(body.length));
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);

        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200 && responseCode != 204) {
            Logger.log("[Discord] Unexpected response: " + responseCode);
        }
        conn.disconnect();
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private String jsonEscape(String text) {
        // Wrap in quotes and escape special characters for JSON
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }

    private String formatDuration(Duration d) {
        long hours   = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}