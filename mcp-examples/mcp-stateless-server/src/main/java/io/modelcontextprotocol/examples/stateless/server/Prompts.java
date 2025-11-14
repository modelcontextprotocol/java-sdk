package io.modelcontextprotocol.examples.stateless.server;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;

import static io.modelcontextprotocol.spec.McpSchema.Role.USER;

public interface Prompts {

	SyncPromptSpecification greetingPrompt = new SyncPromptSpecification(
			new Prompt("greeting", "Greeting Prompt",
					List.of(new McpSchema.PromptArgument("name", "Name of the person to greet", true))),
			(transportContext, getPromptRequest) -> {
				String name = String.valueOf(getPromptRequest.arguments().get("name"));
				return new GetPromptResult("greeting",
						List.of(new PromptMessage(USER, new TextContent("Hello " + name + "!"))));
			});

}
