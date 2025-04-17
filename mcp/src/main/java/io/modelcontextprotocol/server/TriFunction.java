package io.modelcontextprotocol.server;

import java.util.Objects;
import java.util.function.Function;

/**
 * @author taobaorun
 */
@FunctionalInterface
public interface TriFunction<T, U, V, R> {

	R apply(T t, U u, V v);

	default <K> TriFunction<T, U, V, K> andThen(Function<? super R, ? extends K> after) {
		Objects.requireNonNull(after);
		return (T t, U u, V v) -> after.apply(apply(t, u, v));
	}

}
