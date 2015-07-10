package org.bigbluebutton.lib.chat.services {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.chat.models.ChatMessageVO;
	import org.bigbluebutton.lib.chat.models.IChatMessagesSession;
	import org.bigbluebutton.lib.common.models.IMessageListener;
	import org.bigbluebutton.lib.main.models.IUserSession;
	
	public class ChatMessageReceiver implements IMessageListener {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(ChatMessageReceiver);
		
		public var userSession:IUserSession;
		
		public var chatMessagesSession:IChatMessagesSession;
		
		public function ChatMessageReceiver(userSession:IUserSession, chatMessagesSession:IChatMessagesSession) {
			this.userSession = userSession;
			this.chatMessagesSession = chatMessagesSession;
		}
		
		public function onMessage(messageName:String, message:Object):void {
			switch (messageName) {
				case "ChatReceivePublicMessageCommand":
					handleChatReceivePublicMessageCommand(message);
					break;
				case "ChatReceivePrivateMessageCommand":
					handleChatReceivePrivateMessageCommand(message);
					break;
				case "ChatRequestMessageHistoryReply":
					handleChatRequestMessageHistoryReply(message);
					break;
				default:
					//   LogUtil.warn("Cannot handle message [" + messageName + "]");
			}
		}
		
		private function handleChatRequestMessageHistoryReply(message:Object):void {
			LOGGER.debug("Handling chat history message [{0}]", [message.msg]);
			var chats:Array = JSON.parse(message.msg) as Array;
			
			for (var i:int = 0; i < chats.length; i++) {
				handleChatReceivePublicMessageCommand(chats[i]);
			}
		}
		
		private function handleChatReceivePublicMessageCommand(message:Object):void {
			LOGGER.debug("Handling public chat message [{0}]", [message.message]);
			var msg:ChatMessageVO = new ChatMessageVO();
			msg.chatType = message.chatType;
			msg.fromUserID = message.fromUserID;
			msg.fromUsername = message.fromUsername;
			msg.fromColor = message.fromColor;
			msg.fromLang = message.fromLang;
			msg.fromTime = message.fromTime;
			msg.fromTimezoneOffset = message.fromTimezoneOffset;
			msg.toUserID = message.toUserID;
			msg.toUsername = message.toUsername;
			msg.message = message.message;
			chatMessagesSession.publicChat.newChatMessage(msg);
		}
		
		private function handleChatReceivePrivateMessageCommand(message:Object):void {
			LOGGER.debug("Handling private chat message");
			var msg:ChatMessageVO = new ChatMessageVO();
			msg.chatType = message.chatType;
			msg.fromUserID = message.fromUserID;
			msg.fromUsername = message.fromUsername;
			msg.fromColor = message.fromColor;
			msg.fromLang = message.fromLang;
			msg.fromTime = message.fromTime;
			msg.fromTimezoneOffset = message.fromTimezoneOffset;
			msg.toUserID = message.toUserID;
			msg.toUsername = message.toUsername;
			msg.message = message.message;
			var userId:String = (msg.fromUserID == userSession.userId ? msg.toUserID : msg.fromUserID);
			var userName:String = (msg.fromUserID == userSession.userId ? msg.toUsername : msg.fromUsername);
			chatMessagesSession.newPrivateMessage(userId, userName, msg);
		}
	}
}
