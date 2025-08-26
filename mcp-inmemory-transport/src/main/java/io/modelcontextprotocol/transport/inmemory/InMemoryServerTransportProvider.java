package io.modelcontextprotocol.transport.inmemory;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerSession;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

public class InMemoryServerTransportProvider implements McpServerTransportProvider {

	private final InMemoryServerTransport serverTransport;
	private Disposable disposable;

	public InMemoryServerTransportProvider( InMemoryTransport transport ) {
		serverTransport = new InMemoryServerTransport(transport);
	}

	@Override
	public void setSessionFactory(McpServerSession.Factory sessionFactory) {

		var session = sessionFactory.create(serverTransport);
		disposable = serverTransport.serverSink().asFlux().subscribe(message -> {
			session.handle(message).subscribe();
		});
	}

	@Override
	public Mono<Void> closeGracefully() {
		if( disposable!=null && !disposable.isDisposed() ) {
			disposable.dispose();
		}
		return Mono.empty();
	}

	@Override
	public Mono<Void> notifyClients(String method, Object params) {
		// Not implemented for in-memory transport
		return Mono.empty();
	}

}
