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

import io.sentry.Breadcrumb;
import io.sentry.ISentryClient;
import io.sentry.SentryClient;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryLogLevel;
import io.sentry.SentryOptions;
import io.sentry.protocol.DebugImage;
import io.sentry.protocol.DebugMeta;
import io.sentry.protocol.Mechanism;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryId;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SdkVersion;
import io.sentry.protocol.SentryStackTrace;
import io.sentry.protocol.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Forwards the browser apps' error reports to Sentry, bounded and anonymised. Batch format:
 * {"items": [{"kind", "level", "message", "name", "stack", "url", "handled", "debugIds": {url: id},
 *   "context": {key: scalar}, "breadcrumbs": [{"category", "level", "message", "timestamp"}]}]} */
public final class ClientRelay {
    private static final Logger log = LoggerFactory.getLogger(ClientRelay.class);

    public static final Set<String> APPS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("bbb-html5", "bbb-learning-dashboard", "bbb-playback")));

    static final int MAX_ITEMS = 10;
    static final int MAX_MESSAGE = 2000;
    static final int MAX_STACK = 16000;
    static final int MAX_FRAMES = 50;
    static final int MAX_DEBUG_IMAGES = 10;
    static final int MAX_SHORT = 200;
    static final int MAX_BREADCRUMBS = 20;
    static final int MAX_BREADCRUMB_MESSAGE = 500;
    static final int MAX_CONTEXT_KEYS = 20;

    /** Per signed-in user: one broken tab must not spend a whole meeting's budget. */
    static final int USER_LIMIT = 30;
    /** Per app for reports without a session; nginx limits per address before. */
    static final int NO_SESSION_LIMIT = 300;
    static final long WINDOW_MILLIS = 5 * 60 * 1000L;

    /** Line and column numbers are bounded so they always fit an int. */
    private static final Pattern V8_FRAME = Pattern.compile("^\\s*at (?:(.+?) \\()?(.+?):(\\d{1,9}):(\\d{1,9})\\)?\\s*$");
    private static final Pattern GECKO_FRAME = Pattern.compile("^\\s*(?:(.*?)@)?(.+?):(\\d{1,9}):(\\d{1,9})\\s*$");
    private static final Pattern DEBUG_ID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Map<String, String> env;
    private final Function<String, String> config;
    private final Function<SentryOptions, ISentryClient> clientFactory;
    private final Map<String, Optional<ISentryClient>> clients = new ConcurrentHashMap<>();
    private final RateLimiter limiter = new RateLimiter(WINDOW_MILLIS, 10000);

    /** @param config bbb-web's Sentry settings, for every app; the environment overrides them */
    public ClientRelay(Function<String, String> config) {
        this(System.getenv(), config, SentryClient::new);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "client-relay-flush"));
    }

    ClientRelay(Map<String, String> env, Function<String, String> config,
                Function<SentryOptions, ISentryClient> clientFactory) {
        this.env = env;
        this.config = config;
        this.clientFactory = clientFactory;
    }

    public boolean isEnabled(String app) {
        return app != null && APPS.contains(app) && client(app) != null;
    }

    /** Never throws; returns how many events were sent. userId is null without a session. */
    public int report(String app, Object batch, String userId, String meetingId, String userAgent) {
        try {
            ISentryClient client = isEnabled(app) ? client(app) : null;
            if (client == null || !(batch instanceof Map)) {
                return 0;
            }
            Object items = ((Map<?, ?>) batch).get("items");
            if (!(items instanceof List)) {
                return 0;
            }
            String key = userId != null ? "user:" + userId : "no-session:" + app;
            int limit = userId != null ? USER_LIMIT : NO_SESSION_LIMIT;
            int sent = 0;
            for (Object item : ((List<?>) items).subList(0, Math.min(MAX_ITEMS, ((List<?>) items).size()))) {
                if (!(item instanceof Map) || !limiter.tryAcquire(key, limit)) {
                    continue;
                }
                SentryEvent event = toEvent(app, (Map<?, ?>) item, userId, meetingId, userAgent);
                if (event != null) {
                    client.captureEvent(event, null, null);
                    client.captureLog(toLog(app, event), null);
                    sent++;
                }
            }
            return sent;
        } catch (RuntimeException e) {
            log.debug("Dropped a client error report for {}: {}", app, e.toString());
            return 0;
        }
    }

    public void close() {
        for (Optional<ISentryClient> client : clients.values()) {
            client.ifPresent(c -> {
                c.flush(2000);
                c.close();
            });
        }
    }

    private ISentryClient client(String app) {
        return clients.computeIfAbsent(app, this::createClient).orElse(null);
    }

    private Optional<ISentryClient> createClient(String app) {
        String dsn = ErrorTracking.dsnFor(app, env, config);
        if (dsn == null) {
            return Optional.empty();
        }
        Anonymiser.Mode anonymise = Anonymiser.Mode.parse(ErrorTracking.setting(app, "ANONYMISE", env, config));
        Anonymiser anonymiser = new Anonymiser(anonymise);
        SentryOptions options = new SentryOptions();
        options.setDsn(dsn);
        options.setEnvironment(ErrorTracking.orDefault(ErrorTracking.setting(app, "ENVIRONMENT", env, config), "production"));
        options.setRelease(app + "@" + ErrorTracking.bbbRelease());
        String serverName = ErrorTracking.serverName(env.get("BBB_URL"));
        if (serverName != null) {
            options.setServerName(serverName);
        }
        options.setSampleRate(ErrorTracking.sampleRate(ErrorTracking.setting(app, "SAMPLE_RATE", env, config)));
        options.setTag("component", app);
        options.getLogs().setEnabled(true);
        options.getLogs().setBeforeSend(anonymiser::anonymiseLog);
        options.setBeforeSend((event, hint) -> {
            // The SDK describes the JVM that relays, not the browser that failed.
            event.getContexts().remove("runtime");
            return anonymiser.anonymiseEvent(event);
        });
        try {
            log.info("Forwarding {} error reports to {} (personal data: {})", app,
                    java.net.URI.create(dsn).getHost(), anonymise.name().toLowerCase(Locale.ROOT));
            return Optional.of(clientFactory.apply(options));
        } catch (RuntimeException e) {
            log.warn("Error reports from {} not forwarded: {}", app, e.toString());
            return Optional.empty();
        }
    }

    /** Sent by the client directly, the log entry lacks the attributes the SDK's logger adds. */
    SentryLogEvent toLog(String app, SentryEvent event) {
        String body;
        if (event.getExceptions() != null && !event.getExceptions().isEmpty()) {
            SentryException exception = event.getExceptions().get(0);
            body = exception.getType() + ": " + exception.getValue();
        } else {
            body = event.getMessage() != null ? event.getMessage().getFormatted() : "";
        }
        SentryLogEvent logEntry = new SentryLogEvent(new SentryId(), System.currentTimeMillis() / 1000.0, body,
                logLevel(event.getLevel()));
        Map<String, SentryLogEventAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("sentry.release", attribute(app + "@" + ErrorTracking.bbbRelease()));
        attributes.put("sentry.environment",
                attribute(ErrorTracking.orDefault(ErrorTracking.setting(app, "ENVIRONMENT", env, config), "production")));
        attributes.put("component", attribute(app));
        String serverName = ErrorTracking.serverName(env.get("BBB_URL"));
        if (serverName != null) {
            attributes.put("server.address", attribute(serverName));
        }
        if (event.getTag("meeting_id") != null) {
            attributes.put("meeting_id", attribute(event.getTag("meeting_id")));
        }
        if (event.getUser() != null && event.getUser().getId() != null) {
            attributes.put("user.id", attribute(event.getUser().getId()));
        }
        if (event.getRequest() != null && event.getRequest().getUrl() != null) {
            attributes.put("url.full", attribute(event.getRequest().getUrl()));
        }
        logEntry.setAttributes(attributes);
        return logEntry;
    }

    private static SentryLogEventAttributeValue attribute(String value) {
        return new SentryLogEventAttributeValue("string", value);
    }

    private static SentryLogLevel logLevel(SentryLevel level) {
        if (level == null) {
            return SentryLogLevel.ERROR;
        }
        switch (level) {
            case DEBUG: return SentryLogLevel.DEBUG;
            case INFO: return SentryLogLevel.INFO;
            case WARNING: return SentryLogLevel.WARN;
            case FATAL: return SentryLogLevel.FATAL;
            default: return SentryLogLevel.ERROR;
        }
    }

    SentryEvent toEvent(String app, Map<?, ?> item, String userId, String meetingId, String userAgent) {
        String message = text(item.get("message"), MAX_MESSAGE);
        if (message == null) {
            return null;
        }
        SentryEvent event = new SentryEvent();
        event.setPlatform("javascript");
        event.setSdk(new SdkVersion("bigbluebutton.client-relay", ErrorTracking.bbbRelease()));
        event.setLogger(app);
        event.setLevel(level(text(item.get("level"), MAX_SHORT)));

        if ("log".equals(item.get("kind"))) {
            Message logMessage = new Message();
            logMessage.setFormatted(message);
            event.setMessage(logMessage);
        } else {
            SentryException exception = new SentryException();
            String name = text(item.get("name"), MAX_SHORT);
            exception.setType(name != null ? name : "Error");
            exception.setValue(message);
            Mechanism mechanism = new Mechanism();
            mechanism.setType("browser");
            mechanism.setHandled(!Boolean.FALSE.equals(item.get("handled")));
            exception.setMechanism(mechanism);
            SentryStackTrace stackTrace = stackTrace(text(item.get("stack"), MAX_STACK));
            if (stackTrace != null) {
                exception.setStacktrace(stackTrace);
                event.setDebugMeta(debugMeta(stackTrace, item.get("debugIds")));
            }
            event.setExceptions(Collections.singletonList(exception));
        }

        if (userId != null) {
            User user = new User();
            user.setId(userId);
            event.setUser(user);
        }
        if (meetingId != null) {
            event.setTag("meeting_id", meetingId);
        }

        Request request = new Request();
        request.setUrl(text(item.get("url"), MAX_MESSAGE));
        String agent = text(userAgent, MAX_SHORT * 2);
        if (agent != null) {
            request.setHeaders(Collections.singletonMap("User-Agent", agent));
        }
        event.setRequest(request);

        Object context = item.get("context");
        if (context instanceof Map) {
            int count = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) context).entrySet()) {
                Object value = e.getValue();
                boolean scalar = value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
                if (scalar && count++ < MAX_CONTEXT_KEYS) {
                    event.setExtra(String.valueOf(e.getKey()), value instanceof String ? text(value, MAX_SHORT) : value);
                }
            }
        }

        Object breadcrumbs = item.get("breadcrumbs");
        if (breadcrumbs instanceof List) {
            List<Breadcrumb> list = new ArrayList<>();
            List<?> all = (List<?>) breadcrumbs;
            for (Object crumb : all.subList(Math.max(0, all.size() - MAX_BREADCRUMBS), all.size())) {
                if (crumb instanceof Map) {
                    list.add(breadcrumb((Map<?, ?>) crumb));
                }
            }
            event.setBreadcrumbs(list);
        }
        return event;
    }

    private static Breadcrumb breadcrumb(Map<?, ?> crumb) {
        Object timestamp = crumb.get("timestamp");
        Breadcrumb breadcrumb = timestamp instanceof Number
                ? new Breadcrumb(new Date((long) (((Number) timestamp).doubleValue() * 1000)))
                : new Breadcrumb();
        breadcrumb.setCategory(text(crumb.get("category"), 50));
        breadcrumb.setMessage(text(crumb.get("message"), MAX_BREADCRUMB_MESSAGE));
        breadcrumb.setLevel(level(text(crumb.get("level"), MAX_SHORT)));
        return breadcrumb;
    }

    /** Parses V8 ("at fn (url:1:2)") and Gecko/WebKit ("fn@url:1:2") stacks, oldest frame first. */
    static SentryStackTrace stackTrace(String stack) {
        if (stack == null) {
            return null;
        }
        List<SentryStackFrame> frames = new ArrayList<>();
        for (String line : stack.split("\n")) {
            Matcher m = V8_FRAME.matcher(line);
            if (!m.matches()) {
                m = GECKO_FRAME.matcher(line);
                if (!m.matches()) {
                    continue;
                }
            }
            SentryStackFrame frame = new SentryStackFrame();
            String function = m.group(1);
            frame.setFunction(function == null || function.isEmpty() ? "?" : function);
            frame.setAbsPath(m.group(2));
            frame.setFilename(m.group(2).replaceFirst("^[a-z]+://[^/]+", "").replaceFirst("[?#].*$", ""));
            frame.setLineno(Integer.parseInt(m.group(3)));
            frame.setColno(Integer.parseInt(m.group(4)));
            frame.setInApp(!m.group(2).contains("/node_modules/"));
            frames.add(frame);
            if (frames.size() == MAX_FRAMES) {
                break;
            }
        }
        if (frames.isEmpty()) {
            return null;
        }
        Collections.reverse(frames);
        return new SentryStackTrace(frames);
    }

    /** The source map debug ID of each file in the stack, as sent by the page; only valid IDs. */
    static DebugMeta debugMeta(SentryStackTrace stackTrace, Object debugIds) {
        if (!(debugIds instanceof Map) || stackTrace.getFrames() == null) {
            return null;
        }
        List<DebugImage> images = new ArrayList<>();
        Set<String> files = new HashSet<>();
        for (SentryStackFrame frame : stackTrace.getFrames()) {
            Object id = ((Map<?, ?>) debugIds).get(frame.getAbsPath());
            if (id instanceof String && DEBUG_ID.matcher((String) id).matches() && files.add(frame.getAbsPath())) {
                DebugImage image = new DebugImage();
                image.setType("sourcemap");
                image.setCodeFile(frame.getAbsPath());
                image.setDebugId(((String) id).toLowerCase(Locale.ROOT));
                images.add(image);
                if (images.size() == MAX_DEBUG_IMAGES) {
                    break;
                }
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        DebugMeta meta = new DebugMeta();
        meta.setImages(images);
        return meta;
    }

    private static SentryLevel level(String value) {
        if (value == null) {
            return SentryLevel.ERROR;
        }
        switch (value.toLowerCase(Locale.ROOT)) {
            case "debug": return SentryLevel.DEBUG;
            case "info": return SentryLevel.INFO;
            case "warn":
            case "warning": return SentryLevel.WARNING;
            case "fatal": return SentryLevel.FATAL;
            default: return SentryLevel.ERROR;
        }
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String)) {
            return null;
        }
        String s = ((String) value).trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Fixed-window counters per key, bounded in memory. */
    static final class RateLimiter {
        private final long windowMillis;
        private final int maxKeys;
        private final Map<String, long[]> windows = new ConcurrentHashMap<>();

        RateLimiter(long windowMillis, int maxKeys) {
            this.windowMillis = windowMillis;
            this.maxKeys = maxKeys;
        }

        boolean tryAcquire(String key, int limit) {
            long now = System.currentTimeMillis();
            if (windows.size() >= maxKeys) {
                windows.values().removeIf(w -> now - w[0] >= windowMillis);
                if (windows.size() >= maxKeys) {
                    return false;
                }
            }
            long[] window = windows.compute(key, (k, w) -> {
                if (w == null || now - w[0] >= windowMillis) {
                    return new long[] {now, 1};
                }
                w[1]++;
                return w;
            });
            return window[1] <= limit;
        }
    }
}
