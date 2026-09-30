package net.storm.sdk.interact;

import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.actors.INPC;
import net.storm.sdk.bot.BotRuntime;
import net.storm.sdk.game.Camera;
import net.storm.sdk.game.Static;
import net.storm.sdk.input.Mouse;
import net.storm.sdk.input.MouseSettings;
import net.storm.sdk.interact.mouse.MouseManager;
import net.storm.sdk.movement.WalkUiZones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Shape;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NPC interact: voor {@code Attack} eerst <b>snelle muisklik</b> op de hull
 * (rode X op de NPC), daarna invoke als fallback.
 */
public final class AimInteractHelper {

    private static final Logger log = LoggerFactory.getLogger(AimInteractHelper.class);

    private static volatile long lastTurnMs;
    private static volatile String lastDetail = "";

    private AimInteractHelper() {
    }

    public static String getLastDetail() {
        return lastDetail != null ? lastDetail : "";
    }

    /**
     * Interact met NPC.
     * <p>
     * {@code Attack}: <b>muisklik eerst</b> (rode X op de NPC), invoke als de klik mist.<br>
     * {@code Dismiss} e.d.: <b>alleen invoke</b> — left-click = eerste menu-optie (vaak Talk-to).
     */
    public static boolean interactNpc(INPC npc, String action) {
        if (npc == null || action == null) {
            lastDetail = "npc/action null";
            return false;
        }
        String name = npc.getName() != null ? npc.getName() : "?";

        if (isCombatAction(action)) {
            return interactCombat(npc, action, name);
        }

        // Nested menu-opties: NOOIT left-click (dat wordt de eerste optie, bv. Talk-to / Small Net)
        if (mustInvokeOnly(action)) {
            return invokeNpc(npc, action, name);
        }

        // Talk-to op combat-NPC (Monk e.d.): left-click = Attack → altijd invoke
        if (isTalkToAction(action) && npcHasAction(npc, "Attack")) {
            BotRuntime.logConsole("[Aim] Talk-to op Attack-NPC → invoke (LMB zou Attacken)");
            return invokeNpc(npc, action, name);
        }

        // Talk-to / Travel e.d. (geen Attack): muis ok, daarna camera, daarna invoke
        Point p = ClickPoints.forNpc(npc);
        if (isValidClick(p) && tryVerifiedMouseClick(npc, p, action, name, false)) {
            return true;
        }
        WorldPoint wp = npc.getWorldLocation();
        if (wp != null && turnToward(wp)) {
            sleep(180 + ThreadLocalRandom.current().nextInt(120));
            p = ClickPoints.forNpc(npc);
            if (isValidClick(p) && tryVerifiedMouseClick(npc, p, action, name, false)) {
                return true;
            }
        }
        return invokeNpc(npc, action, name);
    }

    /**
     * Acties die niet de left-click default zijn — left-click zou de verkeerde optie doen.
     * Fishing: Draynor left-click = Small Net; Bait/Lure/Cage/Harpoon zitten in het menu.
     */
    private static boolean mustInvokeOnly(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toLowerCase(Locale.ROOT).trim();
        return a.equals("dismiss")
                || a.equals("pickpocket")
                || a.equals("steal-from")
                || a.equals("lure")
                || a.equals("bait")
                || a.equals("net")
                || a.equals("small net")
                || a.equals("cage")
                || a.equals("harpoon")
                || a.equals("use-rod")
                || a.equals("knock-out")
                || a.startsWith("use ")
                || a.equals("exchange")
                || a.equals("collect")
                || a.startsWith("prune")
                || a.startsWith("pay");
    }

    private static boolean interactCombat(INPC npc, String action, String name) {
        // Echte LMB op de hull — OSRS tekent het rode kruisje op de klik, niet op hover/invoke.
        Point p = ClickPoints.forNpc(npc);
        if (isValidClick(p) && tryVerifiedMouseClick(npc, p, action, name, true)) {
            return true;
        }

        WorldPoint wp = npc.getWorldLocation();
        if (wp != null) {
            turnToward(wp);
            sleep(120 + ThreadLocalRandom.current().nextInt(100));
            p = ClickPoints.forNpc(npc);
            if (isValidClick(p) && tryVerifiedMouseClick(npc, p, action, name, true)) {
                return true;
            }
        }

        // Klik miste (imp bewogen) → invoke. X blijft op laatste LMB, niet 0,0.
        return invokeNpc(npc, action, name);
    }

