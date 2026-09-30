package net.storm.sdk.entities;

import net.runelite.api.Client;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.storm.api.domain.tiles.ITileObject;
import net.storm.sdk.game.Static;
import net.storm.sdk.input.Mouse;
import net.storm.sdk.interact.AimInteractHelper;
import net.storm.sdk.interact.ClickPoints;
import net.storm.sdk.interact.MenuInteract;
import net.storm.sdk.interact.mouse.MouseManager;

import java.util.concurrent.ThreadLocalRandom;

final class RlTileObject implements ITileObject {

    private final TileObject object;

    RlTileObject(TileObject object) {
        this.object = object;
    }

    TileObject raw() {
        return object;
    }

    @Override
    public int getId() {
        Integer v = Static.callOnClientThread(object::getId, -1);
        return v != null ? v : -1;
    }

    @Override
    public String getName() {
        return Static.callOnClientThread(() -> {
            Client c = Static.getClient();
            if (c == null) {
                return "";
            }
            ObjectComposition comp = MenuInteract.resolveObjectComposition(c, object.getId());
            return comp != null && comp.getName() != null ? comp.getName() : "";
        }, "");
    }

    @Override
    public WorldPoint getWorldLocation() {
        return Static.callOnClientThread(object::getWorldLocation, null);
    }

    @Override
    public boolean hasAction(String action) {
        return Boolean.TRUE.equals(Static.callOnClientThread(() -> {
            Client c = Static.getClient();
            if (c == null) {
                return false;
            }
            ObjectComposition comp = MenuInteract.resolveObjectComposition(c, object.getId());
            if (comp == null || comp.getActions() == null) {
                return false;
            }
            for (String a : comp.getActions()) {
                if (a != null && a.equalsIgnoreCase(action)) {
                    return true;
                }
            }
            return false;
        }, false));
    }

    @Override
    public boolean interact(String action) {
        // Nested menu (Bottom-floor / Climb-down / Top-floor): NOOIT left-click —
        // left-click = eerste optie = Climb-down (één verdieping) of Chop.
        if (mustInvokeObjectAction(action)) {
            return MenuInteract.interactObject(object, action);
        }
        // Chop down / Mine: left-click default — canvas-klik werkt betrouwbaar.
        if (tryMouseClick()) {
            return true;
        }
        if (MenuInteract.interactObject(object, action)) {
            return true;
        }
        return tryMouseClick();
    }

    /** Acties die niet de left-click default zijn. */
    private static boolean mustInvokeObjectAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        String a = action.toLowerCase(java.util.Locale.ROOT).trim();
        return a.equals("bottom-floor")
                || a.equals("top-floor")
                || a.startsWith("climb")
                || a.equals("open")
                || a.equals("enter")
                || a.equals("close")
                || a.equals("pass")
                || a.equals("cross")
                || a.equals("use")
                || a.startsWith("pay");
    }

    private boolean tryMouseClick() {
        Point p = ClickPoints.forTileObject(object);
        if (!AimInteractHelper.isValidClick(p)) {
            return false;
        }
        if (!MouseManager.moveTo(p)) {
            return false;
        }
        try {
            Thread.sleep(35 + ThreadLocalRandom.current().nextInt(55));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        // Hersample na move (camera/scroll)
        Point now = ClickPoints.forTileObject(object);
        if (!AimInteractHelper.isValidClick(now)) {
            now = p;
        }
        return Mouse.clickOnly(now.getX(), now.getY(), true);
    }
}
