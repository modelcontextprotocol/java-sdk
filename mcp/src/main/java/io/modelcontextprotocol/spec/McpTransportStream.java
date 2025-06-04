package io.modelcontextprotocol.spec;

import org.reactivestreams.Publisher;
import reactor.util.function.Tuple2;

import java.util.Optional;

public interface McpTransportStream<CONNECTION> {

	Optional<String> lastId();

	long streamId();

	Publisher<McpSchema.JSONRPCMessage> consumeSseStream(
			Publisher<Tuple2<Optional<String>, Iterable<McpSchema.JSONRPCMessage>>> eventStream);

}
