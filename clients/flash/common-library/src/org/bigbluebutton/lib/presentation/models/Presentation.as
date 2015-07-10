package org.bigbluebutton.lib.presentation.models {
	
	import org.as3commons.logging.api.ILogger;
	import org.as3commons.logging.api.getClassLogger;
	import org.osflash.signals.ISignal;
	import org.osflash.signals.Signal;
	
	public class Presentation {
		
		//--------------------------------------------------------------------------
		//
		//  Class Constants
		//
		//--------------------------------------------------------------------------
		
		private static const LOGGER:ILogger = getClassLogger(Presentation);
		
		private var _fileName:String = "";
		
		private var _slides:Vector.<Slide> = new Vector.<Slide>();
		
		private var _changePresentation:Function;
		
		private var _currentSlideNum:int = -1;
		
		private var _current:Boolean = false;
		
		private var _slideChangeSignal:ISignal = new Signal();
		
		public function Presentation(fileName:String, changePresentation:Function, numOfSlides:int, isCurrent:Boolean):void {
			_fileName = fileName;
			_slides = new Vector.<Slide>(numOfSlides);
			_changePresentation = changePresentation;
			_current = isCurrent;
		}
		
		public function get fileName():String {
			return _fileName;
		}
		
		public function get slides():Vector.<Slide> {
			return _slides;
		}
		
		public function getSlideAt(num:int):Slide {
			if (_slides.length > num) {
				return _slides[num];
			}
			LOGGER.warn("getSlideAt failed: Slide index out of bounds");
			return null;
		}
		
		public function add(slide:Slide):void {
			_slides[slide.slideNumber - 1] = slide;
			if (slide.current == true) {
				_currentSlideNum = slide.slideNumber - 1;
			}
		}
		
		public function size():uint {
			return _slides.length;
		}
		
		public function show():void {
			_changePresentation(this);
			_slideChangeSignal.dispatch();
		}
		
		public function set currentSlideNum(n:int):void {
			_slides[_currentSlideNum].current = false;
			_currentSlideNum = n - 1;
			_slides[_currentSlideNum].current = true;
			_slideChangeSignal.dispatch();
		}
		
		public function get currentSlideNum():int {
			return _currentSlideNum;
		}
		
		public function set current(b:Boolean):void {
			_current = b;
		}
		
		public function get current():Boolean {
			return _current;
		}
		
		public function get slideChangeSignal():ISignal {
			return _slideChangeSignal;
		}
		
		public function clear():void {
			_slides = new Vector.<Slide>();
		}
	}
}
