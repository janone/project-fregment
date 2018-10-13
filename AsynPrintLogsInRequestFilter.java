package com.daogj.core.log;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;


/**
 * 
 * 
 * @Filename: AsynPrintLogsInRequestFilter.java
 * @Version: 1.0
 * @Author: 杨总
 * @Email: yangzong@sina.com
 *
 */
public class AsynPrintLogsInRequestFilter implements Filter {
	
	private Logger log = LogManager.getLogger(this.getClass());

    public void destroy() {
    	
    	if(myAppender != null){
    		myAppender.shutDownLoggingQueue();
    	}
    	
    }

    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain filterChain) throws IOException, ServletException {
        
    	
    	if(myAppender == null){
    		filterChain.doFilter(servletRequest, servletResponse);
    		return;
    	}
    	
    	
    	
    	try{
        	
    		//标记线程为需要输出线程日志
    		myAppender.markCurrentThreadLoggingEvents();
        	
        	filterChain.doFilter(servletRequest, servletResponse);
        	
        }finally{
        	
        	//输出线程日志，并清除标记
        	myAppender.asynPrintRequestLogAndRemoveMark();
        	
        }

    }

    private ThreadLoggingAppender myAppender = null;
    
    public void init(FilterConfig arg0) throws ServletException {
    	
    	myAppender = ThreadLoggingAppender.findThreadLoggingAppenderFromRootLoggerAndInit();
    	
    	if(myAppender == null){
    		log.warn("note : AsynPrintLogsInRequest Appender not found");
    	
    	}
    	
    }

}
