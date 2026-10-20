/**
* BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
*
* Copyright (c) 2026 BigBlueButton Inc. and by respective authors (see below).
*
* This program is free software; you can redistribute it and/or modify it under the
* terms of the GNU Lesser General Public License as published by the Free Software
* Foundation; either version 3.0 of the License, or (at your option) any later
* version.
*
* BigBlueButton is distributed in the hope that it will be useful, but WITHOUT ANY
* WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
* PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License along
* with BigBlueButton; if not, see <http://www.gnu.org/licenses/>.
*
*/

package org.bigbluebutton.common2.errortracking;

import ch.qos.logback.classic.Level;
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Optional error reporting for the JVM components: call init first in main, declare the appender
 * in logback.xml. BBB_SENTRY_* settings come from the environment, else from sentry.* config. */
public final class ErrorTracking {
    private static final Logger log = LoggerFactory.getLogger(ErrorTracking.class);

    private static final Path RELEASE_FILE = Paths.get("/etc/bigbluebutton/bigbluebutton-release");

    /** Read by {@link ErrorTrackingAppender}, which logback may start before {@link #init}. */
    static volatile Level eventLevel = Level.ERROR;
    static volatile Level breadcrumbLevel = Level.INFO;

    private ErrorTracking() {
    }

    /** Never throws: a bad setting leaves the component running without reporting. */
    public static boolean init(String component) {
        return init(component, key -> null);
    }

    /** @param config the component's settings by camelCase key; the environment overrides them */
    public static boolean init(String component, Function<String, String> config) {
        return init(component, System.getenv(), config);
    }

    static boolean init(String component, Map<String, String> env, Function<String, String> config) {
        try {
            String dsn = dsnFor(component, env, config);
            if (dsn == null) {
                return false;
            }
            Level issues = level(setting(component, "LEVEL", env, config), Level.ERROR);
            Level breadcrumbs = level(setting(component, "BREADCRUMB_LEVEL", env, config), Level.INFO);
            Anonymiser.Mode anonymise = Anonymiser.Mode.parse(setting(component, "ANONYMISE", env, config));
            Anonymiser anonymiser = new Anonymiser(anonymise);
            String serverName = serverName(env.get("BBB_URL"));

            Sentry.init(options -> {
                options.setDsn(dsn);
                options.setEnvironment(orDefault(setting(component, "ENVIRONMENT", env, config), "production"));
                options.setRelease(component + "@" + bbbRelease());
                if (serverName != null) {
                    options.setServerName(serverName);
                }
                options.setSampleRate(sampleRate(setting(component, "SAMPLE_RATE", env, config)));
                options.setTag("component", component);
                // BBB_SENTRY_* only: no sentry.properties, no SENTRY_* variables.
                options.setEnableExternalConfiguration(false);
                // The SDK's shutdown hook flushes for 15 s by default.
                options.setFlushTimeoutMillis(2000);
                options.setBeforeSend((event, hint) -> anonymiser.anonymiseEvent(event));
                options.setBeforeBreadcrumb((breadcrumb, hint) -> anonymiser.anonymiseBreadcrumb(breadcrumb));
                // Records from LEVEL up are also sent as log entries.
                options.getLogs().setEnabled(true);
                options.getLogs().setBeforeSend(anonymiser::anonymiseLog);
            });
            eventLevel = issues;
            breadcrumbLevel = breadcrumbs;

            log.info("Error reporting to {} enabled for {} (issues from {}, breadcrumbs from {}, personal data: {})",
                    URI.create(dsn).getHost(), component, issues, breadcrumbs,
                    anonymise.name().toLowerCase(Locale.ROOT));
            return true;
        } catch (Exception | LinkageError e) {
            log.warn("Error reporting not started for {}: {}", component, e.toString());
            return false;
        }
    }

    static String setting(String component, String name, Map<String, String> env) {
        String suffix = component.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        String value = trimmed(env.get("BBB_SENTRY_" + name + "_" + suffix));
        return value != null ? value : trimmed(env.get("BBB_SENTRY_" + name));
    }

    /** The environment first, else the component's own setting, by camelCase key. */
    static String setting(String component, String name, Map<String, String> env, Function<String, String> config) {
        String value = setting(component, name, env);
        return value != null ? value : trimmed(config.apply(configKey(name)));
    }

    static String configKey(String name) {
        StringBuilder key = new StringBuilder();
        for (String part : name.toLowerCase(Locale.ROOT).split("_")) {
            key.append(key.length() == 0 ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1));
        }
        return key.toString();
    }

    static String dsnFor(String component, Map<String, String> env, Function<String, String> config) {
        String dsn = setting(component, "DSN", env, config);
        if (dsn == null || "off".equalsIgnoreCase(dsn)) {
            return null;
        }
        return dsn;
    }

    static Level level(String value, Level fallback) {
        String name = trimmed(value);
        if (name == null) {
            return fallback;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "debug": return Level.DEBUG;
            case "info": return Level.INFO;
            case "warn":
            case "warning": return Level.WARN;
            case "error":
            case "fatal": return Level.ERROR;
            default: return fallback;
        }
    }

    static double sampleRate(String value) {
        try {
            double rate = Double.parseDouble(trimmed(value));
            return rate >= 0.0 && rate <= 1.0 ? rate : 1.0;
        } catch (NullPointerException | NumberFormatException e) {
            return 1.0;
        }
    }

    static String serverName(String bbbUrl) {
        try {
            String url = trimmed(bbbUrl);
            return url == null ? null : URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String bbbRelease() {
        try {
            for (String line : Files.readAllLines(RELEASE_FILE, StandardCharsets.UTF_8)) {
                if (line.startsWith("BIGBLUEBUTTON_RELEASE=")) {
                    return line.substring("BIGBLUEBUTTON_RELEASE=".length()).trim();
                }
            }
        } catch (Exception e) {
            // A development checkout has no release file.
        }
        return "unknown";
    }

    static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    static String orDefault(String value, String fallback) {
        String result = trimmed(value);
        return result == null ? fallback : result;
    }
}
