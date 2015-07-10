package org.bigbluebutton.lib.common.services {
	
	import flash.events.AsyncErrorEvent;
	import flash.events.IOErrorEvent;
	import flash.events.NetStatusEvent;
	import flash.events.SecurityErrorEvent;
	import flash.net.NetConnection;
	import flash.net.Responder;
	
	import mx.utils.ObjectUtil;
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.main.commands.DisconnectUserSignal;
	import org.bigbluebutton.lib.main.utils.DisconnectEnum;
	import org.osflash.signals.ISignal;
	import org.osflash.signals.Signal;
	
	public class BaseConnection implements IBaseConnection {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(BaseConnection);
		
		[Inject]
		public var disconnectUserSignal:DisconnectUserSignal;
		
		protected var _connectionSuccessSignal:ISignal = new Signal();
		
		protected var _connectionFailureSignal:ISignal = new Signal();
		
		protected var _netConnection:NetConnection;
		
		protected var _uri:String;
		
		protected var _onUserCommand:Boolean;
		
		public function BaseConnection() {
		}
		
		public function init(callback:DefaultConnectionCallback):void {
			_netConnection = new NetConnection();
			_netConnection.client = callback;
			_netConnection.addEventListener(NetStatusEvent.NET_STATUS, netStatus);
			_netConnection.addEventListener(AsyncErrorEvent.ASYNC_ERROR, netASyncError);
			_netConnection.addEventListener(SecurityErrorEvent.SECURITY_ERROR, netSecurityError);
			_netConnection.addEventListener(IOErrorEvent.IO_ERROR, netIOError);
		}
		
		public function get connectionFailureSignal():ISignal {
			return _connectionFailureSignal;
		}
		
		public function get connectionSuccessSignal():ISignal {
			return _connectionSuccessSignal;
		}
		
		public function get connection():NetConnection {
			return _netConnection;
		}
		
		public function connect(uri:String, ... parameters):void {
			_uri = uri;
			// The connect call needs to be done properly. At the moment lock settings
			// are not implemented in the mobile client, so parameters[7] and parameters[8]
			// are "faked" in order to connect (without them, I couldn't get the connect 
			// call to work...) - Adam
			parameters[7] = false;
			parameters[8] = false;
			try {
				LOGGER.info("Trying to connect to [{0]] ...", [uri]);
				// passing an array to a method that expects a variable number of parameters
				// http://stackoverflow.com/a/3852920
				_netConnection.connect.apply(null, new Array(uri).concat(parameters));
			} catch (e:ArgumentError) {
				LOGGER.error(ObjectUtil.toString(e));
				// Invalid parameters.
				switch (e.errorID) {
					case 2004:
						LOGGER.error("Error! Invalid server location: {0}", [uri]);
						break;
					default:
						LOGGER.error("UNKNOWN Error! Invalid server location: {0}", [uri]);
						break;
				}
				sendDisconnectUserSignal();
			}
		}
		
		public function disconnect(onUserCommand:Boolean):void {
			_onUserCommand = onUserCommand;
			_netConnection.close();
		}
		
		protected function netStatus(event:NetStatusEvent):void {
			var info:Object = event.info;
			var statusCode:String = info.code;
			switch (statusCode) {
				case "NetConnection.Connect.Success":
					LOGGER.info("Connection succeeded. Uri: {0}", [_uri]);
					sendConnectionSuccessSignal();
					break;
				case "NetConnection.Connect.Failed":
					LOGGER.error("Connection failed. Uri: {0}", [_uri]);
					sendDisconnectUserSignal();
					break;
				case "NetConnection.Connect.Closed":
					LOGGER.error("Connection closed. Uri: {0}", [_uri]);
					sendDisconnectUserSignal();
					break;
				case "NetConnection.Connect.InvalidApp":
					LOGGER.error("application not found on server. Uri: {0}", [_uri]);
					sendDisconnectUserSignal();
					break;
				case "NetConnection.Connect.AppShutDown":
					LOGGER.info("application has been shutdown. Uri: {0}", [_uri]);
					sendDisconnectUserSignal();
					break;
				case "NetConnection.Connect.Rejected":
					LOGGER.warn("Connection to the server rejected. Uri: {0}. Check if the red5 specified in the uri exists and is running", [_uri]);
					sendDisconnectUserSignal();
					break;
				case "NetConnection.Connect.NetworkChange":
					LOGGER.warn("Detected network change. User might be on a wireless and temporarily dropped connection. Doing nothing. Just making a note.");
					break;
				default:
					LOGGER.debug("Default status");
					sendDisconnectUserSignal();
					break;
			}
		}
		
		protected function sendConnectionSuccessSignal():void {
			connectionSuccessSignal.dispatch();
		}
		
		protected function sendDisconnectUserSignal():void {
			disconnectUserSignal.dispatch(DisconnectEnum.CONNECTION_STATUS_CONNECTION_DROPPED);
		}
		
		protected function netSecurityError(event:SecurityErrorEvent):void {
			LOGGER.error("Security error - {0}", [event.text]);
			sendDisconnectUserSignal();
		}
		
		protected function netIOError(event:IOErrorEvent):void {
			LOGGER.error("Input/output error - {0}", [event.text]);
			sendDisconnectUserSignal();
		}
		
		protected function netASyncError(event:AsyncErrorEvent):void {
			LOGGER.error("Asynchronous code error - {0}", [event.text]);
			sendDisconnectUserSignal();
		}
		
		public function sendMessage(service:String, onSuccess:Function, onFailure:Function, message:Object = null):void {
			LOGGER.debug("SENDING MESSAGE: [{0]]", [service]);
			var responder:Responder = new Responder(function(result:Object):void { // On successful result
				onSuccess("SUCCESSFULLY SENT: [" + service + "].");
			}, function(status:Object):void { // status - On error occurred
				var errorReason:String = "FAILED TO SEND: [" + service + "]:";
				for (var x:Object in status) {
					errorReason += "\n - " + x + " : " + status[x];
				}
				onFailure(errorReason);
			});
			if (message == null) {
				_netConnection.call(service, responder);
			} else {
				_netConnection.call(service, responder, message);
			}
		}
	}
}
