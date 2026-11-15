package io.modelcontextprotocol.spec;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMcpTransportSessionTests {

	@Test
	void closeGracefullyDisposesOpenConnectionsEvenWhenOnCloseFails() {
		var disposed = new AtomicBoolean();
		Disposable disposable = () -> disposed.set(true);
		var session = new DefaultMcpTransportSession(id -> Mono.error(new RuntimeException("boom")));
		session.addConnection(disposable);

		StepVerifier.create(session.closeGracefully()).expectErrorMessage("boom").verify();

		assertThat(disposed.get()).isTrue();
	}

}
