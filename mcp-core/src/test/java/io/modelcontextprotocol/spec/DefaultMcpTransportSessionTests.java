package io.modelcontextprotocol.spec;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.springframework.util.ReflectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link DefaultMcpTransportSession}.
 *
 * @author Phani Pemmaraju
 */
class DefaultMcpTransportSessionTests {

	/** Minimal Disposable to flag that dispose() was called. */
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

	@Test
	void closeGracefully_disposes_when_onClose_throws() {
		@SuppressWarnings("unchecked")
		Function<String, Publisher<Void>> onClose = Mockito.mock(Function.class);
		Mockito.when(onClose.apply(Mockito.any())).thenReturn(Mono.error(new RuntimeException("runtime-exception")));

		// construct session with required ctor
		var session = new DefaultMcpTransportSession(onClose);

		// seed session id
		setField(session, "sessionId", new AtomicReference<>("sessionId-123"));

		// get the existing final composite and add a child flag-disposable
		Disposable.Composite composite = (Disposable.Composite) getField(session, "openConnections");
		FlagDisposable flag = new FlagDisposable();
		composite.add(flag);

		// act + assert: original onClose error is propagated
		assertThatThrownBy(() -> session.closeGracefully().block()).isInstanceOf(RuntimeException.class)
			.hasMessageContaining("runtime-exception");

		// and the child disposable was disposed => proves composite.dispose() executed
		assertThat(flag.isDisposed()).isTrue();
	}

	@Test
	void closeGracefully_propagates_onClose_error_and_disposes_children() {
		// onClose fails again
		@SuppressWarnings("unchecked")
		Function<String, Publisher<Void>> onClose = Mockito.mock(Function.class);
		Mockito.when(onClose.apply(Mockito.any())).thenReturn(Mono.error(new RuntimeException("runtime-exception")));

		var session = new DefaultMcpTransportSession(onClose);
		setField(session, "sessionId", new AtomicReference<>("sessionId-xyz"));

		Disposable.Composite composite = (Disposable.Composite) getField(session, "openConnections");
		FlagDisposable a = new FlagDisposable();
		FlagDisposable b = new FlagDisposable();
		composite.add(a);
		composite.add(b);

		Throwable thrown = Assertions.catchThrowable(() -> session.closeGracefully().block());

		// primary error is from onClose
		assertThat(thrown).isInstanceOf(RuntimeException.class).hasMessageContaining("runtime-exception");

		// both children disposed
		assertThat(a.isDisposed()).isTrue();
		assertThat(b.isDisposed()).isTrue();
	}

	private static void setField(Object target, String fieldName, Object value) {
		Field f = ReflectionUtils.findField(target.getClass(), fieldName);
		if (f == null)
			throw new IllegalArgumentException("No such field: " + fieldName);
		ReflectionUtils.makeAccessible(f);
		ReflectionUtils.setField(f, target, value);
	}

	private static Object getField(Object target, String fieldName) {
		Field f = ReflectionUtils.findField(target.getClass(), fieldName);
		if (f == null)
			throw new IllegalArgumentException("No such field: " + fieldName);
		ReflectionUtils.makeAccessible(f);
		return ReflectionUtils.getField(f, target);
	}

}
