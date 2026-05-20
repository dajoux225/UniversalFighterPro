package scripts.ufp;

import org.dreambot.api.methods.combat.Combat;
import org.dreambot.api.methods.container.impl.Inventory;
import org.dreambot.api.methods.world.World;
import org.dreambot.api.methods.world.Worlds;
import org.dreambot.api.methods.worldhopper.WorldHopper;
import org.dreambot.api.script.listener.ChatListener;
import org.dreambot.api.script.listener.HumanMouseListener;
import org.dreambot.api.methods.container.impl.bank.Bank;
import org.dreambot.api.methods.item.GroundItems;
import org.dreambot.api.methods.interactive.NPCs;
import org.dreambot.api.methods.interactive.Players;
import org.dreambot.api.methods.map.Tile;
import org.dreambot.api.methods.skills.Skill;
import org.dreambot.api.methods.skills.Skills;
import org.dreambot.api.methods.walking.impl.Walking;
import org.dreambot.api.script.AbstractScript;
import org.dreambot.api.script.Category;
import org.dreambot.api.script.ScriptManifest;
import org.dreambot.api.utilities.Logger;
import org.dreambot.api.utilities.Sleep;
import org.dreambot.api.wrappers.interactive.NPC;
import org.dreambot.api.wrappers.interactive.Player;
import org.dreambot.api.wrappers.items.GroundItem;
import org.dreambot.api.wrappers.items.Item;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@ScriptManifest(
        name        = "Universal Fighter Pro",
        description = "Universal combat trainer with banking, antiban, loot filter & Discord notifications",
        author      = "dajoux",
        version     = 1.1,
        category    = Category.COMBAT
)
public class UniversalFighter extends AbstractScript implements HumanMouseListener, ChatListener {

    // -----------------------------------------------------------------------
    // State machine
    // -----------------------------------------------------------------------
    private enum State {
        HEALING, BANKING, LOOTING, BURYING, WALKING, FIGHTING, WAITING
    }

    // -----------------------------------------------------------------------
    // Components
    // -----------------------------------------------------------------------
    private ScriptConfig   config;
    private AntiBan        antiBan;
    private DiscordWebhook discord;

    // -----------------------------------------------------------------------
    // Tracking
    // -----------------------------------------------------------------------
    private int    startXp       = 0;
    private int    killCount     = 0;
    private long   startTime     = 0;
    private String statusMessage = "Starting...";

    private final Set<Integer> unreachableNpcs = new HashSet<>();
    private Integer currentAttemptedNpcIndex   = null;

    // Settings button bounds — tracked so click detection matches what's drawn
    // Stuck detection
    private Tile lastStuckCheckTile  = null;
    private long lastMovedTime       = 0;
    private long lastCantReachTime   = 0;
    private final int STUCK_THRESHOLD_MS     = 4000;
    private final int CANT_REACH_COOLDOWN_MS = 2000;

