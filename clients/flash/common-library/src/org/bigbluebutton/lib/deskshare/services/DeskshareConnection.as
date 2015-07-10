package org.bigbluebutton.lib.deskshare.services {
	
	import flash.net.NetConnection;
	import flash.net.Responder;
	import flash.net.SharedObject;
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.common.services.DefaultConnectionCallback;
	import org.bigbluebutton.lib.common.services.IBaseConnection;
	import org.bigbluebutton.lib.main.models.IConferenceParameters;
	import org.osflash.signals.ISignal;
	import org.osflash.signals.Signal;
	
	public class DeskshareConnection extends DefaultConnectionCallback implements IDeskshareConnection {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(DeskshareConnection);
		
		[Inject]
		public var baseConnection:IBaseConnection;
		
		[Inject]
		public var conferenceParameters:IConferenceParameters;
		
		private var _connectionSuccessSignal:ISignal = new Signal();
		
		private var _connectionFailureSignal:ISignal = new Signal();
		
		private var _isStreamingSignal:ISignal = new Signal();
		
		private var _mouseLocationChangedSignal:ISignal = new Signal();
		
		private var _isStreaming:Boolean;
		
		private var _streamCheckedOnStartup:Boolean;
		
		private var _streamWidth:Number;
		
		private var _streamHeight:Number;
		
		private var _applicationURI:String;
		
		private var _room:String;
		
		private var _deskSO:SharedObject;
		
		public function DeskshareConnection() {
		}
		
		[PostConstruct]
		public function init():void {
			baseConnection.init(this);
			baseConnection.connectionSuccessSignal.add(onConnectionSuccess);
			baseConnection.connectionFailureSignal.add(onConnectionFailure);
		}
		
		private function onConnectionSuccess():void {
			_deskSO = SharedObject.getRemote(room + "-deskSO", applicationURI, false);
			_deskSO.client = this;
			_deskSO.connect(baseConnection.connection);
			checkIfStreamIsPublishing();
			_connectionSuccessSignal.dispatch();
		}
		
		private function checkIfStreamIsPublishing():void {
			baseConnection.connection.call("deskshare.checkIfStreamIsPublishing", new Responder(function(result:Object):void {
				if (result != null && (result.publishing as Boolean)) {
					streamHeight = result.height as Number;
					streamWidth = result.width as Number;
					
					LOGGER.debug("Deskshare stream is streaming [{0},{1}]", [streamWidth, streamHeight]);
					
					// if we receive result from the server, then somebody is sharing their desktop - dispatch the notification signal
					isStreaming = true;
				} else {
					LOGGER.info("No deskshare stream being published");
				}
			}, function(status:Object):void {
				LOGGER.error("Error while trying to call remote method on the server");
			}), _room);
		}
		
		private function onConnectionFailure(reason:String):void {
			_connectionFailureSignal.dispatch(reason);
		}
		
		public function get applicationURI():String {
			return _applicationURI;
		}
		
		public function set applicationURI(value:String):void {
			_applicationURI = value;
		}
		
		public function get streamWidth():Number {
			return _streamWidth;
		}
		
		public function set streamWidth(value:Number):void {
			_streamWidth = value;
		}
		
		public function get streamHeight():Number {
			return _streamHeight;
		}
		
		public function set streamHeight(value:Number):void {
			_streamHeight = value;
		}
		
		public function get room():String {
			return _room;
		}
		
		public function set room(value:String):void {
			_room = value;
		}
		
		public function get connection():NetConnection {
			return baseConnection.connection;
		}
		
		public function connect():void {
			baseConnection.connect(applicationURI);
		}
		
		public function get isStreaming():Boolean {
			return _isStreaming;
		}
		
		public function set isStreaming(value:Boolean):void {
			_isStreaming = value;
			isStreamingSignal.dispatch(_isStreaming);
		}
		
		public function get isStreamingSignal():ISignal {
			return _isStreamingSignal;
		}
		
		public function get mouseLocationChangedSignal():ISignal {
			return _mouseLocationChangedSignal;
		}
		
		public function get connectionFailureSignal():ISignal {
			return _connectionFailureSignal;
		}
		
		public function get connectionSuccessSignal():ISignal {
			return _connectionSuccessSignal;
		}
		
		public function appletStarted(videoWidth:Number, videoHeight:Number):void {
			LOGGER.info("appletStarted() sharing.");
		}
		
		/**
		 * Called by the server when a notification is received to start viewing the broadcast stream.
		 * This method is called on successful execution of sendStartViewingNotification()
		 *
		 */
		public function startViewing(videoWidth:Number, videoHeight:Number):void {
			LOGGER.info("startViewing()");
			streamWidth = videoWidth;
			streamHeight = videoHeight;
			isStreaming = true;
		}
		
		/**
		 * Called by the server to notify clients that the deskshare stream has stopped.
		 */
		public function deskshareStreamStopped():void {
			LOGGER.info("deskshareStreamStopped()");
			isStreaming = false;
		}
		
		/**
		 * Called by the server to notify clients that mouse location has changed
		 */
		public function mouseLocationCallback(x:Number, y:Number):void {
			_mouseLocationChangedSignal.dispatch(x, y);
		}
	}
}
