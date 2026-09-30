package com.lonebot.example.quest.scripts;

import com.lonebot.example.quest.QuestBotScript;
import com.lonebot.example.quest.QuestBotTarget;
import com.lonebot.example.quest.QuestLog;
import com.lonebot.example.quest.helpers.QuestActions;
import com.lonebot.example.quest.helpers.QuestBankGePrep;
import com.lonebot.example.quest.helpers.QuestDialogHelper;
import com.lonebot.example.quest.helpers.QuestGePrices;
import com.lonebot.example.quest.helpers.QuestHelperDialogSteps;
import com.lonebot.example.quest.helpers.QuestNpcApproachHelper;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.api.domain.items.IInventoryItem;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.api.magic.SpellBook;
import net.storm.sdk.entities.NPCs;
import net.storm.sdk.entities.TileObjects;
import net.storm.sdk.game.Combat;
import net.storm.sdk.game.Skills;
import net.storm.sdk.game.Vars;
import net.storm.sdk.items.Bank;
import net.storm.sdk.items.BankWithdrawHelper;
import net.storm.sdk.items.Equipment;
import net.storm.sdk.items.Inventory;
import net.storm.sdk.items.MeleeWeaponRequirements;
import net.storm.sdk.items.Shop;
import net.storm.sdk.magic.Magic;
import net.storm.sdk.movement.Movement;
import net.storm.sdk.quests.Quests;
import net.storm.sdk.utils.Sleep;
import net.storm.sdk.widgets.Dialog;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Vampire Slayer — full CombatBot port (varp 178).
 * Fire Strike → Wind Strike → melee; coffin once; stake/beer/garlic/hammer supply.
 */
public final class VampireSlayerScript implements QuestBotScript {

    public static final int VARP = 178;

    private static final WorldPoint MORGAN = new WorldPoint(3098, 3268, 0);
    private static final WorldPoint MORGAN_INSIDE = new WorldPoint(3098, 3267, 0);
    private static final WorldPoint GARLIC_CUPBOARD = new WorldPoint(3102, 3266, 1);
    private static final WorldPoint GARLIC_APPROACH = new WorldPoint(3101, 3266, 1);
    private static final WorldPoint[] GARLIC_PATH = {
            new WorldPoint(3100, 3268, 1),
            new WorldPoint(3100, 3267, 1),
            GARLIC_APPROACH
    };
    private static final WorldPoint DRAYNOR_BANK = new WorldPoint(3093, 3243, 0);
    private static final WorldPoint HARLOW = new WorldPoint(3222, 3398, 0);
    private static final WorldPoint BARTENDER = new WorldPoint(3226, 3400, 0);
    private static final WorldPoint MANOR_DOOR_STAND = new WorldPoint(3108, 3351, 0);
    private static final WorldPoint MANOR_LARGE_DOOR = new WorldPoint(3108, 3353, 0);
    private static final WorldPoint MANOR_STAIRS = new WorldPoint(3115, 3357, 0);
    private static final WorldPoint COFFIN = new WorldPoint(3078, 9772, 0);
    private static final int MANOR_INTERIOR_MIN_Y = 3354;

    private static final QuestBankGePrep.Need HAMMER = new QuestBankGePrep.Need("Hammer", 1, QuestGePrices.HAMMER);

    private static final int MIN_FOOD = 5;
    private static final int MIN_MIND = 40;
    private static final int MIN_AIR = 80;
    private static final int MIN_RUNES_FIGHT = 5;

    private static final String[] STAFF_PRIORITY = {
            "Staff of fire", "Mystic fire staff", "Fire staff",
            "Staff of air", "Mystic air staff", "Air staff", "Staff"
    };
    private static final String[] MELEE_PRIORITY = {
            "Rune scimitar", "Adamant scimitar", "Mithril scimitar", "Steel scimitar",
            "Rune sword", "Adamant sword", "Mithril sword", "Steel sword",
            "Iron scimitar", "Bronze scimitar"
    };
    private static final String[] FOOD_PRIORITY = {
            "Swordfish", "Lobster", "Tuna", "Salmon", "Trout", "Herring", "Sardine",
            "Shrimps", "Anchovies", "Cooked meat", "Cooked chicken", "Bread"
    };

