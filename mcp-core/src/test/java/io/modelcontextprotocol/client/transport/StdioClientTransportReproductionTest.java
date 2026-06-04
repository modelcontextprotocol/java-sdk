package io.modelcontextprotocol.client.transport;

import java.io.IOException;
import java.time.Duration;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

public class StdioClientTransportReproductionTest {

	private static final Logger logger = LoggerFactory.getLogger(StdioClientTransportReproductionTest.class);

	@Test
	void testCloseGracefullyDoesNotHang() {
		ServerParameters params = ServerParameters.builder("cat").build();
		StdioClientTransport transport = new StdioClientTransport(params, new McpJsonMapper() {
			@Override
			public <T> T readValue(String content, Class<T> type) throws IOException {
				return null;
			}

			@Override
			public <T> T readValue(byte[] content, Class<T> type) throws IOException {
				return null;
			}

			@Override
			public <T> T readValue(String content, TypeRef<T> type) throws IOException {
				return null;
			}

			@Override
			public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
				return null;
			}

			@Override
			public <T> T convertValue(Object fromValue, Class<T> type) {
				return null;
			}

			@Override
			public <T> T convertValue(Object fromValue, TypeRef<T> type) {
				return null;
			}

			@Override
			public String writeValueAsString(Object value) throws IOException {
				return "{}";
			}

			@Override
			public byte[] writeValueAsBytes(Object value) throws IOException {
				return "{}".getBytes();
			}
		});

		transport.connect((message) -> message).block(Duration.ofMillis(5000));

		logger.info("Connected to 'cat' server");

		logger.info("Closing transport...");
		long start = System.currentTimeMillis();
		try {
			transport.closeGracefully().block(Duration.ofMillis(5000));
			long duration = System.currentTimeMillis() - start;
			logger.info("Transport closed in {} ms", duration);
			assertThat(duration).isLessThan(5000);
		}
		catch (Exception ex) {
			logger.error("Transport failed to close or timed out", ex);
			throw ex;
		}
	}

}
