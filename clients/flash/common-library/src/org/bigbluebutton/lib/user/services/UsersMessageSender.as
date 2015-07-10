package org.bigbluebutton.lib.user.services {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.main.models.IUserSession;
	
	public class UsersMessageSender {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(UsersMessageSender);
		
		public var userSession:IUserSession;
		
		// The default callbacks of userSession.mainconnection.sendMessage
		private var defaultSuccessResponse:Function = function(result:String):void {
			LOGGER.info(result);
		};
		
		private var defaultFailureResponse:Function = function(status:String):void {
			LOGGER.error(status);
		};
		
		public function UsersMessageSender() {
		}
		
		public function kickUser(userID:String, ejectedBy:String):void {
			LOGGER.info("UsersMessageSender::kickUser() -- Sending [participants.kickUser] message to server.. with message [userID:{0}]", [userID]);
			var message:Object = new Object();
			message["userId"] = userID;
			message["ejectedBy"] = ejectedBy;
			userSession.mainConnection.sendMessage("participants.ejectUserFromMeeting", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function queryForParticipants():void {
			LOGGER.info("UsersMessageSender::queryForParticipants() -- Sending [participants.getParticipants] message to server");
			userSession.mainConnection.sendMessage("participants.getParticipants", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function assignPresenter(userid:String, name:String, assignedBy:String):void {
			LOGGER.info("UsersMessageSender::assignPresenter() -- Sending [participants.assignPresenter] message to server with message [newPresenterID:{0}, newPresenterName:{1}, assignedBy: {2}]", [userid, name, assignedBy]);
			var message:Object = new Object();
			message["newPresenterID"] = userid;
			message["newPresenterName"] = name;
			message["assignedBy"] = assignedBy;
			userSession.mainConnection.sendMessage("participants.assignPresenter", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function raiseHand():void {
			LOGGER.info("UsersMessageSender::raiseHand() -- Sending [participants.userRaiseHand] message to server");
			userSession.mainConnection.sendMessage("participants.userRaiseHand", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function lowerHand(userID:String, loweredBy:String):void {
			LOGGER.info("UsersMessageSender::raiseHand() -- Sending [participants.lowerHand] message to server with message: [userId:{0}, loweredBy:{0}]", [userID]);
			var message:Object = new Object();
			message["userId"] = userID;
			message["loweredBy"] = loweredBy;
			userSession.mainConnection.sendMessage("participants.lowerHand", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function addStream(userID:String, streamName:String):void {
			LOGGER.info("UsersMessageSender::addStream() -- Sending [participants.shareWebcam] message to server with message [streamName:{0}]", [streamName]);
			userSession.mainConnection.sendMessage("participants.shareWebcam", defaultSuccessResponse, defaultFailureResponse, streamName);
		}
		
		public function removeStream(userID:String, streamName:String):void {
			LOGGER.info("UsersMessageSender::removeStream() -- Sending [participants.unshareWebcam] message to server");
			userSession.mainConnection.sendMessage("participants.unshareWebcam", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function queryForRecordingStatus():void {
			LOGGER.info("UsersMessageSender::queryForRecordingStatus() -- Sending [queryForRecordingStatus] message to server");
			userSession.mainConnection.sendMessage("participants.getRecordingStatus", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function changeRecordingStatus(userID:String, recording:Boolean):void {
			LOGGER.info("UsersMessageSender::changeRecordingStatus() -- Sending [changeRecordingStatus] message to server with message [userId:{0}, recording:{1}]", [userID, recording]);
			var message:Object = new Object();
			message["userId"] = userID;
			message["recording"] = recording;
			userSession.mainConnection.sendMessage("participants.setRecordingStatus", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function muteAllUsers(mute:Boolean, dontMuteThese:Array = null):void {
			LOGGER.info("UsersMessageSender::muteAllUsers() -- Sending [voice.muteAllUsers] message to server");
			if (dontMuteThese == null) {
				dontMuteThese = [];
			}
			var message:Object = new Object();
			message["mute"] = mute;
			message["exceptUsers"] = dontMuteThese;
			userSession.mainConnection.sendMessage("voice.muteAllUsers", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function muteUnmuteUser(userid:String, mute:Boolean):void {
			LOGGER.info("UsersMessageSender::muteUnmuteUser() -- Sending [voice.muteUnmuteUser] message to server with message [userId:{0}, mute:{1}]", [userid, mute]);
			var message:Object = new Object();
			message["userId"] = userid;
			message["mute"] = mute;
			userSession.mainConnection.sendMessage("voice.muteUnmuteUser", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function ejectUser(userid:String):void {
			LOGGER.info("UsersMessageSender::ejectUser() -- Sending [voice.kickUSer] message to server with message [userId:{0}]", [userid]);
			var message:Object = new Object();
			message["userId"] = userid;
			userSession.mainConnection.sendMessage("voice.kickUSer", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function getRoomMuteState():void {
			LOGGER.info("UsersMessageSender::getRoomMuteState() -- Sending [voice.isRoomMuted] message to server");
			userSession.mainConnection.sendMessage("voice.isRoomMuted", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function getRoomLockState():void {
			LOGGER.info("UsersMessageSender::getRoomLockState() -- Sending [lock.isRoomLocked] message to server");
			userSession.mainConnection.sendMessage("lock.isRoomLocked", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function setAllUsersLock(lock:Boolean, except:Array = null):void {
			LOGGER.info("UsersMessageSender::setAllUsersLock() -- Sending [setAllUsersLock] message to server");
		}
		
		public function setUserLock(internalUserID:String, lock:Boolean):void {
			LOGGER.info("UsersMessageSender::setUserLock() -- Sending [setUserLock] message to server");
		}
		
		public function getLockSettings():void {
			LOGGER.info("UsersMessageSender::getLockSettings() -- Sending [getLockSettings] message to server");
			userSession.mainConnection.sendMessage("lock.getLockSettings", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function saveLockSettings(newLockSettings:Object):void {
			LOGGER.info("UsersMessageSender::saveLockSettings() -- Sending [saveLockSettings] message to server");
		}
		
		public function validateToken(internalUserID:String, authToken:String):void {
			LOGGER.info("UsersMessageSender::validateToken() -- Sending [validateToken] message to server");
			var message:Object = new Object();
			message["userId"] = internalUserID;
			message["authToken"] = authToken;
			userSession.mainConnection.sendMessage("validateToken", defaultSuccessResponse, defaultFailureResponse, message);
		}
	}
}
