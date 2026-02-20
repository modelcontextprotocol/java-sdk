/*
 * Copyright 2025 the original author or authors.
 */
package io.modelcontextprotocol.client;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import io.modelcontextprotocol.client.McpClient.SyncSpec;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingMessageNotification;

/**
 * MCP Client-side consumer which logs received messages from MCP Servers using SLF4J.
 *
 * <p>
 * Use this for {@link SyncSpec#loggingConsumer(Consumer)} to log received MCP messages.
 *
 * @author <a href="http://www.vorburger.ch">Michael Vorburger.ch</a>
 */
public class Slf4jLoggingConsumer implements Consumer<McpSchema.LoggingMessageNotification> {

	// This class originated in
	// https://github.com/enola-dev/enola/blob/ffc004666ea7f71357562ef12464d2b9fdbf9dbd/java/dev/enola/ai/mcp/McpServer.java#L29
	// where it was used for https://docs.enola.dev/concepts/mcp/.
	//
	// It then found its way into Google's Agent Development Kit (ADK) in
	// https://github.com/google/adk-java/pull/370. It's now been moved here to be useful
	// to others.

	private static final Logger LOG = LoggerFactory.getLogger(Slf4jLoggingConsumer.class);

	@Override
	public void accept(LoggingMessageNotification notif) {
		if (notif.meta().isEmpty()) {
			// If no meta, then just log the data as a message
			LOG.atLevel(convert(notif.level())).log(notif.data());
		}
		else {
			// If there is meta, then log it as a structured log message
			var builder = LOG.atLevel(convert(notif.level())).setMessage(notif.data());
			notif.meta().forEach((key, value) -> builder.addKeyValue(key, value));
			builder.log();
		}
	}

	private Level convert(McpSchema.LoggingLevel level) {
		return switch (level) {
			case DEBUG -> Level.DEBUG;
			case INFO, NOTICE -> Level.INFO;
			case WARNING -> Level.WARN;
			case ERROR, CRITICAL, ALERT, EMERGENCY -> Level.ERROR;
		};
	}

}
