package io.modelcontextprotocol.schema;

import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * @author Aliaksei Darafeyeu
 */
public class GenericEnumModule extends SimpleModule {

	public GenericEnumModule() {
		addSerializer(McpSchema.Role.class, new GenericEnumSerializer());
		addDeserializer(McpSchema.Role.class, new GenericEnumDeserializer<>(McpSchema.Role.class));

		addSerializer(McpSchema.CreateMessageRequest.ContextInclusionStrategy.class, new GenericEnumSerializer());
		addDeserializer(McpSchema.CreateMessageRequest.ContextInclusionStrategy.class,
				new GenericEnumDeserializer<>(McpSchema.CreateMessageRequest.ContextInclusionStrategy.class));

		addSerializer(McpSchema.CreateMessageResult.StopReason.class, new GenericEnumSerializer());
		addDeserializer(McpSchema.CreateMessageResult.StopReason.class,
				new GenericEnumDeserializer<>(McpSchema.CreateMessageResult.StopReason.class));

		addSerializer(McpSchema.LoggingLevel.class, new GenericEnumSerializer());
		addDeserializer(McpSchema.LoggingLevel.class, new GenericEnumDeserializer<>(McpSchema.LoggingLevel.class));
	}

}
