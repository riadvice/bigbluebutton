package org.bigbluebutton.lib.presentation.services {
	
	import flash.display.Loader;
	import flash.events.Event;
	import flash.net.URLRequest;
	import flash.system.LoaderContext;
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.presentation.models.Slide;
	
	public class LoadSlideService {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(LoadSlideService);
		
		private var _loader:Loader = new Loader();
		
		private var _slide:Slide;
		
		public function LoadSlideService(s:Slide) {
			LOGGER.info("LoadSlideService: loading a new slide");
			_slide = s;
			_loader.contentLoaderInfo.addEventListener(Event.COMPLETE, handleLoaderComplete);
			_loader.load(new URLRequest(_slide.slideURI));
		}
		
		private function handleLoaderComplete(e:Event):void {
			var context:LoaderContext = new LoaderContext();
			context.allowCodeImport = true;
			_slide.SWFFile.loaderContext = context;
			_slide.swfSource = e.target.bytes;
			LOGGER.info("LoadSlideService: loading of slide data finished successfully");
		}
	}
}
