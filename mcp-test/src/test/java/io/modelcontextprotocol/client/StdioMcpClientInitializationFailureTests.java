/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.modelcontextprotocol.client.transport.McpStdioServerProcessExitException;
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
		Throwable retryFailure;
		long elapsedMillis;
		long retryElapsedMillis;
		try {
			long startNanos = System.nanoTime();
			failure = catchThrowable(client::initialize);
			elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

			long retryStartNanos = System.nanoTime();
			retryFailure = catchThrowable(client::initialize);
			retryElapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - retryStartNanos);
		}
		finally {
			client.closeGracefully();
		}

		assertThat(failure).isNotNull();
		assertThat(elapsedMillis).isLessThan(requestTimeout.toMillis());
		McpStdioServerProcessExitException processExit = findCause(failure, McpStdioServerProcessExitException.class);
		assertThat(processExit).isNotNull();
		assertThat(processExit.getExitCode()).isEqualTo(127);
		assertThat(processExit.getCommand()).isEqualTo(javaExecutable());

		assertThat(retryFailure).isNotNull();
		assertThat(retryElapsedMillis).isLessThan(requestTimeout.toMillis());
		McpStdioServerProcessExitException retryProcessExit = findCause(retryFailure,
				McpStdioServerProcessExitException.class);
		assertThat(retryProcessExit).isSameAs(processExit);
	}

	@Test
	void gracefulCloseShouldNotReportUnexpectedProcessExit() {
		String classpath = System.getProperty("java.class.path");
		ServerParameters stdioParams = ServerParameters.builder(javaExecutable())
			.args("-cp", classpath, WaitingStdioServer.class.getName())
			.build();
		StdioClientTransport transport = new StdioClientTransport(stdioParams, JSON_MAPPER);
		AtomicReference<Throwable> transportFailure = new AtomicReference<>();
		transport.setExceptionHandler(transportFailure::set);

		transport.connect(Function.identity()).block(Duration.ofSeconds(3));
		transport.closeGracefully().block(Duration.ofSeconds(5));

		assertThat(transportFailure).hasValue(null);
	}

	private String javaExecutable() {
		String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
		return Path.of(System.getProperty("java.home"), "bin", executable).toString();
	}

	private <T extends Throwable> T findCause(Throwable failure, Class<T> type) {
		Throwable current = failure;
		while (current != null) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause();
		}
		return null;
	}

}
