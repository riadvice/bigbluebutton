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
package org.bigbluebutton.web.controllers

import groovy.json.JsonException
import groovy.json.JsonSlurper
import org.bigbluebutton.api.MeetingService
import org.bigbluebutton.api.domain.UserSession
import org.bigbluebutton.common2.errortracking.ClientRelay
import org.bigbluebutton.web.Application

/** POST /bigbluebutton/client-errors/<app>, forwarded by ClientRelay. Only the HTML5 client
 * sends a session token. Always 204, except 401 for a bad HTML5 session. */
class ClientErrorsController {
  MeetingService meetingService

  private static final int MAX_BODY = 64 * 1024
  private static final ClientRelay relay = new ClientRelay(Application.sentrySettings())

  def report = {
    String app = params.app
    if (!relay.isEnabled(app) || request.contentLength > MAX_BODY) {
      render(status: 204)
      return
    }

    String userId = null
    String meetingId = null
    if (app == "bbb-html5") {
      String sessionToken = params.sessionToken
      UserSession userSession = sessionToken ? meetingService.getUserSessionWithSessionToken(sessionToken) : null
      boolean valid = userSession != null &&
          (session[sessionToken] != null || meetingService.getAllowRequestsWithoutSession(sessionToken))
      if (!valid) {
        render(status: 401)
        return
      }
      userId = userSession.internalUserId
      meetingId = userSession.meetingID
    }

    try {
      def batch = new JsonSlurper().parseText(request.reader.text)
      relay.report(app, batch, userId, meetingId, request.getHeader("User-Agent"))
    } catch (JsonException | IllegalArgumentException | StackOverflowError e) {
      // JsonSlurper recurses once per nesting level: a few thousand '[' overflow the stack
      log.debug("Malformed error report from {}: {}", app, e.class.simpleName)
    }
    render(status: 204)
  }
}
