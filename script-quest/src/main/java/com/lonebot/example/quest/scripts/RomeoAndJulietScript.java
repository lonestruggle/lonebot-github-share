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
import com.lonebot.example.quest.helpers.QuestJulietHouseHelper;
import com.lonebot.example.quest.helpers.QuestNpcApproachHelper;
import net.runelite.api.Quest;
import net.runelite.api.coords.WorldPoint;
import net.storm.sdk.game.Vars;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.quests.Quests;
import net.storm.sdk.widgets.Dialog;

import java.util.Locale;

/**
 * Romeo &amp; Juliet — VarPlayer 144: 0 Romeo start → 10 Juliet (balkon) → 20 brief naar Romeo →
 * 30 Father Lawrence → 40 cadava berries + Apothecary → 50 potion naar Juliet → 60 afronden @ Romeo.
 * <p>CombatBot {@code RomeoAndJulietScript} port: zelfde stap-map, zelfde Quest Helper dialogen
 * ({@code helpers/quests/romeoandjuliet/RomeoAndJuliet.java}) en dezelfde edge-cases — effectieve
 * stap als de Romeo-start-dialoog klaar is maar de varp nog achterloopt, cadava al vanaf stap 10
 * regelen (bank vóór GE), en potion-in-inventory op stap 40 als stap 50 behandelen.</p>
 * <p>Reizen gaat via de LoneBot walker ({@code Movement.walkTo} in de approach-helpers); de
 * CombatBot Varrock-teleport-kit hoort bij die laag en is hier niet overgenomen.</p>
 */
public final class RomeoAndJulietScript implements QuestBotScript {

    /** VarPlayer 144 — Romeo &amp; Juliet voortgang. */
    public static final int VARP = 144;
    /** VarPlayer 144 op/boven deze waarde = quest klaar. */
    public static final int DONE = 100;

    private static final String CADAVA_BERRIES = "Cadava berries";
    private static final String CADAVA_POTION = "Cadava potion";

    /** Cadava bush, zuidoost Varrock — gratis failsafe als bank/GE niet levert. */
    private static final WorldPoint CADAVA_BUSH = new WorldPoint(3268, 3370, 0);

    private static final QuestBankGePrep.Need[] BERRIES = {
            new QuestBankGePrep.Need(CADAVA_BERRIES, 1, QuestGePrices.CADAVA_BERRIES)
    };

    private static final QuestHelperDialogSteps ROMEO_START = QuestHelperDialogSteps.of(
            "Yes, I have seen her actually!",
            "Yes, ok, I'll let her know.",
            "Yes."
    );
    private static final QuestHelperDialogSteps JULIET_D = QuestHelperDialogSteps.of("Ok, thanks.");
    private static final QuestHelperDialogSteps LAWRENCE_D = QuestHelperDialogSteps.of("Ok, thanks.");
    private static final QuestHelperDialogSteps APOTHECARY_D = QuestHelperDialogSteps.of(
            "Talk about something else.",
            "Talk about Romeo & Juliet."
    );
    /** Brief afgeven (20) en afronden (60): alleen doorklikken, nooit een losse optie gokken. */
    private static final QuestHelperDialogSteps CONTINUE_ONLY = QuestHelperDialogSteps.of();

    /** Geen varp-wijziging bij een NPC → approach-throttles (deur/walk) vrijgeven. */
    private static final long STEP_STALL_MS = 30_000L;
    /** Bank/GE levert geen berries → bush-failsafe (zelfde doel, geen coins nodig). */
    private static final long SUPPLY_STALL_MS = 45_000L;
    private static final long TALK_COOLDOWN_MS = 550L;

    private final QuestNpcApproachHelper romeo = QuestNpcApproachHelper.romeo();
    private final QuestNpcApproachHelper lawrence = QuestNpcApproachHelper.fatherLawrence();
    private final QuestNpcApproachHelper apothecary = QuestNpcApproachHelper.apothecary();

    /** Romeo-start-dialoog afgerond terwijl varp nog 0 is (client loopt achter). */
    private boolean romeoStarted;
    /** Bank/GE-route voor cadava opgegeven → berries van de bush halen. */
    private boolean cadavaViaBush;

    private String stepSig = "";
    private long stepSinceMs;
    private String supplySig = "";
    private long supplySinceMs;
    private long lastTalkMs;

    @Override
    public String displayName() {
        return "Romeo & Juliet";
    }

    @Override
    public QuestBotTarget target() {
        return QuestBotTarget.ROMEO_AND_JULIET;
    }

    @Override
    public Quest runeliteQuest() {
        return Quest.ROMEO__JULIET;
    }

    @Override
    public void reset() {
        romeoStarted = false;
        cadavaViaBush = false;
        stepSig = "";
        stepSinceMs = 0L;
        supplySig = "";
        supplySinceMs = 0L;
        lastTalkMs = 0L;
        romeo.reset();
        lawrence.reset();
        apothecary.reset();
    }

    @Override
    public boolean isFinished() {
        return Quests.isFinished(Quest.ROMEO__JULIET) || varp() >= DONE;
    }

