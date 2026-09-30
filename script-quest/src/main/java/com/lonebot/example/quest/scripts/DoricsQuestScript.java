package com.lonebot.example.quest.scripts;

import com.lonebot.example.quest.QuestBotScript;
import com.lonebot.example.quest.QuestBotTarget;
import com.lonebot.example.quest.QuestLog;
import com.lonebot.example.quest.helpers.QuestActions;
import com.lonebot.example.quest.helpers.QuestBankGePrep;
import com.lonebot.example.quest.helpers.QuestDialogHelper;
import com.lonebot.example.quest.helpers.QuestDoricHouseApproachHelper;
import com.lonebot.example.quest.helpers.QuestGePrices;
import com.lonebot.example.quest.helpers.QuestHelperDialogSteps;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.sdk.game.Chat;
import net.storm.sdk.game.Skills;
import net.storm.sdk.game.Vars;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.quests.Quests;
import net.storm.sdk.widgets.Dialog;

import java.util.List;
import java.util.Locale;

/**
 * Doric's Quest — VarPlayer 31; one delivery of unnoted 6 Clay + 4 Copper ore + 2 Iron ore.
 * <p>CombatBot {@code DoricsQuestHandler} port: supply chain first (bank/GE, only noted/mixed
 * deposits), then <b>one trip</b> to the hut — quest start and hand-in happen in the same talk.
 * Rotation skips when Mining ≥ 10; an explicit DORICS target still runs.</p>
 */
public final class DoricsQuestScript implements QuestBotScript {

    public static final int VARP = 31;
    /** VarPlayer 31 at/above this = quest finished. */
    public static final int DONE = 100;

    public static final WorldPoint DORIC = new WorldPoint(2951, 3451, 0);
    public static final WorldPoint DOOR = new WorldPoint(2950, 3450, 0);
    public static final WorldPoint APPROACH = new WorldPoint(2950, 3449, 0);

    private static final int MINING_LEVEL_SKIP = 10;

    private static final String CLAY = "Clay";
    private static final String COPPER = "Copper ore";
    private static final String IRON = "Iron ore";

    private static final int NEED_CLAY = 6;
    private static final int NEED_COPPER = 4;
    private static final int NEED_IRON = 2;

    private static final QuestHelperDialogSteps DIALOG = QuestHelperDialogSteps.of(
            "I wanted to use your anvils.",
            "Yes."
    );

    private static final QuestBankGePrep.Need[] ORES = {
            new QuestBankGePrep.Need(CLAY, NEED_CLAY, QuestGePrices.CLAY),
            new QuestBankGePrep.Need(COPPER, NEED_COPPER, QuestGePrices.COPPER_ORE),
            new QuestBankGePrep.Need(IRON, NEED_IRON, QuestGePrices.IRON_ORE)
    };

    /** Supply chain zonder voortgang → log met coins/ontbrekend, blijft hetzelfde doel proberen. */
    private static final long SUPPLY_STALL_MS = 45_000L;
    /** Bij de hut zonder varp-wijziging → approach-helper reset (deur/walk throttle vrij). */
    private static final long DELIVER_STALL_MS = 30_000L;
    private static final long CHAT_SCAN_MS = 1_200L;
    private static final long REACH_RETRIGGER_MS = 15_000L;

    private String lastSupplySig = "";
    private long lastSupplyProgressMs;
    private long deliverSinceMs;
    private int lastVarpSeen = -1;
    private long lastChatScanMs;
    private long reachCooldownUntilMs;
    /** Chat "I can't reach that!" → deur dicht vóór Doric. */
    private boolean doorBlocksTalk;

    @Override
    public String displayName() {
        return "Doric's Quest";
    }

    @Override
    public QuestBotTarget target() {
        return QuestBotTarget.DORICS;
    }

    @Override
    public Quest runeliteQuest() {
        return Quest.DORICS_QUEST;
    }

    @Override
    public void reset() {
        lastSupplySig = "";
        lastSupplyProgressMs = 0L;
        deliverSinceMs = 0L;
        lastVarpSeen = -1;
        lastChatScanMs = 0L;
        reachCooldownUntilMs = 0L;
        doorBlocksTalk = false;
        QuestDoricHouseApproachHelper.reset();
    }

    @Override
    public boolean isFinished() {
        return Quests.isFinished(Quest.DORICS_QUEST) || varp() >= DONE;
    }

    /**
     * Rotation only: Mining ≥ 10 skips Doric (CombatBot {@code shouldRunQuest}).
     * An explicit DORICS target never consults this.
     */
    @Override
    public boolean isFeasible() {
        return !skipForRotation();
    }

    /** Rotation skip: Mining ≥ 10. */
    public static boolean skipForRotation() {
        return Skills.getLevel(Skill.MINING) >= MINING_LEVEL_SKIP;
    }

    @Override
    public int loop() {
        // 1) Dialog eerst: tijdens de hand-in is de inventory leeg — nooit terug naar de bank stuiteren.
        if (Dialog.isOpen()) {
            QuestDialogHelper.handle(DIALOG);
            return 450;
        }

        int varp = varp();
        if (varp >= DONE || Quests.isFinished(Quest.DORICS_QUEST)) {
            QuestLog.step("Doric", "klaar (varp=" + varp + ")");
            return 1200;
        }

        // 2) Strikt: alleen naar Doric met 6 clay + 4 copper + 2 iron unnoted op zak.
        if (!oresReady()) {
            return supply(varp);
        }
        lastSupplySig = "";
        lastSupplyProgressMs = 0L;

        // 3) Eén trip: deur + Talk-to (start + afleveren in hetzelfde gesprek).
        return deliver(varp);
    }

