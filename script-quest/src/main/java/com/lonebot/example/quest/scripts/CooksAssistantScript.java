package com.lonebot.example.quest.scripts;

import com.lonebot.example.quest.QuestBotScript;
import com.lonebot.example.quest.QuestBotTarget;
import com.lonebot.example.quest.QuestLog;
import com.lonebot.example.quest.helpers.QuestActions;
import com.lonebot.example.quest.helpers.QuestBankGePrep;
import com.lonebot.example.quest.helpers.QuestCutsceneHelper;
import com.lonebot.example.quest.helpers.QuestDialogHelper;
import com.lonebot.example.quest.helpers.QuestGePrices;
import com.lonebot.example.quest.helpers.QuestHelperDialogSteps;
import com.lonebot.example.quest.helpers.QuestNpcApproachHelper;
import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.sdk.game.Chat;
import net.storm.sdk.game.Vars;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.BankWithdrawHelper;
import net.storm.sdk.items.GeRestockHelper;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.quests.Quests;
import net.storm.sdk.utils.Sleep;
import net.storm.sdk.widgets.Dialog;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Cook's Assistant — CombatBot {@code CooksAssistantScript} port.
 * <p>
 * Supply-chain: unnoted Egg + Pot of flour + Bucket of milk via
 * {@link QuestBankGePrep} (inv → bank → GE), daarna quest starten en afleveren bij de Cook.
 * Stappen/dialoog uit Quest Helper {@code helpers/quests/cooksassistant/CooksAssistant.java}.
 * <p>
 * Na quest-start (varp ≥ 1) wordt <b>nooit</b> opnieuw gegathered of gebankt: alleen afleveren.
 */
public final class CooksAssistantScript implements QuestBotScript {

    /** {@code VarPlayerID.COOKS_ASSISTANT} */
    public static final int VARP = 29;
    private static final int VARP_QUEST_COMPLETE = 2;

    private static final String EGG = "Egg";
    private static final String FLOUR = "Pot of flour";
    private static final String MILK = "Bucket of milk";

    private static final WorldPoint COOK_TILE = new WorldPoint(3206, 3214, 0);

    /** QH {@code finishQuest.addDialogSteps("What's wrong?", "Can I help?", "Yes.")} */
    private static final QuestHelperDialogSteps START_DIALOG = QuestHelperDialogSteps.of(
            "What's wrong?",
            "Can I help?",
            "Yes."
    );

    /** Afleveren: Cook vraagt per item; "Yes." dekt de bevestiging. */
    private static final QuestHelperDialogSteps DELIVER_DIALOG = QuestHelperDialogSteps.of(
            "Yes.",
            "I have an egg",
            "I have some flour",
            "I have some milk"
    );

    private static final QuestBankGePrep.Need[] PREP = {
            new QuestBankGePrep.Need(EGG, 1, QuestGePrices.EGG),
            new QuestBankGePrep.Need(FLOUR, 1, QuestGePrices.POT_OF_FLOUR),
            new QuestBankGePrep.Need(MILK, 1, QuestGePrices.BUCKET_OF_MILK)
    };

    /** GP-drempel waaronder we de GE-koop nooit gaan halen — log + blijf bank/GE proberen. */
    private static final int MIN_GE_COINS = 200;

    private enum Phase {
        GATHER,
        BANK_GE,
        WALK_COOK,
        TALK_COOK
    }

    private final QuestNpcApproachHelper cookApproach = QuestNpcApproachHelper.lumbridgeCook();

    private Phase phase = Phase.GATHER;

    private boolean turnedInEgg;
    private boolean turnedInFlour;
    private boolean turnedInMilk;
    private boolean turnedInAll;

    /** Item-vanish fallback: pas na quest-start en na 2× achtereen weg (client-thread flake). */
    private boolean sawEgg;
    private boolean sawFlour;
    private boolean sawMilk;
    private int missEgg;
    private int missFlour;
    private int missMilk;

    private int lastSeenVarp = -1;
    private int prepTicks;
    private int optionStallTicks;

    private long lastNpcMs;
    private long lastDialogMs;
    private long lastChatPollMs;

    @Override
    public String displayName() {
        return "Cook's Assistant";
    }

    @Override
    public QuestBotTarget target() {
        return QuestBotTarget.COOKS_ASSISTANT;
    }

    @Override
    public Quest runeliteQuest() {
        return Quest.COOKS_ASSISTANT;
    }

