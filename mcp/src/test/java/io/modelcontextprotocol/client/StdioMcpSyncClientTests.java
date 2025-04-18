/*
 * Copyright 2024-2024 the original author or authors.
 */

package io.modelcontextprotocol.client;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransportProvider;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpClientTransportProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Christian Tzolov
 * @author Dariusz Jędrzejczyk
 */
@Timeout(15) // Giving extra time beyond the client timeout
class StdioMcpSyncClientTests extends AbstractMcpSyncClientTests {

	@Override
	protected McpClientTransportProvider createMcpClientTransportProvider() {
		ServerParameters stdioParams;
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			stdioParams = ServerParameters.builder("cmd.exe")
				.args("/c", "npx.cmd", "-y", "@modelcontextprotocol/server-everything", "dir")
				.build();
		}
		else {
			stdioParams = ServerParameters.builder("npx")
				.args("-y", "@modelcontextprotocol/server-everything", "dir")
				.build();
		}
		return new StdioClientTransportProvider(stdioParams);
	}

	@Test
	void customErrorHandlerShouldReceiveErrors() throws InterruptedException {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> receivedError = new AtomicReference<>();

		McpClientTransportProvider transportProvider = createMcpClientTransportProvider();
		transportProvider.setSessionFactory(
				(transport) -> new McpClientSession(Duration.ofSeconds(5), transport, Map.of(), Map.of()));
		McpClientTransport transport = transportProvider.getSession().getTransport();
		StepVerifier.create(transport.connect(msg -> msg)).verifyComplete();

		((StdioClientTransportProvider) transportProvider).setStdErrorHandler(error -> {
			receivedError.set(error);
			latch.countDown();
		});

		String errorMessage = "Test error";
		((StdioClientTransportProvider) transportProvider).getErrorSink()
			.emitNext(errorMessage, Sinks.EmitFailureHandler.FAIL_FAST);

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

		assertThat(receivedError.get()).isNotNull().isEqualTo(errorMessage);

		StepVerifier.create(transport.closeGracefully()).expectComplete().verify(Duration.ofSeconds(5));
	}

	protected Duration getInitializationTimeout() {
		return Duration.ofSeconds(6);
	}

}
