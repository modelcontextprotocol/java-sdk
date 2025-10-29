package io.modelcontextprotocol.server.httpserver.sync;

import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@SelectPackages("io.modelcontextprotocol.server.http.tck")
@Suite
@SuiteDisplayName("MCP HTTP Server TCK for Java built-in HTTP Server Sync")
public class HttpServerSyncSuite {

}