    private static final QuestHelperDialogSteps MORGAN_D = QuestHelperDialogSteps.of(
            "Ok, I'm up for an adventure.",
            "Yes.",
            "Please tell me about the vampyre."
    );
    private static final QuestHelperDialogSteps HARLOW_D = QuestHelperDialogSteps.of(
            "I've come to talk to you about slaying vampires.",
            "I've come to talk to you about slaying vampyres.",
            "Ok, thanks.",
            "Yes please.",
            "Yes."
    );
    private static final QuestHelperDialogSteps BEER_D = QuestHelperDialogSteps.of(
            "I'll have a beer please.",
            "I'd like a beer please.",
            "Beer please."
    );

    private boolean coffinOpenedOnce;
    private boolean harlowSaidGoFindStake;
    private int lastAttackedCountIndex = -1;
    private final Map<Integer, Long> notOurCountUntil = new HashMap<>();
    private long lastNpcMs;
    private long lastEatMs;
    private long lastAutocastMs;
    private long phaseStartMs;
    private int lastPhase = -1;
    private int garlicPathIdx;

    @Override
    public String displayName() {
        return "Vampire Slayer";
    }

    @Override
    public QuestBotTarget target() {
        return QuestBotTarget.VAMPYRE_SLAYER;
    }

    @Override
    public Quest runeliteQuest() {
        return Quest.VAMPYRE_SLAYER;
    }

    @Override
    public void reset() {
        coffinOpenedOnce = false;
        harlowSaidGoFindStake = false;
        lastAttackedCountIndex = -1;
        notOurCountUntil.clear();
        lastNpcMs = 0L;
        lastEatMs = 0L;
        lastAutocastMs = 0L;
        phaseStartMs = 0L;
        lastPhase = -1;
        garlicPathIdx = 0;
    }

    @Override
    public boolean isFinished() {
        return Quests.isFinished(Quest.VAMPYRE_SLAYER) || readVarp() >= 3;
    }

    @Override
    public int loop() {
        if (isFinished()) {
            QuestLog.step("Vamp", "klaar");
            return 1200;
        }
        if (QuestDialogHelper.handle(MORGAN_D)
                || QuestDialogHelper.handle(HARLOW_D)
                || QuestDialogHelper.handle(BEER_D)
                || Dialog.isOpen()) {
            if (Dialog.isOpen() && Dialog.canContinue()) {
                Dialog.continueSpace();
            }
            pollHarlowStakeChat();
            return 400;
        }
        if (maybeEat()) {
            return 500;
        }
        int phase = readVarp();
        if (phase != lastPhase) {
            QuestLog.force("Vamp", "phase " + lastPhase + " → " + phase);
            lastPhase = phase;
            phaseStartMs = System.currentTimeMillis();
            if (phase != 1) {
                harlowSaidGoFindStake = false;
            }
            if (phase < 2) {
                coffinOpenedOnce = false;
            }
        }
        if (System.currentTimeMillis() - phaseStartMs > 5 * 60_000L) {
            QuestLog.force("Vamp", "phase failsafe reset (" + phase + ")");
            garlicPathIdx = 0;
            coffinOpenedOnce = false;
            phaseStartMs = System.currentTimeMillis();
        }
        if (phase <= 0) {
            return phaseMorgan();
        }
        if (phase == 1) {
            return phasePrepare();
        }
        return phaseManorAndCount();
    }

    private int phaseMorgan() {
        QuestLog.step("Vamp", "Morgan — start quest");
        WorldPoint me = QuestActions.local();
        if (me != null && me.distanceTo(MORGAN) > 8) {
            Movement.walkTo(MORGAN);
            return 600;
        }
        if (me != null && me.distanceTo(MORGAN) > 3) {
            Movement.walkTo(MORGAN_INSIDE);
            return 500;
        }
        return talkNpc("Morgan", MORGAN, MORGAN_D);
    }

    private int phasePrepare() {
        if (!hasGarlic()) {
            return getGarlic();
        }
        if (!hasHammer()) {
            QuestLog.step("Vamp", "Hammer bank/GE");
            if (QuestBankGePrep.ensureUnnoted(HAMMER)) {
                return 600;
            }
            if (Shop.isOpen()) {
                Shop.buyOne("Hammer");
                return 700;
            }
        }
        if (!hasStake()) {
            return getStake();
        }
        int combat = ensureCombatKit();
        if (combat > 0) {
            return combat;
        }
        QuestLog.step("Vamp", "kit klaar → manor");
        return phaseManorAndCount();
    }