    private final java.awt.Rectangle settingsBtnBounds = new java.awt.Rectangle(10, 228, 110, 20);

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onStart() {
        final boolean[] confirmed = {false};

        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                config = new ScriptConfig();
                JFrame frame = new JFrame("Universal Fighter Pro — Setup");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setResizable(false);

                JScrollPane scroll = new JScrollPane(config);
                scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

                JButton startBtn = new JButton("Start Script");
                startBtn.addActionListener(e -> {
                    if (config.confirm()) {
                        confirmed[0] = true;
                        frame.dispose();
                    }
                });

                frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        confirmed[0] = true;
                    }
                });

                frame.setLayout(new BorderLayout());
                frame.add(scroll, BorderLayout.CENTER);
                frame.add(startBtn, BorderLayout.SOUTH);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        } catch (Exception e) {
            Logger.log("[UFP] GUI failed to launch: " + e.getMessage());
            stop();
            return;
        }

        // Block script thread until buyer hits Start
        while (!confirmed[0]) Sleep.sleep(100);

        antiBan = new AntiBan(config);
        antiBan.start();

        discord = new DiscordWebhook(config);
        discord.start(this::captureScreenshot);

        startXp   = getTotalXp();
        startTime = System.currentTimeMillis();
        lastMovedTime = System.currentTimeMillis();

        // Enable obstacle handling
        Walking.setObstacleSleeping(true);

        // Make sure auto-retaliate is on so we fight back when hit
        if (!Combat.isAutoRetaliateOn()) {
            Combat.toggleAutoRetaliate(true);
        }

        // Set initial combat style
        Combat.setCombatStyle(config.combatStyle);

        Logger.log("[UFP] Started — " + config.npcName
                + " @ (" + config.centerX + "," + config.centerY + ") r=" + config.radius);
    }

    @Override
    public void onExit() {
        if (discord != null) discord.stop();
        Logger.log("[UFP] Stopped. Kills: " + killCount + " | XP: " + getTotalXpGained());
    }

    // -----------------------------------------------------------------------
    // Main loop
    // -----------------------------------------------------------------------

    @Override
    public int onLoop() {
        // Guard against onLoop firing before onStart completes
        if (antiBan == null || config == null) return 600;
        if (antiBan.processTick()) {
            setStatus("Antiban: Taking a break...");
            return 600;
        }

        // Reapply combat style if it changed (e.g. player manually switched)
        if (config != null && Combat.getCombatStyle() != config.combatStyle) {
            Combat.setCombatStyle(config.combatStyle);
        }

        // Stuck detection — if we haven't moved in STUCK_THRESHOLD_MS and aren't in combat, force walk
        if (!isInCombat()) {
            Player localPlayer = Players.getLocal();
            if (localPlayer != null) {
                Tile currentTile = localPlayer.getTile();
                if (lastStuckCheckTile == null || !currentTile.equals(lastStuckCheckTile)) {
                    lastStuckCheckTile = currentTile;
                    lastMovedTime = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastMovedTime > STUCK_THRESHOLD_MS) {
                    Logger.log("[UFP] Stuck detected — forcing walk to center. Possible obstacle.");
                    setStatus("Stuck! Opening obstacle...");
                    Walking.walk(new Tile(config.centerX, config.centerY));
                    lastMovedTime = System.currentTimeMillis();
                    Sleep.sleep(1500, 2500);
                }
            }
        }

        // Schedule check — stop if conditions met
        if (config.scheduleEnabled) {
            if (config.scheduleByTime) {
                long totalMins = config.scheduleHours * 60L + config.scheduleMins;
                long elapsedMins = (System.currentTimeMillis() - startTime) / 60_000;
                if (elapsedMins >= totalMins) {
                    Logger.log("[UFP] Schedule: time limit reached. Stopping.");
                    stop();
                    return 600;
                }
            }
            if (config.scheduleByLevel && config.scheduleSkill != null) {
                Skill skill = getSkillByName(config.scheduleSkill);
                if (skill != null && Skills.getRealLevel(skill) >= config.scheduleTargetLevel) {
                    Logger.log("[UFP] Schedule: " + config.scheduleSkill
                            + " reached level " + config.scheduleTargetLevel + ". Stopping.");
                    stop();
                    return 600;
                }
            }
        }

        discord.updateStatus(statusMessage);

        switch (getState()) {
            case HEALING:  return doHealing();
            case BANKING:  return doBanking();
            case LOOTING:  return doLooting();
            case BURYING:  return doBurying();
            case WALKING:  return doWalking();
            case FIGHTING: return doFighting();
            default:
                setStatus("Waiting...");
                return 600;
        }
    }

    // -----------------------------------------------------------------------
    // State resolver
    // -----------------------------------------------------------------------

    private State getState() {
        if (needsHealing())                              return State.HEALING;
        if (config.bankingEnabled && needsBanking())     return State.BANKING;
        if (hasLootNearby())                             return State.LOOTING;
        if (config.buryBones && hasBoneNearby())         return State.BURYING;
        if (isTooFarFromArea())                          return State.WALKING;
        return State.FIGHTING;
    }

    // -----------------------------------------------------------------------
    // State handlers
    // -----------------------------------------------------------------------

    private int doHealing() {
        setStatus("HP critical — eating");

        // Edgeville monks special case
        if (config.npcName.equalsIgnoreCase("Monk")) {
            NPC monk = NPCs.closest("Monk of Zamorak");
            if (monk == null) monk = NPCs.closest("Monk");
            if (monk != null) {
                monk.interact("Talk-to");
                Sleep.sleepUntil(() -> getHpPercent() >= config.eatThreshold, 5000);
                return 600;
            }
        }

        Item food = Inventory.get(i ->
                i != null && i.getName().toLowerCase()
                        .contains(config.foodName.toLowerCase()));

        if (food != null) {
            food.interact("Eat");
            Sleep.sleepUntil(() -> getHpPercent() >= config.eatThreshold, 3000);
            return 600;
        }

        if (config.logoutOnEmpty) {
            setStatus("Out of food — stopping");
            Logger.log("[UFP] No food. Stopping.");
            stop();
        }
        return 600;
    }

    private int doBanking() {
        setStatus("Banking...");

        if (!Bank.isOpen()) {
            if (!Bank.open()) {
                Walking.walk(Bank.getClosestBankLocation());
                return 1200;
            }
            Sleep.sleepUntil(Bank::isOpen, 5000);
            return 600;
        }

        // Deposit everything except food
        Bank.depositAllExcept(i ->
                i != null && i.getName().toLowerCase()
                        .contains(config.foodName.toLowerCase()));
        Sleep.sleep(400, 700);

        // Check bank has food
        if (!Bank.contains(config.foodName)) {
            setStatus("No food in bank — stopping");
            Logger.log("[UFP] Bank out of food. Stopping.");
            stop();
            return 600;
        }

        Bank.withdraw(config.foodName, config.withdrawAmount);
        Sleep.sleepUntil(() -> Inventory.contains(config.foodName), 3000);
        Bank.close();
        Sleep.sleepUntil(() -> !Bank.isOpen(), 2000);

        setStatus("Bank done — returning");
        return 600;
    }

    private int doLooting() {
        Set<String> lootNames = getLootItemNames();
        if (lootNames.isEmpty()) return 200;

        GroundItem item = GroundItems.closest(g ->
                g != null
                        && isInFightArea(g.getTile())
                        && lootNames.stream().anyMatch(n -> g.getName().toLowerCase().contains(n)));

        if (item != null) {
            setStatus("Looting: " + item.getName());
            Sleep.sleep(antiBan.getReactionDelay());
            item.interact("Take");
            Sleep.sleepUntil(() -> !item.exists(), 3000);
        }
        return 400;
    }

    private int doBurying() {
        // Pick up bones from ground first
        GroundItem bones = GroundItems.closest(g ->
                g != null
                        && g.getName() != null
                        && g.getName().toLowerCase().contains("bones")
                        && isInFightArea(g.getTile()));

        if (bones != null && !Inventory.isFull()) {
            setStatus("Picking up bones");
            Sleep.sleep(antiBan.getReactionDelay());
            bones.interact("Take");
            Sleep.sleepUntil(() -> !bones.exists(), 3000);
            return 400;
        }

        // Bury from inventory
        Item invBones = Inventory.get(i ->
                i != null && i.getName() != null
                        && i.getName().toLowerCase().contains("bones"));

        if (invBones != null) {
            setStatus("Burying bones");
            invBones.interact("Bury");
            Sleep.sleepUntil(
                    () -> !Inventory.contains(invBones.getName()), 2000);
            return 400;
        }

        return 200;
    }

    private int doWalking() {
        setStatus("Walking to fight area...");
        Walking.walk(new Tile(config.centerX, config.centerY));
        Sleep.sleepUntil(() -> Walking.getDestinationDistance() < 4, 4000);
        return 600;
    }

    private int doFighting() {
        // Already fighting — wait it out
        if (isInCombat()) {
            setStatus("Fighting: " + config.npcName);
            return 600;
        }

        // Fatigue miss-click
        if (antiBan.shouldMissClick()) {
            setStatus("Antiban: missed click");
            Sleep.sleep(antiBan.getReactionDelay());
            return 600;
        }

        Player local = Players.getLocal();

        // Use contains instead of equals so "Cow" matches "Cow calf" etc
        // Skip NPCs already being fought by other players
        NPC target = NPCs.closest(npc ->
                npc != null
                        && npc.getName() != null
                        && npc.getName().toLowerCase().contains(config.npcName.toLowerCase())
                        && !unreachableNpcs.contains(npc.getIndex())
                        && isInFightArea(npc.getTile())
                        && !isAnotherPlayerFighting(npc));

        if (target == null) {
            setStatus("Waiting for spawns...");
            return 600;
        }

        Sleep.sleep(antiBan.getReactionDelay());
        setStatus("Attacking: " + target.getName());
        int npcIndex = target.getIndex();
        currentAttemptedNpcIndex = npcIndex;

        // Force left click — prevents suspicious right-click menus
        target.interactForceLeft("Attack");

        boolean engaged = Sleep.sleepUntil(this::isInCombat, 4000);

        if (!engaged) {
            unreachableNpcs.add(npcIndex);
            Logger.log("[UFP] NPC unreachable, blacklisting: " + npcIndex);
        } else {
            // Occasionally clear the blacklist — spawns shift positions
            if (!unreachableNpcs.isEmpty()
                    && ThreadLocalRandom.current().nextDouble() < 0.05) {
                unreachableNpcs.clear();
            }
            // Wait for combat to end (NPC dies or runs)
            Sleep.sleepUntil(() -> !isInCombat(), 30000);
            killCount++;
            discord.incrementKills();
            discord.addXp(getTotalXpGained());
            currentAttemptedNpcIndex = null;

            // World hop check — after kill, before seeking next target
            if (config.worldHopEnabled && shouldHop()) {
                doWorldHop();
                return 600;
            }

            // Randomized post-kill delay
            int postKillDelay = ThreadLocalRandom.current().nextInt(300, 1200);
            if (ThreadLocalRandom.current().nextDouble() < 0.1) {
                postKillDelay += ThreadLocalRandom.current().nextInt(1000, 3000);
            }
            Sleep.sleep(postKillDelay);
        }

        return 400;
    }

    // -----------------------------------------------------------------------
    // Combat helper — Combat class has no isInCombat(); derive from player
    // -----------------------------------------------------------------------

    private boolean isInCombat() {
        Player local = Players.getLocal();
        if (local == null) return false;
        return local.isInCombat();
    }

    // -----------------------------------------------------------------------
    // Condition checks
    // -----------------------------------------------------------------------

    private boolean needsHealing() {
        return getHpPercent() < config.eatThreshold;
    }

    private boolean needsBanking() {
        return !Inventory.contains(i ->
                i != null && i.getName() != null
                        && i.getName().toLowerCase()
                        .contains(config.foodName.toLowerCase()));
    }

    private boolean hasLootNearby() {
        Set<String> names = getLootItemNames();
        if (names.isEmpty()) return false;
        return GroundItems.closest(g ->
                g != null
                        && g.getName() != null
                        && isInFightArea(g.getTile())
                        && names.stream().anyMatch(n -> g.getName().toLowerCase().contains(n))) != null;
    }

    private boolean hasBoneNearby() {
        boolean groundBones = GroundItems.closest(g ->
                g != null && g.getName() != null
                        && g.getName().toLowerCase().contains("bones")
                        && isInFightArea(g.getTile())) != null;
        boolean invBones = Inventory.contains(i ->
                i != null && i.getName() != null
                        && i.getName().toLowerCase().contains("bones"));
        return groundBones || invBones;
    }

    private boolean isTooFarFromArea() {
        Player local = Players.getLocal();
        if (local == null) return false;
        return local.getTile().distance(new Tile(config.centerX, config.centerY))
                > config.radius;
    }

    private boolean isInFightArea(Tile tile) {
        if (tile == null) return false;
        return tile.distance(new Tile(config.centerX, config.centerY)) <= config.radius;
    }

    private boolean isAnotherPlayerFighting(NPC npc) {
        Player local = Players.getLocal();
        for (Player p : Players.all()) {
            if (p == null || p.equals(local)) continue;
            if (p.getInteractingCharacter() != null
                    && p.getInteractingCharacter().equals(npc)) return true;
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private double getHpPercent() {
        int current = Skills.getBoostedLevel(Skill.HITPOINTS);
        int real    = Skills.getRealLevel(Skill.HITPOINTS);
        if (real == 0) return 100.0;
        return ((double) current / real) * 100.0;
    }

    private int getTotalXp() {
        return Skills.getExperience(Skill.ATTACK)
                + Skills.getExperience(Skill.STRENGTH)
                + Skills.getExperience(Skill.DEFENCE)
                + Skills.getExperience(Skill.RANGED)
                + Skills.getExperience(Skill.MAGIC);
    }

    private int getTotalXpGained() {
        return getTotalXp() - startXp;
    }

    private Set<String> getLootItemNames() {
        Set<String> names = new HashSet<>();
        if (config.lootItems == null || config.lootItems.trim().isEmpty()) return names;
        for (String entry : config.lootItems.split(",")) {
            String t = entry.trim().toLowerCase();
            if (!t.isEmpty()) names.add(t);
        }
        return names;
    }

    private boolean shouldHop() {
        // Count players in fight area
        int playersInArea = 0;
        for (Player p : Players.all()) {
            if (p == null || p.equals(Players.getLocal())) continue;
            if (isInFightArea(p.getTile())) playersInArea++;
        }
        return playersInArea >= config.worldHopThreshold;
    }

    private void doWorldHop() {
        setStatus("Hopping world — too crowded");
        Logger.log("[UFP] Hopping — " + countPlayersInArea() + " players in area.");

        World target;
        if (config.hopF2POnly) {
            target = Worlds.getRandomWorld(w ->
                    w.isF2P()
                            && !w.isPVP()
                            && !w.isHighRisk()
                            && !w.isDeadmanMode()
                            && w.getWorld() != Worlds.getCurrentWorld());
        } else {
            target = Worlds.getRandomWorld(w ->
                    w.isNormal()
                            && !w.isPVP()
                            && !w.isHighRisk()
                            && !w.isDeadmanMode()
                            && w.getWorld() != Worlds.getCurrentWorld());
        }

        if (target != null) {
            Logger.log("[UFP] Hopping to world " + target.getWorld());
            WorldHopper.hopWorld(target);
            Sleep.sleepUntil(() -> Worlds.getCurrentWorld() == target.getWorld(), 10000);
            // Reset unreachable list after hop — new world, fresh spawns
            unreachableNpcs.clear();
            lastMovedTime = System.currentTimeMillis();
        } else {
            Logger.log("[UFP] No suitable world found to hop to.");
        }
    }

    private int countPlayersInArea() {
        int count = 0;
        for (Player p : Players.all()) {
            if (p == null || p.equals(Players.getLocal())) continue;
            if (isInFightArea(p.getTile())) count++;
        }
        return count;
    }

    private Skill getSkillByName(String name) {
        switch (name) {
            case "Attack":    return Skill.ATTACK;
            case "Strength":  return Skill.STRENGTH;
            case "Defence":   return Skill.DEFENCE;
            case "Hitpoints": return Skill.HITPOINTS;
            case "Ranged":    return Skill.RANGED;
            case "Magic":     return Skill.MAGIC;
            case "Prayer":    return Skill.PRAYER;
            case "Slayer":    return Skill.SLAYER;
            default:          return null;
        }
    }

    private void setStatus(String msg) {
        statusMessage = msg;
        discord.updateStatus(msg);
    }

    private BufferedImage captureScreenshot() {
        try {
            return new Robot().createScreenCapture(
                    new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        } catch (AWTException e) {
            return null;
        }
    }

    @Override
    public void onMouseClicked(java.awt.event.MouseEvent e) {
        if (settingsBtnBounds.contains(e.getPoint())) {
            openSettingsGui();
        }
    }

    @Override
    public void onGameMessage(org.dreambot.api.wrappers.widgets.message.Message message) {
        if (message == null) return;
        String text = message.getMessage();
        if (text == null) return;
        if (text.toLowerCase().contains("can't reach") || text.toLowerCase().contains("nothing interesting")) {
            long now = System.currentTimeMillis();
            if (now - lastCantReachTime > CANT_REACH_COOLDOWN_MS) {
                lastCantReachTime = now;
                if (currentAttemptedNpcIndex != null) {
                    unreachableNpcs.add(currentAttemptedNpcIndex);
                    currentAttemptedNpcIndex = null;
                }
                Logger.log("[UFP] Can't reach — walking to center to handle obstacle.");
                setStatus("Obstacle! Walking around...");
                Walking.walk(new Tile(config.centerX, config.centerY));
                lastMovedTime = System.currentTimeMillis();
            }
        }
    }

    private void openSettingsGui() {
        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame("Universal Fighter Pro — Settings");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.setResizable(false);

                JScrollPane scroll = new JScrollPane(config);
                scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

                JButton applyBtn = new JButton("Apply");
                applyBtn.addActionListener(e -> {
                    if (config.confirm()) {
                        Combat.setCombatStyle(config.combatStyle);
                        Logger.log("[UFP] Settings updated mid-session.");
                        frame.dispose();
                    }
                });

                frame.setLayout(new BorderLayout());
                frame.add(scroll, BorderLayout.CENTER);
                frame.add(applyBtn, BorderLayout.SOUTH);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        } catch (Exception ex) {
            Logger.log("[UFP] Failed to open settings: " + ex.getMessage());
        }
    }

    @Override
    public void onPaint(Graphics g) {
        int x = 10, y = 40, lh = 17;

        // Calculate rates
        long msElapsed    = System.currentTimeMillis() - startTime;
        int  xpGained     = getTotalXpGained();
        double xpPerHr    = msElapsed > 5000 ? (double) xpGained / msElapsed * 3_600_000 : 0;
        double killsPerHr = msElapsed > 5000 ? (double) killCount / msElapsed * 3_600_000 : 0;

        // Background
        g.setColor(new Color(0, 0, 0, 165));
        g.fillRoundRect(x - 6, y - 16, 232, 168, 10, 10);

        // Title
        g.setFont(new Font("Arial", Font.BOLD, 11));
        g.setColor(new Color(255, 165, 0));
        g.drawString("Universal Fighter Pro", x, y);

        g.setFont(new Font("Arial", Font.PLAIN, 11));

        // Status
        g.setColor(Color.CYAN);
        g.drawString("Status : " + statusMessage, x, y += lh);

        // Stats
        g.setColor(Color.WHITE);
        g.drawString("Kills  : " + killCount + "  (" + (int) killsPerHr + "/hr)", x, y += lh);
        g.drawString("XP     : " + String.format("%,d", xpGained) + "  (" + formatRate(xpPerHr) + "/hr)", x, y += lh);
        g.drawString("Runtime: " + formatRuntime(), x, y += lh);

        // Fatigue bar
        if (antiBan != null) {
            g.drawString("Fatigue:", x, y += lh);
            drawBar(g, x + 58, y - 10, 140, 8, antiBan.getFatigueLevel(), fatigueColor());
        }

        // ETA to next attack level
        int atkXp        = Skills.getExperience(Skill.ATTACK)
                + Skills.getExperience(Skill.STRENGTH)
                + Skills.getExperience(Skill.DEFENCE);
        int atkLvl       = Skills.getLevelForExperience(atkXp);
        int xpForNext    = Skills.getExperienceForLevel(atkLvl + 1);
        int xpLeft       = xpForNext - atkXp;
        String eta       = "--:--";
        if (xpGained > 0 && msElapsed > 5000) {
            double xpPerMs = (double) xpGained / msElapsed;
            long   msLeft  = (long) (xpLeft / xpPerMs);
            eta = String.format("%02d:%02d:%02d",
                    msLeft / 3_600_000, (msLeft % 3_600_000) / 60_000, (msLeft % 60_000) / 1_000);
        }
        g.setColor(Color.WHITE);
        g.drawString("ETA lvl: " + eta, x, y += lh);

        // Settings button — below overlay
        int btnY = y + lh + 2;
        settingsBtnBounds.setBounds(x - 2, btnY, 116, 20);
        g.setColor(new Color(50, 50, 50, 210));
        g.fillRoundRect(settingsBtnBounds.x, settingsBtnBounds.y,
                settingsBtnBounds.width, settingsBtnBounds.height, 6, 6);
        g.setColor(new Color(120, 120, 120));
        g.drawRoundRect(settingsBtnBounds.x, settingsBtnBounds.y,
                settingsBtnBounds.width, settingsBtnBounds.height, 6, 6);
        g.setFont(new Font("Arial", Font.PLAIN, 10));
        g.setColor(Color.WHITE);
        g.drawString("Settings", settingsBtnBounds.x + 36, settingsBtnBounds.y + 14);
    }

    private String formatRate(double perHr) {
        if (perHr >= 1_000_000) return String.format("%.1fm", perHr / 1_000_000);
        if (perHr >= 1_000)     return String.format("%.1fk", perHr / 1_000);
        return String.format("%d", (int) perHr);
    }

    private void drawBar(Graphics g, int x, int y, int w, int h, double pct, Color fill) {
        pct = Math.max(0, Math.min(1, pct));
        g.setColor(new Color(50, 50, 50));
        g.fillRect(x, y, w, h);
        g.setColor(fill);
        g.fillRect(x, y, (int)(w * pct), h);
        g.setColor(Color.GRAY);
        g.drawRect(x, y, w, h);
    }

    private int getCombatXp() {
        return Skills.getExperience(Skill.ATTACK)
                + Skills.getExperience(Skill.STRENGTH)
                + Skills.getExperience(Skill.DEFENCE)
                + Skills.getExperience(Skill.RANGED)
                + Skills.getExperience(Skill.MAGIC);
    }

    private Color fatigueColor() {
        if (antiBan == null) return Color.GREEN;
        double f = antiBan.getFatigueLevel();
        if (f < 0.4) return Color.GREEN;
        if (f < 0.7) return Color.YELLOW;
        return Color.RED;
    }

    private String formatRuntime() {
        long ms = System.currentTimeMillis() - startTime;
        return String.format("%02d:%02d:%02d",
                ms / 3_600_000, (ms % 3_600_000) / 60_000, (ms % 60_000) / 1_000);
    }
}