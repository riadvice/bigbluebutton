package org.bigbluebutton.lib.presentation.commands {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.presentation.models.Slide;
	import org.bigbluebutton.lib.presentation.services.LoadSlideService;
	
	import robotlegs.bender.bundles.mvcs.Command;
	
	public class LoadSlideCommand extends Command {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(LoadSlideCommand);
		
		[Inject]
		public var slide:Slide;
		
		private var _loadSlideService:LoadSlideService;
		
		public function LoadSlideCommand() {
			super();
		}
		
		override public function execute():void {
			if (slide != null) {
				_loadSlideService = new LoadSlideService(slide);
			} else {
				LOGGER.warn("LoadSlideCommand: requested slide is null and cannot be loaded");
			}
		}
	}
}