    private int getGarlic() {
        WorldPoint me = QuestActions.local();
        if (me == null) {
            return 600;
        }
        if (me.distanceTo(MORGAN) > 28) {
            QuestLog.step("Vamp", "naar Morgan huis voor garlic");
            Movement.walkTo(MORGAN);
            return 700;
        }
        if (me.getPlane() == 0) {
            QuestLog.step("Vamp", "Climb-up voor cupboard");
            if (QuestActions.interactObjectAny(MORGAN_INSIDE, "Climb-up", "Staircase", "Stairs")) {
                return 800;
            }
            Movement.walkTo(MORGAN_INSIDE);
            return 600;
        }
        // upstairs path around bed
        if (garlicPathIdx < GARLIC_PATH.length) {
            WorldPoint node = GARLIC_PATH[garlicPathIdx];
            if (me.distanceTo(node) > 2) {
                QuestLog.step("Vamp", "garlic pad → " + node.getX() + "," + node.getY());
                Movement.walkTo(node);
                return 500;
            }
            garlicPathIdx++;
        }
        QuestLog.step("Vamp", "Search cupboard garlic");
        ITileObject cup = TileObjects.getNearest(o -> o != null && o.getWorldLocation() != null
                && o.getWorldLocation().distanceTo(GARLIC_CUPBOARD) <= 2
                && o.getName() != null
                && o.getName().toLowerCase(Locale.ROOT).contains("cupboard"));
        if (cup != null) {
            if (cup.hasAction("Open")) {
                cup.interact("Open");
                return 700;
            }
            if (cup.interact("Search") || cup.interact("Search Cupboard")) {
                return 800;
            }
        }
        QuestActions.interactObjectAny(GARLIC_APPROACH, "Search", "Cupboard");
        return 700;
    }

    private int getStake() {
        if (harlowSaidGoFindStake) {
            QuestLog.step("Vamp", "Harlow: stake elders — bank check");
            if (!Bank.isOpen()) {
                Movement.walkTo(DRAYNOR_BANK);
                Bank.open();
                return 700;
            }
            BankWithdrawHelper.resolveBlockingBankInput();
            if (Bank.contains("Stake")) {
                Bank.withdraw("Stake", 1);
                return 600;
            }
            QuestLog.force("Vamp", "stake niet in bank — Harlow blok");
            return 1500;
        }
        if (!hasBeer()) {
            QuestLog.step("Vamp", "Beer @ Blue Moon");
            if (Shop.isOpen()) {
                Shop.buyOne("Beer");
                Shop.buyOne("Asgarnian ale");
                return 700;
            }
            return talkNpc("Bartender", BARTENDER, BEER_D);
        }
        QuestLog.step("Vamp", "Dr Harlow — stake");
        if (hasBeer() && !hasStake()) {
            if (QuestActions.useItemOnNpc("Beer", "Dr Harlow")
                    || QuestActions.useItemOnNpc("Asgarnian ale", "Dr Harlow")) {
                return 700;
            }
        }
        return talkNpc("Dr Harlow", HARLOW, HARLOW_D);
    }

    private int phaseManorAndCount() {
        if (!hasStake() || !hasHammer()) {
            QuestLog.step("Vamp", "varp2 guard — stake/hammer ontbreekt");
            return phasePrepare();
        }
        int combat = ensureCombatKit();
        if (combat > 0) {
            return combat;
        }
        WorldPoint me = QuestActions.local();
        if (me == null) {
            return 600;
        }
        boolean crypt = me.getY() >= 9700 && me.getY() <= 9800;
        if (crypt) {
            return openCoffinAndKill(me);
        }
        boolean insideManor = me.getPlane() == 0
                && me.getX() >= 3096 && me.getX() <= 3128
                && me.getY() >= MANOR_INTERIOR_MIN_Y && me.getY() <= 3372;
        if (insideManor) {
            QuestLog.step("Vamp", "stairs naar crypt");
            if (QuestActions.interactObjectAny(MANOR_STAIRS, "Climb-down", "Walk-down",
                    "Staircase", "Stairs")) {
                return 800;
            }
            Movement.walkTo(MANOR_STAIRS);
            return 600;
        }
        QuestLog.step("Vamp", "Draynor Manor deur");
        if (me.distanceTo(MANOR_LARGE_DOOR) <= 6) {
            ITileObject door = TileObjects.getNearest(MANOR_LARGE_DOOR, "Large door", "Door");
            if (door != null && (door.interact("Open") || door.interact("Open-door"))) {
                return 900;
            }
        }
        Movement.walkTo(MANOR_DOOR_STAND);
        return 600;
    }

