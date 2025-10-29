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
 * MCP Server Getting a Prompt. <a href=
 * "https://modelcontextprotocol.io/specification/2025-06-18/server/prompts#getting-a-prompt">Getting
 * a Prompt</a>
 */
public class PromptsGetTest {

	public static final String PROMPTS_GET = """
			{
			  "jsonrpc": "2.0",
			  "id": 2,
			  "method": "prompts/get",
			  "params": {
			    "name": "code_review",
			    "arguments": {
			      "code": "def hello():\\n    print('world')"
			    }
			  }
			}""";

	public static final String PROMPTS_GET_RESULT = """
			{
			  "jsonrpc": "2.0",
			  "id": 2,
			  "result": {
			    "description": "Code review prompt",
			    "messages": [
			      {
			        "role": "user",
			        "content": {
			          "type": "text",
			          "text": "Please review this Python code"
			        }
			      }
			    ]
			  }
			}""";

	@Test
	public void promptsListTest() throws Exception {
		McpHttpServer server = McpHttpServer.getDefault();
		HttpRequest request = HttpRequestUtils.POST(server, PROMPTS_GET);
		HttpClient client = HttpClient.newBuilder().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		String json = response.body();
		JSONAssert.assertEquals(PROMPTS_GET_RESULT, json, true);
	}

}
