package io.modelcontextprotocol.examples.stateless.server;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;

import java.util.List;

public interface Resources {

	SyncResourceSpecification testResources = new SyncResourceSpecification(
			Resource.builder()
				.name("test")
				.uri("https://modelcontextprotocol.io")
				.description("A test resource")
				.mimeType("text/plain")
				.size(10L)
				.build(),
			(transportContext, readResourceRequest) -> new ReadResourceResult(
					List.of(new McpSchema.TextResourceContents("https://modelcontextprotocol.io", "text/plain",
							"0123456789"))));

}
