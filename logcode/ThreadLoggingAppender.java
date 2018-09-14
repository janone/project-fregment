package com.daogj.web.util;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.Appender;
import org.apache.log4j.DailyMaxRollingFileAppender;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.LoggerRepository;
import org.apache.log4j.spi.LoggingEvent;

public class ThreadLoggingAppender extends DailyMaxRollingFileAppender{
	
	
	private Logger log = LogManager.getLogger(ThreadLoggingAppender.class);
	
	
	public ThreadLocal<List<LoggingEvent>> currentThreadLoggingEventList = new ThreadLocal<>();
	
	private ThreadLoggingAppender appender = findThreadLoggingAppenderFromRootLogger();

	private ExecutorService executorService = null;
	
	{
		if(appender != null){
			executorService = Executors.newFixedThreadPool(1);
		}
	}
	
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
		
		Thread loopThread = new Thread(()->{
			
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
			return;
		}
		
		//异步服务关闭了，就直接打印
		if(executorService.isShutdown()){
			superSubAppend(event);
			return;
		}
		
		
		
		//不加这下面的代码，无法打印类.方法(行号),会出现?.?(?)
		//////////////////////////////////////
		initLoggingEvent(event);

		
		
	    List<LoggingEvent> loggingEventList = currentThreadLoggingEventList.get();
	    
		//没有标记，加入到其他日志list。
		if( loggingEventList == null ){
			
			//集中起来，每半秒打印一次。
			if(printOtherLogsRegular){
				otherLoggingEvents.add(event);
				
			}else{//立即添加到队列中打印
				addToPrintTaskQueue(Arrays.asList(event));
			}
			
			
		} else {
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
	 * 异步打印当前线程的所有日志。并且清除当前请求的标记
	 */
	public void asynPrintRequestLogAndRemoveMark(){
		
		
		List<LoggingEvent> list = currentThreadLoggingEventList.get();
		currentThreadLoggingEventList.remove();
		
		if(list == null || list.isEmpty()){
			return;
		}
		
		addToPrintTaskQueue(list);
		
		
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
		currentThreadLoggingEventList.set(new LinkedList<>());
	}



	public void setPrintOtherLogsRegular(boolean printOtherLogsRegular) {
		this.printOtherLogsRegular = printOtherLogsRegular;
	}


	public void shutDownLoggingQueue(){
		if(executorService != null){
			executorService.shutdown();
		}
	}
	
	
	public static ThreadLoggingAppender findThreadLoggingAppenderFromRootLogger(){
		
		ThreadLoggingAppender logAppender = null;
		
		
		Enumeration<? extends Appender> allAppenders = LogManager.getRootLogger().getAllAppenders();
		
		while(allAppenders.hasMoreElements()){
			Appender appenderObj = allAppenders.nextElement();
			if(appenderObj instanceof ThreadLoggingAppender){
				logAppender = (ThreadLoggingAppender) appenderObj;
				break;
			}
		}
			
		
    	
    	return logAppender;
    	
	}
	

}
