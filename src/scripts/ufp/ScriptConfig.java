package scripts.ufp;

import org.dreambot.api.utilities.Logger;
import org.dreambot.api.methods.combat.CombatStyle;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class ScriptConfig extends JPanel {

    // -----------------------------------------------------------------------
    // TARGETING
    // -----------------------------------------------------------------------
    public String npcName  = "Chicken";
    public int    centerX  = 3230;
    public int    centerY  = 3297;
    public int    radius   = 12;

    // -----------------------------------------------------------------------
    // COMBAT
    // -----------------------------------------------------------------------
    public CombatStyle combatStyle = CombatStyle.STRENGTH;

    // -----------------------------------------------------------------------
    // HEALING
    // -----------------------------------------------------------------------
    public String  foodName      = "Lobster";
    public int     eatThreshold  = 40;
    public boolean logoutOnEmpty = false;

    // -----------------------------------------------------------------------
    // LOOTING
    // -----------------------------------------------------------------------
    public String  lootItems = "";
    public boolean buryBones = false;

    // -----------------------------------------------------------------------
    // BANKING
    // -----------------------------------------------------------------------
    public boolean bankingEnabled = false;
    public int     withdrawAmount = 10;

    // -----------------------------------------------------------------------
    // SCHEDULE
    // -----------------------------------------------------------------------
    public boolean scheduleEnabled     = false;
    public boolean scheduleByTime      = false;
    public int     scheduleHours       = 0;
    public int     scheduleMins        = 30;
    public boolean scheduleByLevel     = false;
    public String  scheduleSkill       = "Attack";
    public int     scheduleTargetLevel = 50;

    // -----------------------------------------------------------------------
    // WORLD HOPPING
    // -----------------------------------------------------------------------
    public boolean worldHopEnabled    = false;
    public int     worldHopThreshold  = 3;  // hop when this many players are in fight area
    public boolean hopF2POnly         = true;

    // -----------------------------------------------------------------------
    // ANTIBAN
    // -----------------------------------------------------------------------
    public int     minBreakMins      = 45;
    public int     maxBreakMins      = 75;
    public int     breakDurationSecs = 60;
    public boolean fatigueCurve      = true;

    // -----------------------------------------------------------------------
    // DISCORD
    // -----------------------------------------------------------------------
    public String webhookUrl      = "";
    public int    webhookInterval = 15;

    // -----------------------------------------------------------------------
    // Presets: {Display Name, NPC Name, X, Y, Radius}
    // -----------------------------------------------------------------------
    private final String[][] PRESETS = {
            {"Custom",                  "",              "0",    "0",    "0" },
            {"Lumbridge Chickens",      "Chicken",       "3230", "3297", "12"},
            {"Lumbridge Cows",          "Cow",           "3260", "3270", "16"},
            {"Lumbridge Goblins",       "Goblin",        "3256", "3244", "15"},
            {"Edgeville Monks",         "Monk",          "3049", "3483", "10"},
            {"Barbarian Village",       "Man",           "3081", "3422", "12"},
            {"Hill Giants (Edgeville)", "Hill Giant",    "3101", "9837", "15"},
            {"Sand Crabs (Hosidius)",   "Sand Crab",     "1750", "3463", "10"},
            {"Flesh Crawlers (SOS)",    "Flesh Crawler", "3142", "9913", "12"},
            {"Rock Crabs (Rellekka)",   "Rock Crab",     "2676", "3713", "12"},
    };

    // -----------------------------------------------------------------------
    // Swing components
    // -----------------------------------------------------------------------
    private JComboBox<String>    presetBox;
    private JTextField           npcField, xField, yField, radiusField;
    private JComboBox<CombatStyle> combatStyleBox;
    private JLabel               combatDesc;
    private JTextField           foodField;
    private JSlider              eatSlider;
    private JLabel               eatLabel;
    private JCheckBox            logoutBox;
    private JTextField           lootField;
    private JCheckBox            buryBox;
    private JCheckBox            bankBox;
    private JSpinner             withdrawSpinner;
    private JCheckBox            worldHopBox, hopF2PBox;
    private JSpinner             worldHopThresholdSpinner;
    private JCheckBox            scheduleEnabledBox, scheduleByTimeBox, scheduleByLevelBox;
    private JSpinner             scheduleHoursSpinner, scheduleMinsSpinner, scheduleTargetLevelSpinner;
    private JComboBox<String>    scheduleSkillBox;
    private JSpinner             minBreakSpinner, maxBreakSpinner, breakDurSpinner;
    private JCheckBox            fatigueBox;
    private JTextField           webhookField;
    private JSpinner             webhookSpinner;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------
    public ScriptConfig() {
        setLayout(new BorderLayout());
        setPreferredSize(new Dimension(500, 480));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Fight",    buildFightTab());
        tabs.addTab("Healing",  buildHealingTab());
        tabs.addTab("Loot",     buildLootTab());
        tabs.addTab("Banking",  buildBankingTab());
        tabs.addTab("Worlds",   buildWorldHopTab());
        tabs.addTab("Schedule", buildScheduleTab());
        tabs.addTab("Antiban",  buildAntiBanTab());
        tabs.addTab("Discord",  buildDiscordTab());

        add(tabs, BorderLayout.CENTER);
    }

    // -----------------------------------------------------------------------
    // Tab builders
    // -----------------------------------------------------------------------

    private JPanel buildFightTab() {
        JPanel p = tab();

        // Preset
        String[] names = new String[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) names[i] = PRESETS[i][0];
        presetBox = new JComboBox<>(names);
        npcField  = new JTextField(npcName, 12);
        xField    = new JTextField(String.valueOf(centerX), 6);
        yField    = new JTextField(String.valueOf(centerY), 6);
        radiusField = new JTextField(String.valueOf(radius), 4);

        presetBox.addActionListener(e -> {
            int idx = presetBox.getSelectedIndex();
            if (idx == 0) { npcField.setText(""); xField.setText("0"); yField.setText("0"); return; }
            npcField.setText(PRESETS[idx][1]);
            xField.setText(PRESETS[idx][2]);
            yField.setText(PRESETS[idx][3]);
            radiusField.setText(PRESETS[idx][4]);
        });
        presetBox.setSelectedIndex(1);

        JButton tileBtn = new JButton("Set Current Tile");
        tileBtn.addActionListener(e -> {
            try {
                org.dreambot.api.wrappers.interactive.Player local =
                        org.dreambot.api.methods.interactive.Players.getLocal();
                if (local == null) { error("Log in first."); return; }
                org.dreambot.api.methods.map.Tile tile = local.getTile();
                xField.setText(String.valueOf(tile.getX()));
                yField.setText(String.valueOf(tile.getY()));
                presetBox.setSelectedIndex(0);
            } catch (Exception ex) { error("Could not read tile."); }
        });

        JSpinner radiusSpinner = new JSpinner(new SpinnerNumberModel(radius, 1, 50, 1));
        radiusSpinner.addChangeListener(e -> radiusField.setText(String.valueOf(radiusSpinner.getValue())));

        // Combat style
        combatStyleBox = new JComboBox<>(CombatStyle.values());
        combatStyleBox.setSelectedItem(combatStyle);
        combatDesc = new JLabel(styleDesc(combatStyle));
        combatDesc.setFont(new Font("Arial", Font.ITALIC, 11));
        combatStyleBox.addActionListener(e -> {
            CombatStyle s = (CombatStyle) combatStyleBox.getSelectedItem();
            if (s != null) combatDesc.setText(styleDesc(s));
        });

        p.add(section("Location"));
        p.add(row("Preset",   presetBox));
        p.add(row("NPC",      npcField));
        p.add(row("X / Y",   hstack(xField, new JLabel("  /  "), yField)));
        p.add(row("Radius",   hstack(radiusSpinner, tileBtn)));
        p.add(gap());
        p.add(section("Attack Style"));
        p.add(row("Style",    combatStyleBox));
        p.add(row("",         combatDesc));
        return p;
    }

    private JPanel buildHealingTab() {
        JPanel p = tab();
        foodField = new JTextField(foodName, 14);
        eatSlider = new JSlider(10, 90, eatThreshold);
        eatLabel  = new JLabel(eatThreshold + "%");
        logoutBox = new JCheckBox("Logout when food runs out", logoutOnEmpty);
        eatSlider.addChangeListener(e -> eatLabel.setText(eatSlider.getValue() + "%"));

        p.add(section("Food"));
        p.add(row("Item name",    foodField));
        p.add(row("Eat below",    hstack(eatSlider, eatLabel)));
        p.add(gap());
        p.add(logoutBox);
        return p;
    }

    private JPanel buildLootTab() {
        JPanel p = tab();
        lootField = new JTextField(lootItems, 22);
        buryBox   = new JCheckBox("Pick up and bury bones", buryBones);

        p.add(section("Loot Filter"));
        p.add(row("Items",  lootField));
        p.add(new JLabel("  Comma separated, e.g: Cowhide, Goblin mail"));
        p.add(gap());
        p.add(buryBox);
        return p;
    }

    private JPanel buildBankingTab() {
        JPanel p = tab();
        bankBox         = new JCheckBox("Enable banking trips", bankingEnabled);
        withdrawSpinner = new JSpinner(new SpinnerNumberModel(withdrawAmount, 1, 28, 1));
        withdrawSpinner.setEnabled(bankingEnabled);
        bankBox.addActionListener(e -> withdrawSpinner.setEnabled(bankBox.isSelected()));

        p.add(section("Banking"));
        p.add(bankBox);
        p.add(gap());
        p.add(row("Withdraw amount", withdrawSpinner));
        p.add(new JLabel("  Uses the food name from Healing tab"));
        return p;
    }

    private JPanel buildWorldHopTab() {
        JPanel p = tab();
        worldHopBox              = new JCheckBox("Enable world hopping", worldHopEnabled);
        worldHopThresholdSpinner = new JSpinner(new SpinnerNumberModel(worldHopThreshold, 1, 10, 1));
        hopF2PBox                = new JCheckBox("F2P worlds only", hopF2POnly);

        worldHopThresholdSpinner.setEnabled(worldHopEnabled);
        hopF2PBox.setEnabled(worldHopEnabled);
        worldHopBox.addActionListener(e -> {
            worldHopThresholdSpinner.setEnabled(worldHopBox.isSelected());
            hopF2PBox.setEnabled(worldHopBox.isSelected());
        });

        p.add(section("World Hopping"));
        p.add(worldHopBox);
        p.add(gap());
        p.add(row("Hop when players in area >=", worldHopThresholdSpinner));
        p.add(hopF2PBox);
        p.add(gap());
        p.add(new JLabel("  Hops after finishing current kill"));
        return p;
    }

    private JPanel buildScheduleTab() {
        JPanel p = tab();
        String[] skills = {"Attack","Strength","Defence","Hitpoints","Ranged","Magic","Prayer","Slayer"};

        scheduleEnabledBox         = new JCheckBox("Enable auto-stop", scheduleEnabled);
        scheduleByTimeBox          = new JCheckBox("Stop after", scheduleByTime);
        scheduleHoursSpinner       = new JSpinner(new SpinnerNumberModel(scheduleHours, 0, 24, 1));
        scheduleMinsSpinner        = new JSpinner(new SpinnerNumberModel(scheduleMins, 0, 59, 5));
        scheduleByLevelBox         = new JCheckBox("Stop when", scheduleByLevel);
        scheduleSkillBox           = new JComboBox<>(skills);
        scheduleSkillBox.setSelectedItem(scheduleSkill);
        scheduleTargetLevelSpinner = new JSpinner(new SpinnerNumberModel(scheduleTargetLevel, 2, 99, 1));

        scheduleEnabledBox.addActionListener(e -> updateScheduleEnabled());
        scheduleByTimeBox.addActionListener(e -> updateScheduleEnabled());
        scheduleByLevelBox.addActionListener(e -> updateScheduleEnabled());
        updateScheduleEnabled();

        JPanel timeRow  = hstack(scheduleByTimeBox, scheduleHoursSpinner, new JLabel("hrs"), scheduleMinsSpinner, new JLabel("mins"));
        JPanel levelRow = hstack(scheduleByLevelBox, scheduleSkillBox, new JLabel("reaches lvl"), scheduleTargetLevelSpinner);

        p.add(section("Schedule"));
        p.add(scheduleEnabledBox);
        p.add(gap());
        p.add(timeRow);
        p.add(gap());
        p.add(levelRow);
        p.add(new JLabel("  Both conditions can be active — stops on whichever hits first"));
        return p;
    }

    private JPanel buildAntiBanTab() {
        JPanel p = tab();
        minBreakSpinner = new JSpinner(new SpinnerNumberModel(minBreakMins, 5, 120, 5));
        maxBreakSpinner = new JSpinner(new SpinnerNumberModel(maxBreakMins, 5, 180, 5));
        breakDurSpinner = new JSpinner(new SpinnerNumberModel(breakDurationSecs, 10, 300, 10));
        fatigueBox      = new JCheckBox("Progressive fatigue (slows down over time)", fatigueCurve);

        p.add(section("Breaks"));
        p.add(row("Min interval (mins)", minBreakSpinner));
        p.add(row("Max interval (mins)", maxBreakSpinner));
        p.add(row("Break length (secs)", breakDurSpinner));
        p.add(gap());
        p.add(section("Behaviour"));
        p.add(fatigueBox);
        return p;
    }

    private JPanel buildDiscordTab() {
        JPanel p = tab();
        webhookField   = new JTextField(webhookUrl, 26);
        webhookSpinner = new JSpinner(new SpinnerNumberModel(webhookInterval, 1, 120, 1));

        p.add(section("Discord Notifications"));
        p.add(row("Webhook URL",      webhookField));
        p.add(row("Interval (mins)",  webhookSpinner));
        p.add(new JLabel("  Leave URL blank to disable"));
        return p;
    }

    // -----------------------------------------------------------------------
    // Confirm — reads all components back into fields
    // -----------------------------------------------------------------------
    public boolean confirm() {
        try {
            npcName       = npcField.getText().trim();
            centerX       = Integer.parseInt(xField.getText().trim());
            centerY       = Integer.parseInt(yField.getText().trim());
            radius        = Integer.parseInt(radiusField.getText().trim());
            combatStyle   = (CombatStyle) combatStyleBox.getSelectedItem();
            foodName      = foodField.getText().trim();
            eatThreshold  = eatSlider.getValue();
            logoutOnEmpty = logoutBox.isSelected();
            lootItems     = lootField.getText().trim();
            buryBones     = buryBox.isSelected();
            bankingEnabled   = bankBox.isSelected();
            withdrawAmount   = (int) withdrawSpinner.getValue();
            worldHopEnabled   = worldHopBox.isSelected();
            worldHopThreshold = (int) worldHopThresholdSpinner.getValue();
            hopF2POnly        = hopF2PBox.isSelected();
            scheduleEnabled     = scheduleEnabledBox.isSelected();
            scheduleByTime      = scheduleByTimeBox.isSelected();
            scheduleHours       = (int) scheduleHoursSpinner.getValue();
            scheduleMins        = (int) scheduleMinsSpinner.getValue();
            scheduleByLevel     = scheduleByLevelBox.isSelected();
            scheduleSkill       = (String) scheduleSkillBox.getSelectedItem();
            scheduleTargetLevel = (int) scheduleTargetLevelSpinner.getValue();
            minBreakMins        = (int) minBreakSpinner.getValue();
            maxBreakMins        = (int) maxBreakSpinner.getValue();
            breakDurationSecs   = (int) breakDurSpinner.getValue();
            fatigueCurve        = fatigueBox.isSelected();
            webhookUrl          = webhookField.getText().trim();
            webhookInterval     = (int) webhookSpinner.getValue();

            if (npcName.isEmpty())            { error("NPC name cannot be empty.");          return false; }
            if (minBreakMins >= maxBreakMins) { error("Min break must be less than max.");   return false; }
            if (bankingEnabled && foodName.isEmpty()) { error("Set a food name for banking."); return false; }

            Logger.log("[UFP] Config: " + npcName + " @ (" + centerX + "," + centerY + ") r=" + radius);
            return true;
        } catch (NumberFormatException e) {
            error("X, Y and Radius must be numbers.");
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void updateScheduleEnabled() {
        boolean on = scheduleEnabledBox.isSelected();
        scheduleByTimeBox.setEnabled(on);
        scheduleHoursSpinner.setEnabled(on && scheduleByTimeBox.isSelected());
        scheduleMinsSpinner.setEnabled(on && scheduleByTimeBox.isSelected());
        scheduleByLevelBox.setEnabled(on);
        scheduleSkillBox.setEnabled(on && scheduleByLevelBox.isSelected());
        scheduleTargetLevelSpinner.setEnabled(on && scheduleByLevelBox.isSelected());
    }

    private String styleDesc(CombatStyle s) {
        if (s == null) return "";
        switch (s.name()) {
            case "ATTACK":         return "Trains Attack XP";
            case "STRENGTH":       return "Trains Strength XP";
            case "DEFENCE":        return "Trains Defence XP";
            case "SHARED":         return "Trains Attack + Strength + Defence";
            case "RANGED":         return "Trains Ranged XP";
            case "RANGED_RAPID":   return "Trains Ranged XP, fastest speed";
            case "RANGED_DEFENCE": return "Trains Ranged + Defence XP";
            case "MAGIC":          return "Trains Magic XP";
            case "MAGIC_DEFENCE":  return "Trains Magic + Defence XP";
            default:               return s.name();
        }
    }

    /** Base panel for each tab — vertical box layout with padding */
    private JPanel tab() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(12, 14, 12, 14));
        return p;
    }

    /** Bold section heading */
    private JLabel section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(new Font("Arial", Font.BOLD, 12));
        l.setBorder(new EmptyBorder(6, 0, 4, 0));
        return l;
    }

    /** Label + component row */
    private JPanel row(String label, JComponent field) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        if (!label.isEmpty()) r.add(new JLabel(label + ":"));
        r.add(field);
        return r;
    }

    /** Horizontal stack of components */
    private JPanel hstack(JComponent... parts) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        for (JComponent c : parts) r.add(c);
        return r;
    }

    /** Small vertical gap */
    private JPanel gap() {
        JPanel g = new JPanel();
        g.setPreferredSize(new Dimension(1, 6));
        return g;
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }
}