    @Override
    public int loop() {
        int varp = varp();

        // 1) Dialoog eerst: tijdens een gesprek nooit lopen of opnieuw klikken.
        if (Dialog.isOpen() || QuestCutsceneHelper.inCutscene()) {
            return handleDialog(varp);
        }

        if (varp >= DONE || Quests.isFinished(Quest.ROMEO__JULIET)) {
            QuestLog.step("Romeo", "klaar (varp=" + varp + ")");
            return 1200;
        }

        int step = effectiveStep(varp);
        watchStepStall(step);

        // 2) Cadava al vanaf stap 10 regelen (CombatBot-prep): bank vóór GE, één trip minder.
        if (needsCadava(step)) {
            Integer supply = supplyCadava(step);
            if (supply != null) {
                return supply;
            }
        }

        if (step >= 60) {
            return talkRomeo("afronden", CONTINUE_ONLY);
        }
        if (step >= 50) {
            return potionToJuliet(step);
        }
        if (step >= 40) {
            // Potion al op zak maar varp nog 40 → Juliet, niet nog een rondje Apothecary.
            if (hasPotion()) {
                return potionToJuliet(step);
            }
            return toApothecary();
        }
        if (step >= 30) {
            QuestLog.step("Romeo", "Father Lawrence (Varrock kerk)");
            return talkVia(lawrence, LAWRENCE_D, "Father Lawrence");
        }
        if (step >= 20) {
            return talkRomeo("brief", CONTINUE_ONLY);
        }
        if (step >= 10) {
            return talkJuliet();
        }
        QuestLog.step("Romeo", "start bij Romeo (Varrock Square)");
        return talkVia(romeo, ROMEO_START, "Romeo");
    }

    /**
     * Varp 144 loopt na de Romeo-start-dialoog een tick achter — met een gesloten dialoog en
     * {@link #romeoStarted} is dat stap 10 (CombatBot {@code effectiveQuestStep}).
     */
    private int effectiveStep(int varp) {
        if (varp <= 0 && romeoStarted && !Dialog.isOpen()) {
            return 10;
        }
        return varp;
    }

    /**
     * Continue + exacte Quest Helper-opties per stap. Onthoudt of de laatste start-optie
     * ("Yes.") geklikt is, zodat de varp-lag na Romeo geen nieuwe start-poging geeft.
     */
    private int handleDialog(int varp) {
        boolean startYes = isQuestStartYesVisible(varp);
        QuestHelperDialogSteps steps = dialogFor(effectiveStep(varp));
        if (QuestDialogHelper.handle(steps)) {
            if (startYes && !romeoStarted) {
                romeoStarted = true;
                QuestLog.force("Romeo", "start-dialoog afgerond (varp nog " + varp + ")");
            }
            return 450;
        }
        return 500;
    }

    private static QuestHelperDialogSteps dialogFor(int step) {
        if (step <= 0) {
            return ROMEO_START;
        }
        if (step == 10 || step == 50) {
            return JULIET_D;
        }
        if (step == 30) {
            return LAWRENCE_D;
        }
        if (step == 40) {
            return APOTHECARY_D;
        }
        return CONTINUE_ONLY;
    }

    /** Laatste optie van de start-dialoog: bare "Yes." terwijl de eerdere regels weg zijn. */
    private static boolean isQuestStartYesVisible(int varp) {
        if (varp > 0 || !Dialog.isViewingOptions()) {
            return false;
        }
        if (hasOption("i have seen her") || hasOption("let her know")) {
            return false;
        }
        return Dialog.hasOption(t -> {
            if (t == null) {
                return false;
            }
            String s = t.replaceAll("<[^>]+>", "").replace(".", "").replace("!", "").trim();
            return "yes".equalsIgnoreCase(s);
        });
    }

    private static boolean hasOption(String needle) {
        return Dialog.hasOption(t -> t != null
                && t.toLowerCase(Locale.ROOT).contains(needle));
    }

    private int talkRomeo(String why, QuestHelperDialogSteps dialog) {
        if (QuestJulietHouseHelper.inJulietRoom()) {
            QuestLog.step("Romeo", why + ": eerst omlaag uit Juliets kamer");
            QuestJulietHouseHelper.leaveToGround();
            return 700;
        }
        QuestLog.step("Romeo", why + " → Romeo");
        return talkVia(romeo, dialog, "Romeo");
    }

    private int talkJuliet() {
        if (QuestJulietHouseHelper.goToJuliet()) {
            return 700;
        }
        if (System.currentTimeMillis() - lastTalkMs < TALK_COOLDOWN_MS) {
            return 350;
        }
        QuestLog.step("Romeo", "Talk-to Juliet (balkon)");
        lastTalkMs = System.currentTimeMillis();
        QuestNpcApproachHelper.approachAndTalk("Juliet", QuestJulietHouseHelper.JULIET, JULIET_D);
        return 700;
    }