    @Override
    public void reset() {
        phase = Phase.GATHER;
        turnedInEgg = false;
        turnedInFlour = false;
        turnedInMilk = false;
        turnedInAll = false;
        sawEgg = false;
        sawFlour = false;
        sawMilk = false;
        missEgg = 0;
        missFlour = 0;
        missMilk = 0;
        lastSeenVarp = -1;
        prepTicks = 0;
        optionStallTicks = 0;
        lastNpcMs = 0L;
        lastDialogMs = 0L;
        lastChatPollMs = 0L;
        cookApproach.reset();
    }

    @Override
    public boolean isFinished() {
        return turnedInAll || readVarp() >= VARP_QUEST_COMPLETE || Quests.isFinished(Quest.COOKS_ASSISTANT);
    }

    @Override
    public int loop() {
        int varp = readVarp();
        onVarpChanged(varp);
        pollChatTurnIns();
        trackVanishedItems(varp);

        if (isFinished()) {
            QuestLog.step("Cook", "voltooid (varp=" + varp + ")");
            if (Bank.isOpen()) {
                Bank.leaveOpenForWalk();
            }
            GeRestockHelper.closeGeIfOpen();
            return rand(1200, 1800);
        }

        Integer dialogDelay = handleDialog(varp);
        if (dialogDelay != null) {
            return dialogDelay;
        }

        if (QuestCutsceneHelper.inCutscene()) {
            QuestLog.step("Cook", "cutscene — geen walk, wacht op dialoog");
            return rand(300, 500);
        }

        // Quest gestart: alleen afleveren. Geen bank, geen GE, geen re-gather.
        if (varp >= 1) {
            if (Bank.isOpen()) {
                Bank.leaveOpenForWalk();
            }
            prepTicks = 0;
            phase = Phase.WALK_COOK;
            QuestLog.step("Cook", "afleveren @ Cook (varp=" + varp + ") · " + progressLabel());
            return walkOrTalkCook(varp);
        }

        // varp == 0: items moeten unnoted op zak vóór de start-dialoog.
        if (!allItemsReady()) {
            phase = Phase.BANK_GE;
            return phaseBankGePrep();
        }

        if (Bank.isOpen()) {
            Bank.leaveOpenForWalk();
            Sleep.sleep(200, 360);
            return rand(400, 700);
        }
        GeRestockHelper.closeGeIfOpen();
        prepTicks = 0;
        phase = Phase.WALK_COOK;
        QuestLog.step("Cook", "items compleet → quest starten @ Cook · " + progressLabel());
        return walkOrTalkCook(varp);
    }

    // ------------------------------------------------------------------ prep

    /**
     * Bank/GE via {@link QuestBankGePrep}. Failsafe: nooit van doel wisselen — zolang een item
     * ontbreekt blijven we bank/GE proberen (met log elke ~25 ticks) i.p.v. een andere flow.
     */
    private int phaseBankGePrep() {
        if (Bank.isOpen()) {
            int block = BankWithdrawHelper.resolveBlockingBankInput();
            if (block > 0) {
                QuestLog.step("Cook", "Enter-amount blokkeerde bank → opgelost");
                Sleep.sleep(Math.min(block, 600));
                return rand(300, 500);
            }
        }

        prepTicks++;
        if (prepTicks % 25 == 0) {
            QuestLog.force("Cook", "prep loopt lang (" + prepTicks + " ticks) — zelfde doel bank/GE · "
                    + progressLabel() + " · gp=" + coins());
        } else {
            QuestLog.step("Cook", "items halen · bank/GE · " + progressLabel());
        }

        if (missingFromInventory() > 0 && !itemsAvailableInOpenBank() && coins() < MIN_GE_COINS) {
            QuestLog.step("Cook", "weinig gp (" + coins() + ") voor GE — blijf bank/GE proberen");
        }

        if (QuestBankGePrep.ensureUnnoted(PREP)) {
            return rand(500, 800);
        }

        prepTicks = 0;
        phase = Phase.WALK_COOK;
        QuestLog.step("Cook", "prep klaar · " + progressLabel());
        return rand(400, 700);
    }

