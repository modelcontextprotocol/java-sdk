/*
 * Copyright 2017-2025 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.modelcontextprotocol.server.http.tck;

import io.modelcontextprotocol.server.McpHttpServer;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MCP Server Initialization Phase. <a href=
 * "https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle#initialization">Initialization</a>
 */
public class InitializeTest {

	private static final String INITIALIZE = """
			{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"sampling":{},"elicitation":{},"roots":{"listChanged":true}},"clientInfo":{"name":"mcp-inspector","version":"0.16.3"}}}""";

	private static final String EXPECTED_INITIALIZATION = """
			{
			  "jsonrpc":"2.0",
			  "id":0,
			  "result": {
			    "protocolVersion":"2025-06-18",
			     "capabilities": {
			       "prompts": {
			         "listChanged": false
			       },
			       "resources": {
			         "subscribe": false, "listChanged": false
			       },
			       "tools": {
			         "listChanged": false
			       }
			     },
			     "serverInfo": {
			       "name": "mcp-server",
			       "version": "0.0.1"
			     }
			   }
			}""";

	@Test
	public void initializeTest() throws Exception {
		McpHttpServer server = McpHttpServer.getDefault();
		HttpRequest request = HttpRequestUtils.POST(server, INITIALIZE);
		HttpClient client = HttpClient.newBuilder().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		JSONAssert.assertEquals(EXPECTED_INITIALIZATION, response.body(), true);
	}

}
