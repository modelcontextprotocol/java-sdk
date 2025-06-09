package io.modelcontextprotocol.spec;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class DefaultMcpTransportStream<CONNECTION> implements McpTransportStream<CONNECTION> {

	private static final Logger logger = LoggerFactory.getLogger(DefaultMcpTransportStream.class);

	private static final AtomicLong counter = new AtomicLong();

	private final AtomicReference<String> lastId = new AtomicReference<>();

	// Used only for internal accounting
	private final long streamId;

	private final boolean resumable;

	private final Function<McpTransportStream<CONNECTION>, Publisher<CONNECTION>> reconnect;

	public DefaultMcpTransportStream(boolean resumable,
			Function<McpTransportStream<CONNECTION>, Publisher<CONNECTION>> reconnect) {
		this.reconnect = reconnect;
		this.streamId = counter.getAndIncrement();
		this.resumable = resumable;
	}

	@Override
	public Optional<String> lastId() {
		return Optional.ofNullable(this.lastId.get());
	}

	@Override
	public long streamId() {
		return this.streamId;
	}

	@Override
	public Publisher<McpSchema.JSONRPCMessage> consumeSseStream(
			Publisher<Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>>> eventStream) {
		return Flux.deferContextual(ctx -> Flux.from(eventStream).doOnError(e -> {
			if (resumable && !(e instanceof McpTransportSessionNotFoundException)) {
				Mono.from(reconnect.apply(this)).contextWrite(ctx).subscribe();
			}
		}).doOnNext(idAndMessage -> idAndMessage.getT1().ifPresent(id -> {
			String previousId = this.lastId.getAndSet(id);
			logger.debug("Updating last id {} -> {} for stream {}", previousId, id, this.streamId);
		})).flatMapIterable(Tuple2::getT2));
	}

}
