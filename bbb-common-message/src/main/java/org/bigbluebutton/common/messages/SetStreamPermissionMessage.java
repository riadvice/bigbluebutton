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

public class SetStreamPermissionMessage implements IBigBlueButtonMessage {

    public static final String SET_STREAM_PERMISSION = "set_stream_permission";
    public static final String VERSION = "0.0.1";

    public final String meetingId;
    public final String userId;
    public final String streamName;
    public final Boolean allowed;

    public SetStreamPermissionMessage(String meetingId, String userId,
            String streamName, Boolean allowed) {
        this.meetingId = meetingId;
        this.userId = userId;
        this.streamName = streamName;
        this.allowed = allowed;
    }

    public String toJson() {
        HashMap<String, Object> payload = new HashMap<String, Object>();

        payload.put(Constants.MEETING_ID, meetingId);
        payload.put(Constants.USER_ID, userId);
        payload.put(Constants.STREAM_NAME, streamName);
        payload.put(Constants.ALLOWED, allowed);

        java.util.HashMap<String, Object> header = MessageBuilder.buildHeader(
                SET_STREAM_PERMISSION, VERSION, null);
        return MessageBuilder.buildJson(header, payload);
    }

    public static SetStreamPermissionMessage fromJson(String message) {
        JsonParser parser = new JsonParser();
        JsonObject obj = (JsonObject) parser.parse(message);

        if (obj.has("header") && obj.has("payload")) {
            JsonObject header = (JsonObject) obj.get("header");
            JsonObject payload = (JsonObject) obj.get("payload");

            if (header.has("name")) {
                String messageName = header.get("name").getAsString();
                if (SET_STREAM_PERMISSION.equals(messageName)) {
                    if (payload.has(Constants.MEETING_ID)
                            && payload.has(Constants.USER_ID)
                            && payload.has(Constants.STREAM_NAME)
                            && payload.has(Constants.ALLOWED)) {
                        String id = payload.get(Constants.MEETING_ID)
                                .getAsString();
                        String userId = payload.get(Constants.USER_ID)
                                .getAsString();
                        String streamName = payload.get(Constants.STREAM_NAME)
                                .getAsString();
                        Boolean allowed = payload.get(Constants.ALLOWED)
                                .getAsBoolean();

                        return new SetStreamPermissionMessage(id, userId,
                                streamName, allowed);
                    }
                }
            }
        }
        return null;

    }
}
