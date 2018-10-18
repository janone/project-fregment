package com.daogj.core.log;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Appender;
import org.apache.log4j.DailyMaxRollingFileAppender;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggingEvent;

public class ThreadLoggingAppender extends DailyMaxRollingFileAppender{
	
	
	private Logger log = LogManager.getLogger(ThreadLoggingAppender.class);
	
	
	
	/**
	 * 用户记录当前线程的日志
	 */
	private ThreadLocal<List<LoggingEvent>> eventListHolder = new ThreadLocal<>();
	
	/**
	 * 用户记录当前线程的日志
	 */
	private ThreadLocal<String> keyHolder = new ThreadLocal<>();
	
	
	/**
	 * rootLog中的线程日志appender
	 */
	private ThreadLoggingAppender appender = null;

	
	/**
	 * 跑日志输出的线程
	 */
	private ExecutorService executorService = null;
	
	
	/**
	 * 记录所有线程，用于遍历检查去超时线程
	 */
	private Map<String,List<LoggingEvent>> allThreadLogMap = new ConcurrentHashMap<>();
	
	
	/**
	 * 线程超时时间，超过该值(秒)，将会直接输出日志，避免线程卡住看不到错误日志
	 */
	private long watchPeriod = 15L;//10秒
	
	
	
	

	private void watchThreadPeriodly() {
		
		
		String logMessage = "线程时间超过 " + watchPeriod + "秒，直接输出日志";
	
		Thread watchDog = new Thread(()->{
			
			while(true){
				
				try {
			
					
					Thread.sleep(watchPeriod*1000);
					
					//当前时间
					long nowTime = System.currentTimeMillis();
					long exceedTime = watchPeriod*1000;
					
					//遍历所有线程，如果线程时间大于15秒，就直接输出日志.避免线程卡住无法查看到卡住的日志
					for(Entry<String, List<LoggingEvent>> en : allThreadLogMap.entrySet()){
						
						//获取开始时间
						long startTime = new Long(en.getKey().split(":")[0]);
						long usetime = nowTime - startTime;
						
						if(  usetime > exceedTime ){
							
							log.warn(logMessage);
							addToPrintTaskQueue(en.getValue());
							allThreadLogMap.remove(en.getKey());
						}
						
					}
					
				} catch (Exception e) {
					log.error("threadlog watch dog error",e);;
				}
				
			}
			
		});
		
		watchDog.setDaemon(true);
		watchDog.setName("threadlog watch dog");
		watchDog.start();
	}

	/**
	 * 不在线程内的日志
	 */
	public volatile List<LoggingEvent> otherLoggingEvents = new LinkedList<>();
	
	
	/**
	 * 默认为true。非线程日志会立即输出，但是每个日志都会生成一个runnable任务加入到队列中，比较耗资源
	 * 设置为false。则每半秒生成一个任务。
	 */
	private boolean printOtherLogsRegular = false;
	
	public ThreadLoggingAppender() {
		
		super();
		
		
		if(!printOtherLogsRegular){
			return;//设置为立即打印，则不再执行下面代码
		}
			
		
		printOtherLogsRegularInThread();
		
		
	}


	private void printOtherLogsRegularInThread() {
		
		Thread loopThread = new Thread(() -> {
			
			try {
				
				
				while(true){
					//每半秒输出一次非线程日志。避免每条日志生成一个runnable造成资源浪费。
					Thread.sleep(500);
					
					if(otherLoggingEvents.size() > 0){
						addToPrintTaskQueue(otherLoggingEvents);
						otherLoggingEvents = new LinkedList<>();
					}
				}
				
				
			} catch (Exception e) {
				log.error("循环输出非线程日志错误",e);
			}
			
		});
		
		loopThread.setDaemon(true);
		loopThread.start();
	}
	
	
	/**
	 * 线程中有标记，就记录到List中
	 * 没标记就加到其他日志队列中
	 * @param event
	 */
	@Override
	protected void subAppend(LoggingEvent event) {
		
		
		if(appender == null){
			superSubAppend(event);
			return;
		}
		
		//异步服务关闭了，就直接打印
		if(executorService == null || executorService.isShutdown()){
			superSubAppend(event);
			return;
		}
		
		
		
		//不加这下面的代码，无法打印类.方法(行号),会出现?.?(?)
		//////////////////////////////////////
		initLoggingEvent(event);

		
		
	    List<LoggingEvent> loggingEventList = eventListHolder.get();
	    
		//没有标记，加入到其他日志list。非请求日志
		if( loggingEventList == null ){
			
			//集中起来，每半秒打印一次。
			if(printOtherLogsRegular){
				otherLoggingEvents.add(event);
				
			}else{//立即添加到队列中打印
				addToPrintTaskQueue(Arrays.asList(event));
			}
			
			
		} else {
			//请求日志
			loggingEventList.add(event);
		}
	}


