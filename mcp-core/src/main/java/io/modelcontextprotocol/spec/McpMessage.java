package io.modelcontextprotocol.spec;

import java.util.Map;
import java.util.Optional;

/**
 * An MCP message base class for message driven architecture.
 *
 */
public sealed interface McpMessage {

	/**
	 * @return additional metadata related to this resource.
	 * @see <a href=
	 * "https://modelcontextprotocol.io/specification/2025-06-18/basic/index#meta">Specification</a>
	 * for notes on _meta usage
	 */
	Map<String, Object> meta();

	sealed interface McpEvent extends McpMessage {

		record TaskCreated(McpSchema.Task task, Map<String, Object> meta) implements McpEvent {
		}

		record TaskStatusUpdated(McpSchema.Task task, Map<String, Object> meta) implements McpEvent {
		}

		record TaskProgressUpdated(String taskId, Object progressToken, Map<String, Object> meta) implements McpEvent {
		}

		record TaskResultRetrieved(Optional<String> taskId, McpSchema.CallToolResult result,
				Map<String, Object> meta) implements McpEvent {
		}

		record TaskFailed(Optional<String> taskId, McpError error, Map<String, Object> meta) implements McpEvent {
		}

	}

}