    /**
     * Bank/GE via {@link QuestBankGePrep}: alleen noted/mixed storten, unnoted withdrawen,
     * tekort in bank+inv via GE. Failsafe: geen voortgang in 45 s → console-regel met
     * coins + ontbrekend item, en dezelfde prep blijft draaien (geen ander doel).
     */
    private int supply(int varp) {
        String sig = progressLabel();
        long now = System.currentTimeMillis();
        if (!sig.equals(lastSupplySig)) {
            lastSupplySig = sig;
            lastSupplyProgressMs = now;
            QuestLog.step("Doric", "materialen " + sig + " (varp=" + varp + ")");
        } else if (lastSupplyProgressMs == 0L) {
            lastSupplyProgressMs = now;
        } else if (now - lastSupplyProgressMs > SUPPLY_STALL_MS) {
            lastSupplyProgressMs = now;
            QuestLog.force("Doric", "geen voortgang 45s · " + sig
                    + " · ontbreekt " + firstMissing() + " · coins=" + coins()
                    + " → bank/GE blijft proberen");
        }

        if (QuestBankGePrep.ensureUnnoted(ORES)) {
            return 600;
        }
        // ensureUnnoted false = alles unnoted; bank/GE is dan al gesloten voor de walk.
        QuestLog.step("Doric", "materialen compleet → hut");
        return 400;
    }

    private int deliver(int varp) {
        WorldPoint me = QuestActions.local();
        if (me == null) {
            return 800;
        }
        long now = System.currentTimeMillis();
        if (varp != lastVarpSeen) {
            lastVarpSeen = varp;
            deliverSinceMs = now;
        } else if (deliverSinceMs == 0L) {
            deliverSinceMs = now;
        } else if (now - deliverSinceMs > DELIVER_STALL_MS) {
            deliverSinceMs = now;
            QuestDoricHouseApproachHelper.reset();
            QuestLog.force("Doric", "30s geen varp-wijziging bij hut → approach reset ("
                    + me.getX() + "," + me.getY() + ")");
        }

        scanChatForBlockedDoor(me, now);

        if (QuestDoricHouseApproachHelper.canTalkToDoricNow(me)) {
            QuestLog.step("Doric", "Talk-to Doric · " + progressLabel() + " unnoted");
        } else {
            QuestLog.step("Doric", "→ hut " + DOOR.getX() + "," + DOOR.getY()
                    + " (nu " + me.getX() + "," + me.getY() + ", varp=" + varp + ")");
        }

        // Helper doet deur (alleen Open bij dichte deur), binnenlopen, Talk-to en de dialoogstappen.
        if (QuestDoricHouseApproachHelper.talk(DIALOG)) {
            return 700;
        }
        // Geen actie: NPC/deur even niet klikbaar → korte retry op hetzelfde doel.
        QuestLog.step("Doric", "geen klik gelukt → retry hut/Talk-to");
        QuestActions.walkTo(APPROACH, 2);
        return 900;
    }

    /**
     * Chat "I can't reach that!" = hutdeur dicht. Reset de approach-throttles zodat de deur
     * meteen opnieuw geopend wordt; vlag valt weg zodra we binnen staan.
     */
    private void scanChatForBlockedDoor(WorldPoint me, long now) {
        if (doorBlocksTalk) {
            if (QuestDoricHouseApproachHelper.insideDoricHouse(me)) {
                doorBlocksTalk = false;
                reachCooldownUntilMs = now + REACH_RETRIGGER_MS;
            }
            return;
        }
        if (now < reachCooldownUntilMs || now - lastChatScanMs < CHAT_SCAN_MS) {
            return;
        }
        lastChatScanMs = now;
        List<String> lines = Chat.getRecentMessages(3);
        if (lines == null || lines.isEmpty()) {
            // Lege lijst kan ook een client-thread timeout zijn — geen conclusie trekken.
            return;
        }
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            if (raw.toLowerCase(Locale.ROOT).contains("reach that")) {
                doorBlocksTalk = true;
                reachCooldownUntilMs = now + REACH_RETRIGGER_MS;
                QuestDoricHouseApproachHelper.reset();
                QuestLog.force("Doric", "chat \"can't reach that\" → deur dicht, opnieuw Open");
                return;
            }
        }
    }

    private static boolean oresReady() {
        return QuestActions.unnotedCount(CLAY) >= NEED_CLAY
                && QuestActions.unnotedCount(COPPER) >= NEED_COPPER
                && QuestActions.unnotedCount(IRON) >= NEED_IRON;
    }

    private static String firstMissing() {
        if (QuestActions.unnotedCount(CLAY) < NEED_CLAY) {
            return CLAY;
        }
        if (QuestActions.unnotedCount(COPPER) < NEED_COPPER) {
            return COPPER;
        }
        if (QuestActions.unnotedCount(IRON) < NEED_IRON) {
            return IRON;
        }
        return "niets";
    }

    private static String progressLabel() {
        return QuestActions.unnotedCount(CLAY) + "/" + NEED_CLAY + " clay, "
                + QuestActions.unnotedCount(COPPER) + "/" + NEED_COPPER + " copper, "
                + QuestActions.unnotedCount(IRON) + "/" + NEED_IRON + " iron";
    }

    private static int coins() {
        try {
            return Inventory.getCount(true, "Coins");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int varp() {
        try {
            return Vars.getVarp(VARP);
        } catch (Throwable t) {
            return 0;
        }
    }
}