	private void initLoggingEvent(LoggingEvent event) {
		// Set the NDC and thread name for the calling thread as these
	    // LoggingEvent fields were not set at event creation time.
		event.getNDC();
	    event.getThreadName();
	    // Get a copy of this thread's MDC.
	    event.getMDCCopy();
	    if (!event.locationInformationExists()) {
	      event.getLocationInformation();
	    }
	    event.getRenderedMessage();
	    event.getThrowableStrRep();
	}
	
	
	/**
	 * 真实的打印
	 * @param event
	 */
	public void superSubAppend(LoggingEvent event){
		super.subAppend(event);
	}
	
	
	/**
	 * filter中发出异步打印当前线程的所有日志。并且清除当前请求的标记
	 * 1、清除线程标记，避免后续的请求日志打到同一个日志单位中
	 * 2、打印日志
	 * 3、清除watchdog的检查列表
	 * 
	 */
	public void asynPrintRequestLogAndRemoveMark() {
		
		if(appender == null){
			return;
		}
		
		
		List<LoggingEvent> eventList = eventListHolder.get();
		
		eventListHolder.remove();
		removeToWatch();
		addToPrintTaskQueue(eventList);
		
	}

	/**
	 * 加入打印任务队列
	 * @param loggingEventS
	 */
	private void addToPrintTaskQueue(List<LoggingEvent> loggingEventS) {

		executorService.execute(()->{
			for(LoggingEvent event : loggingEventS){
				appender.superSubAppend(event);
			}
		});
		
	}
	
	
	/**
	 * 标记当前线程为需要打印线程日志
	 */
	public void markCurrentThreadLoggingEvents(){
		
		if(appender == null){
			return;
		}
		
		LinkedList<LoggingEvent> linkedList = new LinkedList<>();
		
		eventListHolder.set(linkedList);
		
		putToWatch(linkedList, System.currentTimeMillis());
	}



	public void setPrintOtherLogsRegular(boolean printOtherLogsRegular) {
		this.printOtherLogsRegular = printOtherLogsRegular;
	}


	public void shutDownLoggingQueue(){
		if(executorService != null){
			executorService.shutdown();
		}
	}
	
	
	public static ThreadLoggingAppender findThreadLoggingAppenderFromRootLoggerAndInit(){
		
		ThreadLoggingAppender logAppender = null;
		
		
		Enumeration<? extends Appender> allAppenders = LogManager.getRootLogger().getAllAppenders();
		
		while(allAppenders.hasMoreElements()){
			Appender appenderObj = allAppenders.nextElement();
			if(appenderObj instanceof ThreadLoggingAppender){
				logAppender = (ThreadLoggingAppender) appenderObj;
				break;
			}
		}
		
		
		if(logAppender != null){
			logAppender.init();
		}
    	
    	return logAppender;
    	
	}
	
	public void init(){
		
		setRootLoggerAppender(this);
		executorService = Executors.newFixedThreadPool(1);
		watchThreadPeriodly();
		
	}


	public void putToWatch(List<LoggingEvent> eventList, Long startTime){
		
		String key = startTime + ":" + Thread.currentThread().getId() + ":" + Math.random();
		keyHolder.set(key);
		allThreadLogMap.put(key,eventList);
	}
	
	public void removeToWatch(){
		allThreadLogMap.remove(keyHolder.get());
	}
	
	
	public long getWatchPeriod() {
		return watchPeriod;
	}


	public void setWatchPeriod(long watchPeriod) {
		this.watchPeriod = watchPeriod;
	}
	
	
	public void setRootLoggerAppender(ThreadLoggingAppender appender){
		this.appender = appender;
	}
	
	
	

}