    private int openCoffinAndKill(WorldPoint me) {
        if (Combat.isInCombat()) {
            QuestLog.step("Vamp", "in combat Count");
            maybeRefreshAutocast();
            return 400;
        }
        if (me.distanceTo(COFFIN) > 8) {
            Movement.walkTo(COFFIN);
            return 600;
        }
        if (!coffinOpenedOnce) {
            QuestLog.step("Vamp", "Open coffin (1×)");
            if (QuestActions.interactObject("Coffin", "Open", COFFIN)
                    || QuestActions.interactObject("Coffin", "Search", COFFIN)) {
                coffinOpenedOnce = true;
                return 900;
            }
            return 700;
        }
        INPC count = findOurCount();
        if (count != null) {
            lastAttackedCountIndex = count.getIndex();
            if (count.interact("Attack")) {
                QuestLog.step("Vamp", "Attack Count Draynor #" + lastAttackedCountIndex);
                return 500;
            }
        }
        QuestLog.step("Vamp", "Count niet zichtbaar — Search coffin");
        QuestActions.interactObject("Coffin", "Search", COFFIN);
        return 800;
    }

    private INPC findOurCount() {
        long now = System.currentTimeMillis();
        notOurCountUntil.entrySet().removeIf(e -> e.getValue() < now);
        return NPCs.getNearest(n -> {
            if (n == null || n.getName() == null) {
                return false;
            }
            if (!n.getName().toLowerCase(Locale.ROOT).contains("count draynor")) {
                return false;
            }
            if (!n.hasAction("Attack")) {
                return false;
            }
            Long until = notOurCountUntil.get(n.getIndex());
            return until == null || until < now;
        });
    }

    /** Fire Strike → Wind Strike → melee. 0 = ready. */
    private int ensureCombatKit() {
        SpellBook.Standard preferred = preferredSpell();
        if (preferred != null) {
            return ensureMageReady(preferred);
        }
        return ensureMeleeReady();
    }

    private SpellBook.Standard preferredSpell() {
        int mag = safeLevel(Skill.MAGIC);
        if (mag >= 13 && mageKitReadyOrBankable(true)) {
            return SpellBook.Standard.FIRE_STRIKE;
        }
        if (mag >= 1 && mageKitReadyOrBankable(false)) {
            return SpellBook.Standard.WIND_STRIKE;
        }
        return null;
    }

    private boolean mageKitReadyOrBankable(boolean fire) {
        if (hasMageKitOnPerson(fire)) {
            return true;
        }
        // optimistic: bank may have kit — bank trip handles miss
        return true;
    }

    private boolean hasMageKitOnPerson(boolean fire) {
        boolean staff = hasStaff(fire);
        int mind = Inventory.getCount(true, "Mind rune") + Equipment.getCount("Mind rune");
        int air = Inventory.getCount(true, "Air rune") + Equipment.getCount("Air rune");
        if (fire) {
            boolean fireRune = Inventory.contains("Fire rune") || Equipment.contains("Fire rune")
                    || hasFireStaffEquippedOrInv();
            return staff && mind >= MIN_RUNES_FIGHT && (fireRune || air >= MIN_RUNES_FIGHT);
        }
        return staff && mind >= MIN_RUNES_FIGHT && air >= MIN_RUNES_FIGHT;
    }

    private int ensureMageReady(SpellBook.Standard spell) {
        boolean fire = spell == SpellBook.Standard.FIRE_STRIKE;
        if (!hasMageKitOnPerson(fire)) {
            return bankForMage(fire);
        }
        if (!staffEquipped(fire)) {
            equipBestStaff(fire);
            return 500;
        }
        if (!Magic.isAutoCasting(spell)) {
            long now = System.currentTimeMillis();
            if (now - lastAutocastMs > 1200L) {
                QuestLog.step("Vamp", "autocast " + spell.getName());
                Magic.setAutoCast(spell, false);
                lastAutocastMs = now;
            }
            return 600;
        }
        return 0;
    }

