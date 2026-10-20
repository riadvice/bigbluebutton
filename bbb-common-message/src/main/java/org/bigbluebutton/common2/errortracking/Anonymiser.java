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
import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filters secrets and masks personal data, by the name of each name=value pair. */
public final class Anonymiser {
    public static final String FILTERED = "[Filtered]";

    public enum Mode {
        MASK, OFF;

        static Mode parse(String value) {
            return value != null && value.trim().equalsIgnoreCase("off") ? OFF : MASK;
        }
    }

    private static final Pattern SECRET_NAME = Pattern.compile(
            "pass|secret|token|checksum|salt|api[_-]?key|authori[sz]ation|cookie|pw$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PERSONAL_NAME = Pattern.compile(
            "name$|e-?mail|address$|^ip$|^message$|^text$|^msg$",
            Pattern.CASE_INSENSITIVE);

    // The bounded name keeps the scan linear. A value stops at '?' and '=' so a URL
    // value (url=https://h/p?token=x) leaves its own query pairs to be matched.
    private static final Pattern QUERY_PAIR = Pattern.compile(
            "([\\w.\\-\\[\\]]{1,256})=([^&\\s\"'<>,;?=]*)");
    private static final Pattern JSON_PAIR = Pattern.compile(
            "\"([\\w.\\-]{1,256})\"(\\s*:\\s*)\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d.])");

    private final Mode mode;

    public Anonymiser(Mode mode) {
        this.mode = mode;
    }

    private boolean isSecret(String name) {
        return SECRET_NAME.matcher(name).find();
    }

    private boolean isPersonal(String name) {
        return mode != Mode.OFF && PERSONAL_NAME.matcher(name).find();
    }

    private boolean isFiltered(String name) {
        return isSecret(name) || isPersonal(name);
    }

    public String anonymiseText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = replacePairs(QUERY_PAIR, text,
                m -> isFiltered(m.group(1)) ? m.group(1) + "=" + FILTERED : null);
        result = replacePairs(JSON_PAIR, result, m -> isFiltered(m.group(1))
                ? "\"" + m.group(1) + "\"" + m.group(2) + "\"" + FILTERED + "\"" : null);
        if (mode != Mode.OFF) {
            result = IPV4.matcher(result).replaceAll(Matcher.quoteReplacement(FILTERED));
        }
        return result;
    }

    private String replacePairs(Pattern pattern, String text, Function<Matcher, String> masked) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String replaced = masked.apply(m);
            m.appendReplacement(out, Matcher.quoteReplacement(replaced == null ? m.group() : replaced));
        }
        m.appendTail(out);
        return out.toString();
    }

    Object anonymiseValue(String name, Object value) {
        if (name != null && value != null && isFiltered(name)) {
            return FILTERED;
        }
        if (value instanceof String) {
            return anonymiseText((String) value);
        }
        if (value instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(e.getKey());
                copy.put(key, anonymiseValue(key, e.getValue()));
            }
            return copy;
        }
        if (value instanceof List) {
            List<Object> copy = new ArrayList<>();
            for (Object item : (List<?>) value) {
                copy.add(anonymiseValue(null, item));
            }
            return copy;
        }
        return value;
    }

    public SentryEvent anonymiseEvent(SentryEvent event) {
        Message message = event.getMessage();
        if (message != null) {
            message.setFormatted(anonymiseText(message.getFormatted()));
            message.setMessage(anonymiseText(message.getMessage()));
            // The raw arguments of a log call are often names; the formatted text is enough.
            message.setParams(null);
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(anonymiseText(exception.getValue()));
            }
        }
        if (event.getBreadcrumbs() != null) {
            for (Breadcrumb breadcrumb : event.getBreadcrumbs()) {
                anonymiseBreadcrumb(breadcrumb);
            }
        }
        if (event.getExtras() != null) {
            for (Map.Entry<String, Object> e : new LinkedHashMap<>(event.getExtras()).entrySet()) {
                event.setExtra(e.getKey(), anonymiseValue(e.getKey(), e.getValue()));
            }
        }
        Request request = event.getRequest();
        if (request != null) {
            request.setUrl(anonymiseText(request.getUrl()));
            request.setQueryString(anonymiseText(request.getQueryString()));
            request.setCookies(null);
            request.setData(null);
            if (request.getHeaders() != null) {
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, String> e : request.getHeaders().entrySet()) {
                    // Kept as is: it holds no personal data, and Chrome's reduced version
                    // (141.0.0.0) would be taken for an IP address, hiding the browser.
                    String value = "User-Agent".equalsIgnoreCase(e.getKey())
                            ? e.getValue() : String.valueOf(anonymiseValue(e.getKey(), e.getValue()));
                    headers.put(e.getKey(), value);
                }
                request.setHeaders(headers);
            }
        }
        return event;
    }

    public SentryLogEvent anonymiseLog(SentryLogEvent log) {
        log.setBody(anonymiseText(log.getBody()));
        if (log.getAttributes() != null) {
            Map<String, SentryLogEventAttributeValue> attributes = new LinkedHashMap<>();
            for (Map.Entry<String, SentryLogEventAttributeValue> e : log.getAttributes().entrySet()) {
                SentryLogEventAttributeValue value = e.getValue();
                if (e.getKey().startsWith("sentry.message.parameter")) {
                    continue;
                }
                if (value != null && value.getValue() instanceof String) {
                    value = new SentryLogEventAttributeValue(value.getType(), anonymiseText((String) value.getValue()));
                }
                attributes.put(e.getKey(), value);
            }
            log.setAttributes(attributes);
        }
        return log;
    }

    public Breadcrumb anonymiseBreadcrumb(Breadcrumb breadcrumb) {
        breadcrumb.setMessage(anonymiseText(breadcrumb.getMessage()));
        for (Map.Entry<String, Object> e : new LinkedHashMap<>(breadcrumb.getData()).entrySet()) {
            breadcrumb.setData(e.getKey(), anonymiseValue(e.getKey(), e.getValue()));
        }
        return breadcrumb;
    }
}