    private static boolean itemsAvailableInOpenBank() {
        if (!Bank.isOpen()) {
            return false;
        }
        try {
            return Bank.contains(EGG) || Bank.contains(FLOUR) || Bank.contains(MILK);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------- walk/talk

    private int walkOrTalkCook(int varp) {
        int approach = cookApproach.approachBeforeTalk();
        if (approach > 0) {
            QuestLog.step("Cook", "→ keuken/Cook " + COOK_TILE.getX() + "," + COOK_TILE.getY());
            return approach;
        }

        INPC cook = cookApproach.findNpc();
        WorldPoint me = QuestActions.local();

        if (cook == null || me == null || !cookApproach.canTalkNow(me, cook)) {
            int retry = cookApproach.approachBeforeTalk();
            if (retry > 0) {
                return retry;
            }
            QuestLog.step("Cook", "Cook nog niet bereikbaar — opnieuw benaderen");
            return rand(800, 1200);
        }

        long now = System.currentTimeMillis();
        if (now - lastNpcMs < 1800L) {
            return rand(400, 700);
        }

        if (cook.interact("Talk-to") || cook.interact("Talk")) {
            lastNpcMs = now;
            phase = Phase.TALK_COOK;
            QuestLog.step("Cook", "Talk-to (varp=" + varp + ") @ " + me.getX() + "," + me.getY());
            Sleep.sleep(220, 380);
            return rand(1000, 1600);
        }

        QuestLog.step("Cook", "Talk-to faalde — retry zelfde Cook");
        return rand(600, 1000);
    }

    // ---------------------------------------------------------------- dialog

    /**
     * @return delay wanneer de dialoog/cutscene het loop-tick opvult, anders {@code null}
     */
    private Integer handleDialog(int varp) {
        boolean cutscene = QuestCutsceneHelper.inCutscene();
        boolean dialog = Dialog.isOpen();
        if (!dialog && !cutscene) {
            optionStallTicks = 0;
            return null;
        }

        long now = System.currentTimeMillis();
        if (now - lastDialogMs < 120L) {
            return 120;
        }
        lastDialogMs = now;

        QuestHelperDialogSteps steps = varp >= 1 ? DELIVER_DIALOG : START_DIALOG;
        boolean optionMenu = Dialog.isViewingOptions();

        if (optionMenu && !hasWantedOption(steps)) {
            optionStallTicks++;
            // Laatste redmiddel (CombatBot): lineaire quest → eerste optie, zodat we niet vastzitten.
            if (optionStallTicks >= 3) {
                optionStallTicks = 0;
                QuestLog.force("Cook", "geen exacte dialoog-optie → eerste optie (failsafe)");
                Dialog.chooseOption(0);
                return rand(250, 450);
            }
            return rand(200, 350);
        }
        optionStallTicks = 0;

        if (QuestDialogHelper.handle(steps)) {
            QuestLog.step("Cook", varp >= 1
                    ? "afleveren-dialoog · " + progressLabel()
                    : "start-dialoog (What's wrong? / Can I help? / Yes.)");
            return rand(250, 450);
        }

        if (Dialog.canContinue()) {
            Dialog.continueSpace();
            return rand(150, 280);
        }

        return cutscene ? rand(300, 500) : rand(200, 400);
    }

    private static boolean hasWantedOption(QuestHelperDialogSteps steps) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        for (String want : steps.steps()) {
            final String w = strip(want).toLowerCase(Locale.ROOT);
            if (w.isEmpty()) {
                continue;
            }
            try {
                if (Dialog.hasOption(t -> optionMatches(t, w))) {
                    return true;
                }
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean optionMatches(String optionText, String wantLower) {
        if (optionText == null) {
            return false;
        }
        String o = strip(optionText).toLowerCase(Locale.ROOT);
        if (o.equals(wantLower)) {
            return true;
        }
        if (isBareYes(wantLower)) {
            return isBareYes(o);
        }
        return o.contains(wantLower);
    }

    private static boolean isBareYes(String s) {
        String t = s.replace(".", "").replace("!", "").trim();
        return "yes".equals(t) || "yes please".equals(t);
    }

    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\u00A0', ' ').replaceAll("<[^>]+>", "").trim();
    }

    // ------------------------------------------------------------- turn-ins

    /** Chat-hook (CombatBot {@code onChatMessage}) — mag ook extern aangeroepen worden. */
    public void onChatMessage(String message) {
        if (message == null) {
            return;
        }
        String low = strip(message).toLowerCase(Locale.ROOT);
        if (low.isEmpty()) {
            return;
        }
        if (low.contains("here's a bucket of milk")) {
            markTurnIn(MILK);
        } else if (low.contains("here's a pot of flour")) {
            markTurnIn(FLOUR);
        } else if (low.contains("here's a fresh egg")) {
            markTurnIn(EGG);
        } else if (low.contains("you've brought me everything i need")
                || low.contains("congratulations, you've completed a quest")
                || low.contains("you have completed the cook's assistant")) {
            markAllTurnedIn("chat");
        }
    }

    /** Geen ChatMessage-event in de script-laag → chatbuffer pollen (~1.2 s). */
    private void pollChatTurnIns() {
        if (turnedInAll) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastChatPollMs < 1200L) {
            return;
        }
        lastChatPollMs = now;
        List<String> lines;
        try {
            lines = Chat.getRecentMessages(12);
        } catch (Throwable ignored) {
            return;
        }
        if (lines == null || lines.isEmpty()) {
            return;
        }
        for (String line : lines) {
            onChatMessage(line);
        }
    }

    /**
     * Fallback zonder chat: na quest-start verdwijnt een afgeleverd item uit de inventory.
     * Pas markeren na 2× achtereen afwezig — één lege client-thread-read is geen bewijs.
     */
    private void trackVanishedItems(int varp) {
        if (varp < 1 || turnedInAll) {
            return;
        }
        boolean hasEgg = Inventory.contains(EGG);
        boolean hasFlour = Inventory.contains(FLOUR);
        boolean hasMilk = Inventory.contains(MILK);

        if (hasEgg) {
            sawEgg = true;
            missEgg = 0;
        } else if (sawEgg && !turnedInEgg && ++missEgg >= 2) {
            markTurnIn(EGG);
        }

        if (hasFlour) {
            sawFlour = true;
            missFlour = 0;
        } else if (sawFlour && !turnedInFlour && ++missFlour >= 2) {
            markTurnIn(FLOUR);
        }

        if (hasMilk) {
            sawMilk = true;
            missMilk = 0;
        } else if (sawMilk && !turnedInMilk && ++missMilk >= 2) {
            markTurnIn(MILK);
        }
    }

    private void markTurnIn(String item) {
        if (EGG.equals(item) && !turnedInEgg) {
            turnedInEgg = true;
            QuestLog.force("Cook", "Egg afgeleverd · " + progressLabel());
        } else if (FLOUR.equals(item) && !turnedInFlour) {
            turnedInFlour = true;
            QuestLog.force("Cook", "Pot of flour afgeleverd · " + progressLabel());
        } else if (MILK.equals(item) && !turnedInMilk) {
            turnedInMilk = true;
            QuestLog.force("Cook", "Bucket of milk afgeleverd · " + progressLabel());
        }
        if (turnedInEgg && turnedInFlour && turnedInMilk) {
            markAllTurnedIn("alle items");
        }
    }

    private void markAllTurnedIn(String reason) {
        turnedInEgg = true;
        turnedInFlour = true;
        turnedInMilk = true;
        if (!turnedInAll) {
            turnedInAll = true;
            QuestLog.force("Cook", "quest afgerond (" + reason + ")");
        }
    }

    private void onVarpChanged(int varp) {
        if (varp == lastSeenVarp) {
            return;
        }
        if (lastSeenVarp >= 0) {
            QuestLog.force("Cook", "varp " + lastSeenVarp + " → " + varp + " · " + progressLabel());
        }
        int previous = lastSeenVarp;
        lastSeenVarp = varp;
        if (varp >= VARP_QUEST_COMPLETE) {
            markAllTurnedIn("varp " + varp);
        } else if (varp >= 1 && previous < 1) {
            prepTicks = 0;
            phase = Phase.WALK_COOK;
            QuestLog.force("Cook", "quest gestart → alleen afleveren, geen bank/GE meer");
        }
    }

    // ---------------------------------------------------------------- status

    private boolean needsItem(String item) {
        if (EGG.equals(item)) {
            return !turnedInEgg;
        }
        if (FLOUR.equals(item)) {
            return !turnedInFlour;
        }
        if (MILK.equals(item)) {
            return !turnedInMilk;
        }
        return false;
    }

    /** Alle nog benodigde items unnoted op zak (noted egg kan de Cook niet aannemen). */
    private boolean allItemsReady() {
        return (!needsItem(EGG) || QuestActions.hasUnnoted(EGG))
                && (!needsItem(FLOUR) || QuestActions.hasUnnoted(FLOUR))
                && (!needsItem(MILK) || QuestActions.hasUnnoted(MILK));
    }

    private int missingFromInventory() {
        int n = 0;
        if (needsItem(EGG) && !QuestActions.hasUnnoted(EGG)) {
            n++;
        }
        if (needsItem(FLOUR) && !QuestActions.hasUnnoted(FLOUR)) {
            n++;
        }
        if (needsItem(MILK) && !QuestActions.hasUnnoted(MILK)) {
            n++;
        }
        return n;
    }

    private String progressLabel() {
        return "egg=" + (turnedInEgg ? "✓" : QuestActions.unnotedCount(EGG))
                + " flour=" + (turnedInFlour ? "✓" : QuestActions.unnotedCount(FLOUR))
                + " milk=" + (turnedInMilk ? "✓" : QuestActions.unnotedCount(MILK))
                + " [" + phase.name().toLowerCase(Locale.ROOT) + "]";
    }

    private static int coins() {
        try {
            return GeRestockHelper.inventoryCoinCount();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int readVarp() {
        try {
            return Vars.getVarp(VARP);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int rand(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + ThreadLocalRandom.current().nextInt(max - min);
    }
}
