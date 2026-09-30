package com.lonebot.example.imps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Per-account dagelijkse actie-log: {@code ~/.lonebot/accounts/{RSN}/logs/imps-YYYY-MM-DD.log}
 */
public final class ImpActionLog {

    private static final Logger log = LoggerFactory.getLogger(ImpActionLog.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile String lastLine = "";
    private static volatile long lastWriteMs;

    private ImpActionLog() {
    }

    public static void action(String accountKey, String message) {
        write(accountKey, "ACT", message);
    }

    public static void tryDo(String accountKey, String message) {
        write(accountKey, "TRY", message);
    }

    public static void status(String accountKey, String message) {
        write(accountKey, "STS", message);
    }

    public static void warn(String accountKey, String message) {
        write(accountKey, "WRN", message);
    }

    private static void write(String accountKey, String level, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String line = message.trim();
        // Dedup: zelfde regel binnen 400ms niet opnieuw
        long now = System.currentTimeMillis();
        if (line.equals(lastLine) && now - lastWriteMs < 400L) {
            return;
        }
        lastLine = line;
        lastWriteMs = now;

        File f = logFile(accountKey);
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                log.warn("[ImpLog] mkdir fail {}", parent);
                return;
            }
            String stamp = LocalDateTime.now().format(TS);
            try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(f, true)))) {
                w.println(stamp + " [" + level + "] " + line);
            }
        } catch (Exception e) {
            log.debug("[ImpLog] write fail: {}", e.toString());
        }
    }

    private static File logFile(String accountKey) {
        String safe = sanitize(accountKey);
        String day = LocalDate.now().format(DAY);
        return new File(System.getProperty("user.home") + File.separator + ".lonebot"
                + File.separator + "accounts" + File.separator + safe
                + File.separator + "logs", "imps-" + day + ".log");
    }

    private static String sanitize(String accountKey) {
        if (accountKey == null || accountKey.isBlank()) {
            return "_default";
        }
        return accountKey.trim().replace('\u00A0', ' ')
                .replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
