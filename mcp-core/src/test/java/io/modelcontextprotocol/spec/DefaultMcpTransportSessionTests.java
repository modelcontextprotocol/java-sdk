package io.modelcontextprotocol.spec;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultMcpTransportSession}.
 *
 * @author Phani Pemmaraju
 */
class DefaultMcpTransportSessionTests {

	@Test
	void closeGracefully_disposes_when_onClose_throws() {
		@SuppressWarnings("unchecked")
		Function<String, Publisher<Void>> onClose = Mockito.mock(Function.class);
		Mockito.when(onClose.apply(Mockito.any())).thenReturn(Mono.error(new RuntimeException("runtime-exception")));

		var session = new DefaultMcpTransportSession(onClose);
		session.markInitialized("sessionId-123");

		FlagDisposable flag = new FlagDisposable();
		session.addConnection(flag);

		assertThatThrownBy(() -> session.closeGracefully().block()).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("runtime-exception");

		assertThat(flag.isDisposed()).isTrue();
	}

	@Test
	void closeGracefully_propagates_onClose_error_and_disposes_children() {
		@SuppressWarnings("unchecked")
		Function<String, Publisher<Void>> onClose = Mockito.mock(Function.class);
		Mockito.when(onClose.apply(Mockito.any())).thenReturn(Mono.error(new RuntimeException("runtime-exception")));

		var session = new DefaultMcpTransportSession(onClose);
		session.markInitialized("sessionId-xyz");

		FlagDisposable a = new FlagDisposable();
		FlagDisposable b = new FlagDisposable();
		session.addConnection(a);
		session.addConnection(b);

		Throwable thrown = Assertions.catchThrowable(() -> session.closeGracefully().block());

		assertThat(thrown).isInstanceOf(RuntimeException.class).hasMessageContaining("runtime-exception");
		assertThat(a.isDisposed()).isTrue();
		assertThat(b.isDisposed()).isTrue();
	}

	/**
	 * Minimal Disposable to flag that dispose() was called.
	 */
	static final class FlagDisposable implements Disposable {

		final AtomicBoolean disposed = new AtomicBoolean(false);

		@Override
		public void dispose() {
			disposed.set(true);
		}

		@Override
		public boolean isDisposed() {
			return disposed.get();
		}

	}

}