    private int ensureMeleeReady() {
        unequipStaffIfAny();
        if (hasMeleeEquipped()) {
            return 0;
        }
        if (equipBestMeleeFromInv()) {
            return 500;
        }
        return bankForMelee();
    }

    private int bankForMage(boolean fire) {
        QuestLog.step("Vamp", "bank mage kit (" + (fire ? "fire" : "wind") + ")");
        if (!Bank.isOpen()) {
            Movement.walkTo(DRAYNOR_BANK);
            Bank.open();
            return 700;
        }
        BankWithdrawHelper.resolveBlockingBankInput();
        try {
            Bank.setNoted(false);
        } catch (Throwable ignored) {
        }
        withdrawStaff(fire);
        if (Bank.contains("Mind rune")) {
            Bank.withdrawAll("Mind rune");
            Sleep.sleep(200, 360);
        }
        if (!fire || !hasFireStaffEquippedOrInv()) {
            if (Bank.contains("Air rune")) {
                Bank.withdrawAll("Air rune");
                Sleep.sleep(200, 360);
            }
        }
        if (fire && !hasFireStaffEquippedOrInv() && Bank.contains("Fire rune")) {
            Bank.withdrawAll("Fire rune");
            Sleep.sleep(200, 360);
        }
        withdrawFood();
        Bank.leaveOpenForWalk();
        return 600;
    }

    private int bankForMelee() {
        QuestLog.step("Vamp", "bank melee wapen");
        if (!Bank.isOpen()) {
            Movement.walkTo(DRAYNOR_BANK);
            Bank.open();
            return 700;
        }
        BankWithdrawHelper.resolveBlockingBankInput();
        String w = MeleeWeaponRequirements.firstUsableInBank(MELEE_PRIORITY);
        if (w != null) {
            Bank.withdraw(w, 1);
            Sleep.sleep(220, 400);
        }
        withdrawFood();
        Bank.leaveOpenForWalk();
        return 600;
    }

    private void withdrawStaff(boolean fire) {
        for (String s : STAFF_PRIORITY) {
            if (fire && !s.toLowerCase(Locale.ROOT).contains("fire")
                    && !s.equalsIgnoreCase("Staff")) {
                continue;
            }
            if (!fire && s.toLowerCase(Locale.ROOT).contains("fire")) {
                continue;
            }
            if (Bank.contains(s)) {
                Bank.withdraw(s, 1);
                Sleep.sleep(200, 360);
                return;
            }
        }
        for (String s : STAFF_PRIORITY) {
            if (Bank.contains(s)) {
                Bank.withdraw(s, 1);
                Sleep.sleep(200, 360);
                return;
            }
        }
    }

    private void withdrawFood() {
        int have = countFood();
        if (have >= MIN_FOOD) {
            return;
        }
        for (String f : FOOD_PRIORITY) {
            if (Bank.contains(f)) {
                Bank.withdraw(f, Math.min(10, MIN_FOOD - have));
                Sleep.sleep(200, 360);
                return;
            }
        }
    }

    private void maybeRefreshAutocast() {
        SpellBook.Standard s = preferredSpell();
        if (s != null && !Magic.isAutoCasting(s)) {
            Magic.setAutoCast(s, false);
        }
    }

    private boolean maybeEat() {
        try {
            if (Skills.getBoostedLevel(Skill.HITPOINTS) > 5) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastEatMs < 1800L) {
            return false;
        }
        for (String f : FOOD_PRIORITY) {
            IInventoryItem food = Inventory.getFirst(f);
            if (food != null && (food.interact("Eat") || food.interact("Drink"))) {
                lastEatMs = now;
                QuestLog.step("Vamp", "Eat " + f);
                return true;
            }
        }
        return false;
    }

    private void pollHarlowStakeChat() {
        // Lightweight: if stake appears, clear flag
        if (hasStake()) {
            harlowSaidGoFindStake = false;
        }
    }

