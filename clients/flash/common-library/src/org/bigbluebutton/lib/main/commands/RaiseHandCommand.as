package org.bigbluebutton.lib.main.commands {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.bigbluebutton.lib.main.models.IUserSession;
	import org.bigbluebutton.lib.user.services.IUsersService;
	
	import robotlegs.bender.bundles.mvcs.Command;
	
	public class RaiseHandCommand extends Command {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(RaiseHandCommand);
		
		[Inject]
		public var userSession:IUserSession;
		
		[Inject]
		public var userService:IUsersService;
		
		[Inject]
		public var userId:String;
		
		[Inject]
		public var raised:Boolean;
		
		override public function execute():void {
			if (raised) {
				LOGGER.info("RaiseHandCommand.execute() - handRaised");
				userService.raiseHand();
			} else {
				LOGGER.info("RaiseHandCommand.execute() - hand lowered for user {0} by user {0}", [userId]);
				userService.lowerHand(userId);
			}
		}
	}
}
