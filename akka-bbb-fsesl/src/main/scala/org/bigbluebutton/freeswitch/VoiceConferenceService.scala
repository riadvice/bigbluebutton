package org.bigbluebutton.freeswitch

import org.bigbluebutton.SystemConfiguration
import org.bigbluebutton.freeswitch.voice.IVoiceConferenceService
import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.common2.util.JsonUtil
import org.bigbluebutton.common2.redis.RedisPublisher

class VoiceConferenceService(sender: RedisPublisher) extends IVoiceConferenceService with SystemConfiguration {

  val FROM_VOICE_CONF_SYSTEM_CHAN = "bigbluebutton:from-voice-conf:system"

  def voiceConfRecordingStarted(voiceConfId: String, recordStream: String, recording: java.lang.Boolean, timestamp: String) {
    val header = BbbCoreVoiceConfHeader(RecordingStartedVoiceConfEvtMsg.NAME, voiceConfId)
    val body = RecordingStartedVoiceConfEvtMsgBody(voiceConfId, recordStream, recording.booleanValue(), timestamp)
    val envelope = BbbCoreEnvelope(RecordingStartedVoiceConfEvtMsg.NAME, Map("voiceConf" -> voiceConfId))

    val msg = new RecordingStartedVoiceConfEvtMsg(header, body)
    val msgEvent = BbbCommonEnvCoreMsg(envelope, msg)

    val json = JsonUtil.toJson(msgEvent)
    sender.publish(fromVoiceConfRedisChannel, json)

  }

  def voiceConfRunning(voiceConfId: String, running: java.lang.Boolean): Unit = {
    println("*************######## Conference voiceConfId=" + voiceConfId + " running=" + running)
    val header = BbbCoreVoiceConfHeader(VoiceConfRunningEvtMsg.NAME, voiceConfId)
    val body = VoiceConfRunningEvtMsgBody(voiceConfId, running.booleanValue())
    val envelope = BbbCoreEnvelope(VoiceConfRunningEvtMsg.NAME, Map("voiceConf" -> voiceConfId))

    val msg = new VoiceConfRunningEvtMsg(header, body)
    val msgEvent = BbbCommonEnvCoreMsg(envelope, msg)

    val json = JsonUtil.toJson(msgEvent)
    sender.publish(fromVoiceConfRedisChannel, json)
  }

  def userJoinedVoiceConf(voiceConfId: String, voiceUserId: String, userId: String, callerIdName: String,
                          callerIdNum: String, muted: java.lang.Boolean, talking: java.lang.Boolean, avatarURL: String) {

    val header = BbbCoreVoiceConfHeader(UserJoinedVoiceConfEvtMsg.NAME, voiceConfId)
    val body = UserJoinedVoiceConfEvtMsgBody(voiceConfId, voiceUserId, userId, callerIdName, callerIdNum,
      muted.booleanValue(), talking.booleanValue(), avatarURL)
    val envelope = BbbCoreEnvelope(UserJoinedVoiceConfEvtMsg.NAME, Map("voiceConf" -> voiceConfId))

    val msg = new UserJoinedVoiceConfEvtMsg(header, body)
    val msgEvent = BbbCommonEnvCoreMsg(envelope, msg)

    val json = JsonUtil.toJson(msgEvent)
    sender.publish(fromVoiceConfRedisChannel, json)

  }

  def userLeftVoiceConf(voiceConfId: String, voiceUserId: String) {

    val header = BbbCoreVoiceConfHeader(UserLeftVoiceConfEvtMsg.NAME, voiceConfId)
    val body = UserLeftVoiceConfEvtMsgBody(voiceConfId, voiceUserId)
    val envelope = BbbCoreEnvelope(UserLeftVoiceConfEvtMsg.NAME, Map("voiceConf" -> voiceConfId))

    val msg = new UserLeftVoiceConfEvtMsg(header, body)
    val msgEvent = BbbCommonEnvCoreMsg(envelope, msg)

    val json = JsonUtil.toJson(msgEvent)
    sender.publish(fromVoiceConfRedisChannel, json)

  }

  def userLockedInVoiceConf(voiceConfId: String, voiceUserId: String, locked: java.lang.Boolean) {

  }

  def userMutedInVoiceConf(voiceConfId: String, voiceUserId: String, muted: java.lang.Boolean) {

    val header = BbbCoreVoiceConfHeader(UserMutedInVoiceConfEvtMsg.NAME, voiceConfId)
    val body = UserMutedInVoiceConfEvtMsgBody(voiceConfId, voiceUserId, muted.booleanValue())
    val envelope = BbbCoreEnvelope(UserMutedInVoiceConfEvtMsg.NAME, Map("voiceConf" -> voiceConfId))

    val msg = new UserMutedInVoiceConfEvtMsg(header, body)
    val msgEvent = BbbCommonEnvCoreMsg(envelope, msg)

    val json = JsonUtil.toJson(msgEvent)
    sender.publish(fromVoiceConfRedisChannel, json)

  }

  def userTalkingInVoiceConf(voiceConfId: String, voiceUserId: String, talking: java.lang.Boolean) {

    val header = BbbCoreVoiceConfHeader(UserTalkingInVoiceConfEvtMsg.NAME, voiceConfId)
    val body = UserTalkingInVoiceConfEvtMsgBody(voiceConfId, voiceUserId, talking.booleanValue())
    val envelope = BbbCoreEnvelope(UserTalkingInVoiceConfEvtMsg.NAME, Map("voiceConf" -> voiceConfId))

    val msg = new UserTalkingInVoiceConfEvtMsg(header, body)
    val msgEvent = BbbCommonEnvCoreMsg(envelope, msg)

    val json = JsonUtil.toJson(msgEvent)
    sender.publish(fromVoiceConfRedisChannel, json)

  }

}
