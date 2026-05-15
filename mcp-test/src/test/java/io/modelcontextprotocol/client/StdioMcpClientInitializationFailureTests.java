/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static io.modelcontextprotocol.util.McpJsonMapperUtils.JSON_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Tests for initialization failures reported by {@link StdioClientTransport}.
 *
 * @author Dongliang Xie
 */
@Timeout(10)
class StdioMcpClientInitializationFailureTests {

	@Test
	void initializeShouldFailWithProcessExitInsteadOfRequestTimeout() {
		Duration requestTimeout = Duration.ofSeconds(3);
		String classpath = System.getProperty("java.class.path");
		ServerParameters stdioParams = ServerParameters.builder(javaExecutable())
			.args("-cp", classpath, FailingStdioServer.class.getName())
			.build();
		StdioClientTransport transport = new StdioClientTransport(stdioParams, JSON_MAPPER);
		McpSyncClient client = McpClient.sync(transport)
			.requestTimeout(requestTimeout)
			.initializationTimeout(Duration.ofSeconds(5))
			.build();

		Throwable failure;
		long elapsedMillis;
		try {
			long startNanos = System.nanoTime();
			failure = catchThrowable(client::initialize);
			elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
		}
		finally {
			client.closeGracefully();
		}

		assertThat(failure).isNotNull();
		String stackTrace = stackTraceOf(failure);
		assertThat(elapsedMillis).isLessThan(requestTimeout.toMillis());
		assertThat(stackTrace).contains("MCP server process exited", "with code 127")
			.doesNotContain("TimeoutException");
	}

	private String javaExecutable() {
		String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
		return Path.of(System.getProperty("java.home"), "bin", executable).toString();
	}

	private String stackTraceOf(Throwable failure) {
		StringWriter writer = new StringWriter();
		failure.printStackTrace(new PrintWriter(writer));
		return writer.toString();
	}

}
