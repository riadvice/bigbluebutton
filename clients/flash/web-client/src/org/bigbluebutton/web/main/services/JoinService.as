package org.bigbluebutton.web.main.services {
	
	import flash.net.URLRequest;
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.common.utils.URLFetcher;
	import org.osflash.signals.ISignal;
	import org.osflash.signals.Signal;
	
	public class JoinService {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(JoinService);
		
		private static const URL_REQUEST_ERROR_TYPE:String = "TypeError";
		
		private static const URL_REQUEST_INVALID_URL_ERROR:String = "invalidURL";
		
		private static const URL_REQUEST_GENERIC_ERROR:String = "genericError";
		
		private static const XML_RETURN_CODE_FAILED:String = "FAILED";
		
		private static const JOIN_URL_EMPTY:String = "emptyJoinUrl";
		
		protected var _successSignal:Signal = new Signal();
		
		protected var _failureSignal:Signal = new Signal();
		
		public function get successSignal():ISignal {
			return _successSignal;
		}
		
		public function get failureSignal():ISignal {
			return _failureSignal;
		}
		
		public function join(joinUrl:String):void {
			LOGGER.fatal("JOIN SERVICE - DONT LEAVE THIS IN");
			
			if (joinUrl.length == 0) {
				onFailure(JOIN_URL_EMPTY);
				return;
			}
			var fetcher:URLFetcher = new URLFetcher();
			fetcher.successSignal.add(onSuccess);
			fetcher.failureSignal.add(onFailure);
			fetcher.fetch(joinUrl);
		}
		
		protected function onSuccess(data:Object, responseUrl:String, urlRequest:URLRequest):void {
			try {
				var xml:XML = new XML(data);
				if (xml.returncode == XML_RETURN_CODE_FAILED) {
					onFailure(xml.messageKey);
					return;
				}
			} catch (e:Error) {
				LOGGER.error("The response is probably not a XML. {0}", [e.message]);
				successSignal.dispatch(urlRequest, responseUrl);
				return;
			}
			onFailure(URL_REQUEST_GENERIC_ERROR);
		}
		
		protected function onFailure(reason:String):void {
			failureSignal.dispatch(reason);
		}
	}
}
