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
 * MCP Server Resources List. <a href=
 * "https://modelcontextprotocol.io/specification/2025-06-18/server/resources#listing-resources">Listing
 * resources</a>
 */
public class ResourcesGetTest {

	public static final String RESOURCES_GET = """
			{
			  "jsonrpc": "2.0",
			  "id": 2,
			  "method": "resources/read",
			  "params": {
			    "uri": "file:///project/src/main.rs"
			  }
			}""";

	public static final String RESOURCES_GET_RESULT = """
			{
			  "jsonrpc": "2.0",
			  "id": 2,
			  "result": {
			    "contents": [
			      {
			        "uri": "file:///project/src/main.rs",
			        //"name": "main.rs",
			        //"title": "Rust Software Application Main File",
			        "mimeType": "text/x-rust",
			        "text": "fn main() {\\n    println!(\\"Hello world!\\");\\n}"
			      }
			    ]
			  }
			}""";

	@Test
	public void resourcesGet() throws Exception {
		McpHttpServer server = McpHttpServer.getDefault();
		HttpRequest request = HttpRequestUtils.POST(server, RESOURCES_GET);
		HttpClient client = HttpClient.newBuilder().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		String json = response.body();
		JSONAssert.assertEquals(RESOURCES_GET_RESULT, json, true);
	}

}
