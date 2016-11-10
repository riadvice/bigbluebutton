/**
 * BigBlueButton open source conferencing system - http://www.bigbluebutton.org/
 * 
 * Copyright (c) 2016 BigBlueButton Inc. and by respective authors (see below).
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

package org.bigbluebutton.common.messages;

import java.util.HashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class StreamPermissionChangeMessage implements ISubscribedMessage {
    public static final String STREAM_PERMISSION_CHANGE = "stream_permission_change";
    public static final String VERSION = "0.0.1";

    public final String meetingId;
    public final String userId;
    public final String stream;
    public final Boolean allowed;

    public StreamPermissionChangeMessage(String meetingId, String userId,
            String stream, Boolean allowed) {
        this.meetingId = meetingId;
        this.userId = userId;
        this.stream = stream;
        this.allowed = allowed;
    }

    public String toJson() {
        HashMap<String, Object> payload = new HashMap<String, Object>();
        payload.put(Constants.MEETING_ID, meetingId);
        payload.put(Constants.USER_ID, userId);
        payload.put(Constants.STREAM, stream);
        payload.put(Constants.STREAM, allowed);

        java.util.HashMap<String, Object> header = MessageBuilder.buildHeader(
                STREAM_PERMISSION_CHANGE, VERSION, null);

        return MessageBuilder.buildJson(header, payload);
    }

    public static StreamPermissionChangeMessage fromJson(String message) {
        JsonParser parser = new JsonParser();
        JsonObject obj = (JsonObject) parser.parse(message);

        if (obj.has("header") && obj.has("payload")) {
            JsonObject header = (JsonObject) obj.get("header");
            JsonObject payload = (JsonObject) obj.get("payload");

            if (header.has("name")) {
                String messageName = header.get("name").getAsString();
                if (STREAM_PERMISSION_CHANGE.equals(messageName)) {
                    if (payload.has(Constants.MEETING_ID)
                            && payload.has(Constants.USER_ID)
                            && payload.has(Constants.STREAM)
                            && payload.has(Constants.ALLOWED)) {
                        String id = payload.get(Constants.MEETING_ID)
                                .getAsString();
                        String userid = payload.get(Constants.USER_ID)
                                .getAsString();
                        String stream = payload.get(Constants.STREAM)
                                .getAsString();
                        Boolean allowed = payload.get(Constants.ALLOWED)
                                .getAsBoolean();
                        return new StreamPermissionChangeMessage(id, userid,
                                stream, allowed);
                    }
                }
            }
        }
        return null;
    }
}
