package org.bigbluebutton.lib.presentation.services {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.main.models.IUserSession;
	
	public class PresentMessageSender {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(PresentMessageSender);
		
		public var userSession:IUserSession;
		
		// The default callbacks of userSession.mainconnection.sendMessage
		private var defaultSuccessResponse:Function = function(result:String):void {
			LOGGER.info(result);
		};
		
		private var defaultFailureResponse:Function = function(status:String):void {
			LOGGER.error(status);
		};
		
		private var presenterViewedRegionX:Number = 0;
		
		private var presenterViewedRegionY:Number = 0;
		
		private var presenterViewedRegionW:Number = 100;
		
		private var presenterViewedRegionH:Number = 100;
		
		public function getPresentationInfo():void {
			LOGGER.info("PresentMessageSender::getPresentationInfo() -- Sending [presentation.getPresentationInfo] message to server")
			userSession.mainConnection.sendMessage("presentation.getPresentationInfo", defaultSuccessResponse, defaultFailureResponse);
		}
		
		public function gotoSlide(id:String):void {
			LOGGER.info("PresentMessageSender::gotoSlide() -- Sending [presentation.gotoSlide] message to server with message [page:{0}]", [id]);
			var message:Object = new Object();
			message["page"] = id;
			userSession.mainConnection.sendMessage("presentation.gotoSlide", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		/***
		 * A hack for the viewer to sync with the presenter. Have the viewer query the presenter for it's x,y,width and height info.
		 */
		public function move(xOffset:Number, yOffset:Number, widthRatio:Number, heightRatio:Number):void {
			LOGGER.debug("PresentMessageSender::move() -- Sending [presentation.resizeAndMoveSlide] message to server with message [xOffset:{0}, yOffset:{1}, widthRatio:{2}, heightRatio:{3}]", [xOffset, yOffset, widthRatio, heightRatio]);
			var message:Object = new Object();
			message["xOffset"] = xOffset;
			message["yOffset"] = yOffset;
			message["widthRatio"] = widthRatio;
			message["heightRatio"] = heightRatio;
			userSession.mainConnection.sendMessage("presentation.resizeAndMoveSlide", defaultSuccessResponse, defaultFailureResponse, message);
			presenterViewedRegionX = xOffset;
			presenterViewedRegionY = yOffset;
			presenterViewedRegionW = widthRatio;
			presenterViewedRegionH = heightRatio;
		}
		
		public function removePresentation(name:String):void {
			LOGGER.info("PresentMessageSender::removePresentation() -- Sending [presentation.removePresentation] message to server with message [presentationID:{0}]", [name]);
			LOGGER.info("  |- name : {0}", [name]);
			var message:Object = new Object();
			message["presentationID"] = name;
			userSession.mainConnection.sendMessage("presentation.removePresentation", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function sendCursorUpdate(xPercent:Number, yPercent:Number):void {
			LOGGER.info("PresentMessageSender::sendCursorUpdate() -- Sending [presentation.sendCursorUpdate] message to server with message [xPercent:{0}, yPercent:{1}]", [xPercent, yPercent]);
			var message:Object = new Object();
			message["xPercent"] = xPercent;
			message["yPercent"] = yPercent;
			userSession.mainConnection.sendMessage("presentation.sendCursorUpdate", defaultSuccessResponse, defaultFailureResponse, message);
		}
		
		public function sharePresentation(share:Boolean, presentationID:String):void {
			LOGGER.info("PresentMessageSender::sharePresentation() -- Sending [presentation.sharePresentation] message to server with message [presentationID:{0}, share:{1}", [presentationID, share]);
			var message:Object = new Object();
			message["presentationID"] = presentationID;
			message["share"] = share;
			userSession.mainConnection.sendMessage("presentation.sharePresentation", defaultSuccessResponse, defaultFailureResponse, message);
		}
	}
}