    private int talkNpc(String name, WorldPoint tile, QuestHelperDialogSteps dialog) {
        long now = System.currentTimeMillis();
        if (now - lastNpcMs < 1600L) {
            return 400;
        }
        if (QuestNpcApproachHelper.approachAndTalk(name, tile, dialog)) {
            lastNpcMs = now;
            return 700;
        }
        Movement.walkTo(tile);
        return 600;
    }

    private static int readVarp() {
        try {
            return Vars.getVarp(VARP);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeLevel(Skill s) {
        try {
            return Skills.getLevel(s);
        } catch (Throwable t) {
            return 1;
        }
    }

    private static boolean hasGarlic() {
        return Inventory.contains("Garlic");
    }

    private static boolean hasHammer() {
        return Inventory.contains("Hammer") || Equipment.contains("Hammer");
    }

    private static boolean hasStake() {
        return Inventory.contains("Stake");
    }

    private static boolean hasBeer() {
        return Inventory.contains("Beer") || Inventory.contains("Asgarnian ale");
    }

    private static boolean hasStaff(boolean preferFire) {
        return staffEquipped(preferFire) || staffInInv(preferFire);
    }

    private static boolean staffEquipped(boolean preferFire) {
        for (String s : STAFF_PRIORITY) {
            if (preferFire && !s.toLowerCase(Locale.ROOT).contains("fire") && !s.equalsIgnoreCase("Staff")) {
                continue;
            }
            if (Equipment.contains(s)) {
                return true;
            }
        }
        return Equipment.contains(i -> i != null && i.getName() != null
                && i.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    private static boolean staffInInv(boolean preferFire) {
        for (String s : STAFF_PRIORITY) {
            if (preferFire && !s.toLowerCase(Locale.ROOT).contains("fire") && !s.equalsIgnoreCase("Staff")) {
                continue;
            }
            if (Inventory.contains(s)) {
                return true;
            }
        }
        return Inventory.contains(i -> i != null && i.getName() != null
                && i.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    private static boolean hasFireStaffEquippedOrInv() {
        return Equipment.contains(i -> i != null && i.getName() != null
                && i.getName().toLowerCase(Locale.ROOT).contains("fire")
                && i.getName().toLowerCase(Locale.ROOT).contains("staff"))
                || Inventory.contains(i -> i != null && i.getName() != null
                && i.getName().toLowerCase(Locale.ROOT).contains("fire")
                && i.getName().toLowerCase(Locale.ROOT).contains("staff"));
    }

    private void equipBestStaff(boolean fire) {
        for (String s : STAFF_PRIORITY) {
            if (fire && !s.toLowerCase(Locale.ROOT).contains("fire") && !s.equalsIgnoreCase("Staff")) {
                continue;
            }
            IInventoryItem it = Inventory.getFirst(s);
            if (it != null && (it.interact("Wield") || it.interact("Equip"))) {
                QuestLog.step("Vamp", "Wield " + s);
                return;
            }
        }
    }

    private void unequipStaffIfAny() {
        try {
            IInventoryItem staff = Equipment.getFirst(i -> i != null && i.getName() != null
                    && i.getName().toLowerCase(Locale.ROOT).contains("staff"));
            if (staff != null) {
                if (staff.interact("Remove") || staff.interact("Unequip")) {
                    QuestLog.step("Vamp", "unequip staff voor melee");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean hasMeleeEquipped() {
        return Equipment.contains(i -> i != null && i.getName() != null
                && isMeleeName(i.getName())
                && MeleeWeaponRequirements.canWield(i.getName()));
    }

    private boolean equipBestMeleeFromInv() {
        for (String w : MELEE_PRIORITY) {
            if (!MeleeWeaponRequirements.canWield(w)) {
                continue;
            }
            IInventoryItem it = Inventory.getFirst(w);
            if (it != null && (it.interact("Wield") || it.interact("Equip"))) {
                QuestLog.step("Vamp", "Wield " + w);
                return true;
            }
        }
        return false;
    }

    private static boolean isMeleeName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("scimitar") || n.contains("sword") || n.contains("battleaxe")
                || n.contains("mace") || n.contains("warhammer") || n.contains("dagger")
                || n.contains("axe") && !n.contains("pickaxe");
    }

    private int countFood() {
        int n = 0;
        for (String f : FOOD_PRIORITY) {
            n += Inventory.getCount(f);
        }
        return n;
    }
}
