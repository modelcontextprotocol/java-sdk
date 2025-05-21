package io.modelcontextprotocol.spec;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import reactor.core.publisher.Mono;

public interface McpServerSession extends McpSession {

  String getId();

  Mono<Void> handle(McpSchema.JSONRPCMessage message);

  /**
   * Request handler for the initialization request.
   */
  interface InitRequestHandler {

    /**
     * Handles the initialization request.
     * @param initializeRequest the initialization request by the client
     * @return a Mono that will emit the result of the initialization
     */
    Mono<McpSchema.InitializeResult> handle(McpSchema.InitializeRequest initializeRequest);

  }

  /**
   * Notification handler for the initialization notification from the client.
   */
  interface InitNotificationHandler {

    /**
     * Specifies an action to take upon successful initialization.
     * @return a Mono that will complete when the initialization is acted upon.
     */
    Mono<Void> handle();

  }

  /**
   * A handler for client-initiated notifications.
   */
  interface NotificationHandler {

    /**
     * Handles a notification from the client.
     * @param exchange the exchange associated with the client that allows calling
     * back to the connected client or inspecting its capabilities.
     * @param params the parameters of the notification.
     * @return a Mono that completes once the notification is handled.
     */
    Mono<Void> handle(McpAsyncServerExchange exchange, Object params);

  }

  /**
   * A handler for client-initiated requests.
   *
   * @param <T> the type of the response that is expected as a result of handling the
   * request.
   */
  interface RequestHandler<T> {

    /**
     * Handles a request from the client.
     * @param exchange the exchange associated with the client that allows calling
     * back to the connected client or inspecting its capabilities.
     * @param params the parameters of the request.
     * @return a Mono that will emit the response to the request.
     */
    Mono<T> handle(McpAsyncServerExchange exchange, Object params);

  }

  /**
   * Factory for creating server sessions which delegate to a provided 1:1 transport
   * with a connected client.
   */
  @FunctionalInterface
  interface Factory {

    /**
     * Creates a new 1:1 representation of the client-server interaction.
     * @param sessionTransport the transport to use for communication with the client.
     * @return a new server session.
     */
    McpServerSession create(McpServerTransport sessionTransport);

  }
}