    /**
     * Beweeg (snappy bij combat), herbereken NPC-punt, bevestig dat klik nog op NPC-hull zit.
     */
    private static boolean tryVerifiedMouseClick(INPC npc, Point aim, String action, String name, boolean snappy) {
        if (npc == null || aim == null) {
            return false;
        }
        Point start = aim;
        if (snappy) {
            // Directe move — geen lange Bezier (te traag voor bewegende NPC)
            if (!Mouse.moveRaw(start.getX(), start.getY()) && !MouseManager.moveTo(start)) {
                return false;
            }
            sleep(MouseSettings.scaleDelay(8 + ThreadLocalRandom.current().nextInt(18)));
        } else {
            if (!MouseManager.moveTo(start)) {
                return false;
            }
            sleep(MouseSettings.scaleDelay(25 + ThreadLocalRandom.current().nextInt(40)));
        }

        // Bevestig: NPC nog op/nabij klikpunt?
        Point now = ClickPoints.forNpc(npc);
        if (!isValidClick(now)) {
            lastDetail = "verify-fail offscreen " + action + " → " + name;
            log.info("[AimInteract] {}", lastDetail);
            return false;
        }
        int drift = Math.abs(now.getX() - start.getX()) + Math.abs(now.getY() - start.getY());
        if (drift > 55) {
            // NPC te ver bewogen tijdens aim → geen ground-klik (zou walken)
            lastDetail = "verify-fail drift=" + drift + " " + action + " → " + name;
            log.info("[AimInteract] {}", lastDetail);
            BotRuntime.logConsole("[Aim] skip mouse drift=" + drift + " → invoke");
            return false;
        }
        if (!clickPointStillOnNpc(npc, now)) {
            lastDetail = "verify-fail not-on-hull " + action + " → " + name;
            log.info("[AimInteract] {}", lastDetail);
            return false;
        }

        boolean ok = Mouse.clickOnly(now.getX(), now.getY(), true);
        if (ok) {
            lastDetail = (snappy ? "mouse-fast+verify " : "mouse+verify ") + action + " → " + name;
            log.info("[AimInteract] {}", lastDetail);
            BotRuntime.logConsole("[Aim] " + lastDetail);
        }
        return ok;
    }

    /** True als canvas-punt binnen/near convex hull van de NPC ligt. */
    public static boolean clickPointStillOnNpc(INPC npc, Point click) {
        if (npc == null || click == null) {
            return false;
        }
        return Boolean.TRUE.equals(Static.callOnClientThread(() -> {
            Client c = Static.getClient();
            if (c == null) {
                return false;
            }
            NPC raw = null;
            try {
                for (NPC n : c.getNpcs()) {
                    if (n != null && n.getIndex() == npc.getIndex()) {
                        raw = n;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (raw == null) {
                return false;
            }
            try {
                Shape hull = raw.getConvexHull();
                if (hull != null && hull.contains(click.getX(), click.getY())) {
                    return true;
                }
                // Tolerantie: dicht bij hull-center
                if (hull != null) {
                    java.awt.Rectangle b = hull.getBounds();
                    if (b != null) {
                        int cx = b.x + b.width / 2;
                        int cy = b.y + b.height / 2;
                        int d = Math.abs(click.getX() - cx) + Math.abs(click.getY() - cy);
                        return d <= Math.max(28, (b.width + b.height) / 2);
                    }
                }
            } catch (Throwable ignored) {
            }
            // Fallback: canvas-punt van huidige locatie dichtbij
            Point fresh = ClickPoints.forNpcOnClient(npc);
            if (fresh == null) {
                return false;
            }
            return Math.abs(fresh.getX() - click.getX()) + Math.abs(fresh.getY() - click.getY()) <= 40;
        }, false));
    }

    private static boolean invokeNpc(INPC npc, String action, String name) {
        boolean inv = MenuInteract.interactNpcByIndex(npc.getIndex(), action);
        lastDetail = inv
                ? "invoke " + action + " → " + name + " (" + MenuInteract.getLastProbeDetail() + ")"
                : "fail " + action + " → " + name;
        log.info("[AimInteract] {}", lastDetail);
        if (inv) {
            BotRuntime.logConsole("[Aim] " + lastDetail);
        }
        return inv;
    }

    private static boolean isTalkToAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toLowerCase(Locale.ROOT).trim();
        return a.equals("talk-to") || a.equals("talk to") || a.equals("talk");
    }

    private static boolean npcHasAction(INPC npc, String action) {
        if (npc == null || action == null) {
            return false;
        }
        try {
            return npc.hasAction(action);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isCombatAction(String action) {
        if (action == null) {
            return false;
        }
        String a = action.toLowerCase(Locale.ROOT);
        return a.equals("attack") || a.equals("fight") || a.startsWith("attack ");
    }

    public static boolean turnToward(WorldPoint target) {
        long now = System.currentTimeMillis();
        if (now - lastTurnMs < 900L) {
            return false;
        }
        if (target == null) {
            return false;
        }
        Boolean ok = Static.callOnClientThread(() -> {
            Client c = Static.getClient();
            if (c == null) {
                return false;
            }
            Player me = c.getLocalPlayer();
            if (me == null || me.getWorldLocation() == null) {
                return false;
            }
            WorldPoint from = me.getWorldLocation();
            int dx = target.getX() - from.getX();
            int dy = target.getY() - from.getY();
            if (dx == 0 && dy == 0) {
                return false;
            }
            int yaw = ((int) (Math.atan2(dx, dy) * 325.949D)) & 2047;
            try {
                c.setCameraYawTarget(yaw);
            } catch (Throwable t) {
                Camera.setYaw(yaw);
            }
            try {
                int pitch = c.getCameraPitch();
                if (pitch < 280) {
                    c.setCameraPitchTarget(Math.min(383, pitch + 60 + ThreadLocalRandom.current().nextInt(40)));
                }
            } catch (Throwable ignored) {
            }
            log.info("[AimInteract] camera yaw→{} toward {}", yaw, target);
            return true;
        }, false);
        if (Boolean.TRUE.equals(ok)) {
            lastTurnMs = now;
            return true;
        }
        return false;
    }

    public static boolean isValidClick(Point p) {
        if (p == null || p.getX() < 8 || p.getY() < 8) {
            return false;
        }
        try {
            Client c = Static.getClient();
            if (c != null && WalkUiZones.isOverUi(c, p)) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static boolean isNpcOnScreen(INPC npc) {
        return isValidClick(ClickPoints.forNpc(npc));
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