    private int potionToJuliet(int step) {
        if (!hasPotion()) {
            // Potion kwijt/nog niet gehad → zelfde doel: terug naar de Apothecary.
            QuestLog.step("Romeo", "geen cadava potion → Apothecary (stap " + step + ")");
            return toApothecary();
        }
        if (QuestJulietHouseHelper.goToJuliet()) {
            return 700;
        }
        if (System.currentTimeMillis() - lastTalkMs < TALK_COOLDOWN_MS) {
            return 350;
        }
        QuestLog.step("Romeo", "potion → Juliet");
        lastTalkMs = System.currentTimeMillis();
        QuestNpcApproachHelper.approachAndTalk("Juliet", QuestJulietHouseHelper.JULIET, JULIET_D);
        return 700;
    }

    private int toApothecary() {
        if (QuestJulietHouseHelper.inJulietRoom()) {
            QuestLog.step("Romeo", "Apothecary: eerst omlaag uit Juliets kamer");
            QuestJulietHouseHelper.leaveToGround();
            return 700;
        }
        QuestLog.step("Romeo", "Apothecary — berries omruilen voor potion");
        return talkVia(apothecary, APOTHECARY_D, "Apothecary");
    }

    private int talkVia(QuestNpcApproachHelper approach, QuestHelperDialogSteps dialog, String label) {
        if (approach.talk(dialog)) {
            return 700;
        }
        QuestLog.step("Romeo", label + ": geen klik gelukt → retry");
        return 800;
    }

    /** Cadava nodig vanaf stap 10 t/m 40 zolang berries én potion ontbreken (CombatBot-prep). */
    private boolean needsCadava(int step) {
        if (step < 10 || step >= 50) {
            return false;
        }
        return !hasBerries() && !hasPotion();
    }

    /**
     * Bank vóór GE via {@link QuestBankGePrep} (unnoted withdraw, alleen noted/mixed storten,
     * tekort in bank+inv via GE). Failsafe: 45 s geen voortgang → berries van de Cadava bush,
     * zelfde doel en zonder coins.
     *
     * @return delay zolang de prep bezig is, of {@code null} als de quest-stap door mag
     */
    private Integer supplyCadava(int step) {
        String sig = "berries=" + QuestActions.unnotedCount(CADAVA_BERRIES) + " coins=" + coins();
        long now = System.currentTimeMillis();
        if (!sig.equals(supplySig)) {
            supplySig = sig;
            supplySinceMs = now;
        } else if (supplySinceMs == 0L) {
            supplySinceMs = now;
        } else if (!cadavaViaBush && now - supplySinceMs > SUPPLY_STALL_MS) {
            supplySinceMs = now;
            cadavaViaBush = true;
            QuestLog.force("Romeo", "45s geen cadava via bank/GE (" + sig
                    + ") → Cadava bush zuidoost Varrock");
        }

        if (cadavaViaBush) {
            return pickCadavaFromBush();
        }

        QuestLog.step("Romeo", "cadava berries via bank → GE (stap " + step + ")");
        if (QuestBankGePrep.ensureUnnoted(BERRIES)) {
            return 600;
        }
        // false = berries unnoted op zak en bank/GE is al gesloten voor de walk.
        QuestLog.step("Romeo", "cadava berries op zak");
        return null;
    }

    /** Gratis route: bush aanklikken, daarna eventuele losse berries oppakken. */
    private int pickCadavaFromBush() {
        if (QuestActions.interactObject("Cadava bush", "Pick-from", CADAVA_BUSH)) {
            return 700;
        }
        if (QuestActions.pickup(CADAVA_BERRIES)) {
            return 700;
        }
        QuestLog.step("Romeo", "→ Cadava bush " + CADAVA_BUSH.getX() + "," + CADAVA_BUSH.getY());
        QuestActions.walkTo(CADAVA_BUSH, 2);
        return 700;
    }

    /**
     * Zelfde stap + zelfde items te lang → deur/walk-throttles van de approach-helpers vrijgeven
     * zodat dezelfde NPC opnieuw benaderd wordt (geen ander doel, geen andere route).
     */
    private void watchStepStall(int step) {
        String sig = step + "|" + hasBerries() + "|" + hasPotion();
        long now = System.currentTimeMillis();
        if (!sig.equals(stepSig)) {
            stepSig = sig;
            stepSinceMs = now;
            return;
        }
        if (stepSinceMs == 0L) {
            stepSinceMs = now;
            return;
        }
        if (now - stepSinceMs > STEP_STALL_MS) {
            stepSinceMs = now;
            romeo.reset();
            lawrence.reset();
            apothecary.reset();
            WorldPoint me = QuestActions.local();
            QuestLog.force("Romeo", "30s geen voortgang op stap " + step
                    + (me != null ? " (" + me.getX() + "," + me.getY() + ",p" + me.getPlane() + ")" : "")
                    + " → approach reset");
        }
    }

    private static boolean hasBerries() {
        return Inventory.contains(CADAVA_BERRIES);
    }

    private static boolean hasPotion() {
        return Inventory.contains(CADAVA_POTION);
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
