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

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import io.sentry.logback.SentryAppender;

/** Declared in logback.xml, not added from code, because Spring Boot resets logback on start. */
public class ErrorTrackingAppender extends SentryAppender {

    @Override
    public void start() {
        // A DSN ending in _IS_UNDEFINED makes SentryAppender skip its own Sentry.init.
        SentryOptions options = new SentryOptions();
        options.setDsn("BBB_SENTRY_IS_UNDEFINED");
        setOptions(options);
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!Sentry.isEnabled()) {
            return;
        }
        setMinimumEventLevel(ErrorTracking.eventLevel);
        setMinimumBreadcrumbLevel(ErrorTracking.breadcrumbLevel);
        setMinimumLevel(ErrorTracking.eventLevel);
        super.append(event);
    }
}
