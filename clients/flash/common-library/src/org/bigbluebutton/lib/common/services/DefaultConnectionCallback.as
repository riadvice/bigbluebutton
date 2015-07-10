package org.bigbluebutton.lib.common.services {
	
	import mx.utils.ObjectUtil;
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.common.models.IMessageListener;
	
	public class DefaultConnectionCallback {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(DefaultConnectionCallback);
		
		private var _messageListeners:Array = new Array();
		
		public function onBWCheck(... rest):Number {
			return 0;
		}
		
		public function onBWDone(... rest):void {
			LOGGER.debug("onBWDone() {0}", [ObjectUtil.toString(rest)]);
			var p_bw:Number;
			if (rest.length > 0)
				p_bw = rest[0];
			// your application should do something here 
			// when the bandwidth check is complete 
			LOGGER.debug("bandwidth = {0} Kbps.", [p_bw]);
		}
		
		public function onMessageFromServer(messageName:String, result:Object):void {
			LOGGER.debug("RECEIVED MESSAGE: [{0}]", [messageName]);
			notifyListeners(messageName, result);
		}
		
		public function addMessageListener(listener:IMessageListener):void {
			_messageListeners.push(listener);
		}
		
		public function removeMessageListener(listener:IMessageListener):void {
			for (var ob:int = 0; ob < _messageListeners.length; ob++) {
				if (_messageListeners[ob] == listener) {
					_messageListeners.splice(ob, 1);
					break;
				}
			}
		}
		
		private function notifyListeners(messageName:String, message:Object):void {
			if (messageName != null && messageName != "") {
				for (var notify:String in _messageListeners) {
					_messageListeners[notify].onMessage(messageName, message);
				}
			} else {
				LOGGER.error("Message name is undefined");
			}
		}
	}
}
