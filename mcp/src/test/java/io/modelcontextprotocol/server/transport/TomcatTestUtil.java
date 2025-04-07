/*
* Copyright 2025 - 2025 the original author or authors.
*/
package io.modelcontextprotocol.server.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Servlet;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.startup.Tomcat;

import static org.junit.Assert.assertThat;

/**
 * @author Christian Tzolov
 */
public class TomcatTestUtil {

	public static Tomcat createTomcatServer(String contextPath, int port, Servlet servlet) {

		var tomcat = new Tomcat();
		tomcat.setPort(port);

		String baseDir = System.getProperty("java.io.tmpdir");
		tomcat.setBaseDir(baseDir);

		// Context context = tomcat.addContext("", baseDir);
		Context context = tomcat.addContext(contextPath, baseDir);

		// Add transport servlet to Tomcat
		org.apache.catalina.Wrapper wrapper = context.createWrapper();
		wrapper.setName("mcpServlet");
		wrapper.setServlet(servlet);
		wrapper.setLoadOnStartup(1);
		wrapper.setAsyncSupported(true);
		context.addChild(wrapper);
		context.addServletMappingDecoded("/*", "mcpServlet");

		var connector = tomcat.getConnector();
		connector.setAsyncTimeout(3000);

		return tomcat;
	}

}
