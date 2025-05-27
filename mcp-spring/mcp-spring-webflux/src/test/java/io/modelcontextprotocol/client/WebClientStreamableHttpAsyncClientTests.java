package io.modelcontextprotocol.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

@Timeout(15)
public class WebClientStreamableHttpAsyncClientTests extends AbstractMcpAsyncClientTests {

    static String host = "http://localhost:3001";

    // Uses the https://github.com/tzolov/mcp-everything-server-docker-image
    @SuppressWarnings("resource")
    GenericContainer<?> container = new GenericContainer<>("docker.io/tzolov/mcp-everything-server-streamable:v2")
            .withLogConsumer(outputFrame -> System.out.println(outputFrame.getUtf8String()))
            .withExposedPorts(3001)
            .waitingFor(Wait.forHttp("/").forStatusCode(404));

    @Override
    protected McpClientTransport createMcpTransport() {
        return new WebClientStreamableHttpTransport(new ObjectMapper(), WebClient.builder(), "/mcp", true, false);
    }

    @Override
    protected void onStart() {
        container.start();
        int port = container.getMappedPort(3001);
        host = "http://" + container.getHost() + ":" + port;
    }

    @Override
    public void onClose() {
        container.stop();
    }
}
