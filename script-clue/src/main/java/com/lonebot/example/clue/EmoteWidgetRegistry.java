package com.lonebot.example.clue;

import net.storm.api.domain.widgets.IWidget;
import net.storm.api.widgets.Tab;
import net.storm.api.widgets.WidgetGroup;
import net.storm.sdk.bot.BotRuntime;
import net.storm.sdk.widgets.Tabs;
import net.storm.sdk.widgets.Widgets;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Emote panel — iface 216. CombatBot-achtig: Perform-actie + naam-match + BFS children.
 */
public final class EmoteWidgetRegistry {

    public static final int EMOTE_GROUP = WidgetGroup.EMOTES_GROUP_ID;
    private static final int[] CONTAINER_CHILDREN = {1, 0, 2, 3};
    private static long lastFailLogMs;

    private EmoteWidgetRegistry() {
    }

    public static boolean openPanel() {
        if (!Tabs.isOpen(Tab.EMOTES)) {
            Tabs.open(Tab.EMOTES);
            return false;
        }
        return container() != null;
    }

    public static boolean clickEmote(String emoteName) {
        if (emoteName == null || emoteName.isBlank()) {
            return false;
        }
        if (!openPanel()) {
            return false;
        }
        IWidget hit = findEmote(emoteName);
        if (hit == null) {
            logFail("widget ontbreekt: " + emoteName);
            return false;
        }
        String want = emoteName.trim();
        // CombatBot: eerst "Perform"
        if (hit.hasAction("Perform") && hit.interact("Perform")) {
            BotRuntime.logConsole("[Clue/Emote] Perform " + want);
            return true;
        }
        if (hit.interact(want) || clickByActions(hit, want)) {
            BotRuntime.logConsole("[Clue/Emote] " + want);
            return true;
        }
        logFail("klik mislukt: " + want);
        return false;
    }

    private static void logFail(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastFailLogMs < 1_500L) {
            return;
        }
        lastFailLogMs = now;
        BotRuntime.logConsole("[Clue/Emote] " + msg);
    }

    private static boolean clickByActions(IWidget w, String want) {
        String n = ClueScrollHelper.normalize(want);
        String[] tries = {
                "Perform",
                want,
                "Blow Raspberry",
                "Raspberry",
                "Jump for Joy",
                "Dance",
                "Wave",
                "Cheer",
                "Think",
                "Panic",
                "Bow",
                "Laugh",
                "Yawn",
                "Shrug",
                "Clap",
                "Headbang",
                "Beckon",
                "Salute",
                "Jig",
                "Spin"
        };
        for (String t : tries) {
            if ("Perform".equals(t) || ClueScrollHelper.normalize(t).equals(n)
                    || ClueScrollHelper.containsNorm(t, want)) {
                if (w.hasAction(t) && w.interact(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static IWidget findEmote(String emoteName) {
        String want = ClueScrollHelper.normalize(emoteName);
        IWidget container = container();
        if (container != null) {
            IWidget hit = bfsMatch(container, want);
            if (hit != null) {
                return hit;
            }
        }
        for (int child : CONTAINER_CHILDREN) {
            IWidget alt = Widgets.get(EMOTE_GROUP, child);
            if (alt == null || alt == container) {
                continue;
            }
            IWidget hit = bfsMatch(alt, want);
            if (hit != null) {
                return hit;
            }
        }
        return Widgets.getFirst(w -> w != null && !w.isHidden()
                && (w.getId() >>> 16) == EMOTE_GROUP
                && matches(w, want));
    }

    private static IWidget bfsMatch(IWidget root, String wantNorm) {
        if (root == null) {
            return null;
        }
        Deque<IWidget> q = new ArrayDeque<>();
        q.add(root);
        int guard = 0;
        while (!q.isEmpty() && guard++ < 400) {
            IWidget w = q.poll();
            if (w == null || w.isHidden()) {
                continue;
            }
            if (matches(w, wantNorm)) {
                return w;
            }
            IWidget[] kids = w.getChildren();
            if (kids == null) {
                continue;
            }
            for (IWidget k : kids) {
                if (k != null) {
                    q.add(k);
                }
            }
        }
        return null;
    }

    private static boolean matches(IWidget w, String wantNorm) {
        if (w == null || w.isHidden()) {
            return false;
        }
        String name = ClueScrollHelper.normalize(w.getName());
        String text = ClueScrollHelper.normalize(w.getText());
        if (!name.isEmpty() && (name.equals(wantNorm) || name.contains(wantNorm))) {
            return true;
        }
        if (!text.isEmpty() && (text.equals(wantNorm) || text.contains(wantNorm))) {
            return true;
        }
        String display = emoteDisplay(wantNorm);
        return w.hasAction(display) || w.hasAction("Perform");
    }

    private static String emoteDisplay(String norm) {
        if ("blow raspberry".equals(norm) || "raspberry".equals(norm)) {
            return "Blow Raspberry";
        }
        if ("jump for joy".equals(norm)) {
            return "Jump for Joy";
        }
        if (norm == null || norm.isEmpty()) {
            return "";
        }
        String[] p = norm.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String s : p) {
            if (s.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
        }
        return sb.toString();
    }

    private static IWidget container() {
        for (int child : CONTAINER_CHILDREN) {
            IWidget w = Widgets.get(EMOTE_GROUP, child);
            if (w != null && !w.isHidden()) {
                return w;
            }
        }
        return Widgets.get(EMOTE_GROUP, 1);
    }
}
