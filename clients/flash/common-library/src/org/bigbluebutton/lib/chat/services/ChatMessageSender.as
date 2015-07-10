package org.bigbluebutton.lib.chat.services {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.chat.models.ChatMessageVO;
	import org.bigbluebutton.lib.main.models.IUserSession;
	import org.osflash.signals.ISignal;
	import org.osflash.signals.Signal;
	
	public class ChatMessageSender {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(ChatMessageSender);
		
		public var userSession:IUserSession;
		
		private var successSendingMessageSignal:ISignal;
		
		private var failureSendingMessageSignal:ISignal;
		
		public function ChatMessageSender(userSession:IUserSession, successSendMessageSignal:ISignal, failureSendingMessageSignal:ISignal) {
			this.userSession = userSession;
			this.successSendingMessageSignal = successSendMessageSignal;
			this.failureSendingMessageSignal = failureSendingMessageSignal;
		}
		
		public function getPublicChatMessages():void {
			LOGGER.debug("Sending [chat.getPublicMessages] to server.");
			userSession.mainConnection.sendMessage("chat.sendPublicChatHistory", function(result:String):void { // On successful result
				publicChatMessagesOnSuccessSignal.dispatch(result);
			}, function(status:String):void { // status - On error occurred
				publicChatMessagesOnFailureSignal.dispatch(status);
			});
		}
		
		public function sendPublicMessage(message:ChatMessageVO):void {
			LOGGER.debug("Sending [chat.getPublicMessages] to server. [{0}]", [message.message]);
			userSession.mainConnection.sendMessage("chat.sendPublicMessage", function(result:String):void { // On successful result
				successSendingMessageSignal.dispatch(result);
			}, function(status:String):void { // status - On error occurred
				failureSendingMessageSignal.dispatch(status);
			}, message.toObj());
		}
		
		public function sendPrivateMessage(message:ChatMessageVO):void {
			LOGGER.debug("Sending fromUserID [{0}] to toUserID [{1}]", [message.fromUserID, message.toUserID]);
			userSession.mainConnection.sendMessage("chat.sendPrivateMessage", function(result:String):void { // On successful result
				successSendingMessageSignal.dispatch(result);
			}, function(status:String):void { // status - On error occurred
				failureSendingMessageSignal.dispatch(status);
			}, message.toObj());
		}
		
		private var _publicChatMessagesOnSuccessSignal:Signal = new Signal();
		
		private var _publicChatMessagesOnFailureSignal:Signal = new Signal();
		
		public function get publicChatMessagesOnSuccessSignal():Signal {
			return _publicChatMessagesOnSuccessSignal;
		}
		
		public function get publicChatMessagesOnFailureSignal():Signal {
			return _publicChatMessagesOnFailureSignal;
		}
	}
}
