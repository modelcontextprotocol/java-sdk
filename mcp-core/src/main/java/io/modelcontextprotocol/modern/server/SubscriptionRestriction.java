/*
 * Copyright 2026-2026 the original author or authors.
 */

package io.modelcontextprotocol.modern.server;

import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema.SubscriptionFilter;
import reactor.core.publisher.Mono;

/**
 * Narrows, per {@code subscriptions/listen} request, the notifications the server agrees
 * to honour, for example to keep a caller from watching resources that belong to another
 * principal. Registered through {@link McpServer.Builder#restrictSubscriptions}.
 * <p>
 * Change notifications are server-wide: a {@code notifications/resources/updated} for a
 * URI reaches every stream whose acknowledged filter contains that URI, whoever opened
 * it. Without a restriction, any caller may subscribe to any URI and learn when that
 * resource changes, though not its content.
 * <p>
 * Return the part of {@code honoured} this caller may receive, typically {@code honoured}
 * with some fields removed or some list elements left out. The restriction can only
 * narrow: the server keeps only the part of the returned filter that it honours, so it
 * cannot grant notifications the client didn't request or the server cannot deliver.
 * Anything returned beyond {@code honoured} is ignored and logged as a warning. An empty
 * {@code Mono} honours nothing.
 * <p>
 * The restriction runs once, when the stream opens, and the filter it leaves is
 * acknowledged to the client and applies for the whole life of the stream. A change in
 * what the caller may receive, such as a revoked permission, isn't applied to streams
 * that are already open: it takes effect on the caller's next
 * {@code subscriptions/listen}. When the server honours nothing of the request, the
 * restriction isn't called: the stream opens with an empty acknowledgement and delivers
 * nothing, so there is nothing to narrow or refuse.
 * <p>
 * To refuse the request altogether, return {@link Mono#error} or throw an
 * {@link McpException}; it is answered with its error, before any stream opens. The spec
 * defines no permission error code, so use for example
 * {@code new McpException(ErrorCodes.INVALID_REQUEST, "Not permitted")}, with the same
 * message whichever subscription is denied, so that the error does not tell the caller
 * which URI it may not access. Any other exception is answered as an internal error.
 * <p>
 * The restriction runs while the request is being handled, so it must not block the
 * calling thread. Run blocking work, such as a permission lookup, on a scheduler that
 * allows it, except where the request is already handled on a thread that may block:
 * <pre>{@code
 * (ctx, requested, honoured) -> {
 *     Mono<SubscriptionFilter> restricted = Mono.fromCallable(() -> {
 *         Principal principal = (Principal) ctx.transportContext().get("principal");
 *         List<String> allowed = honoured.resourceSubscriptionsOrEmpty()
 *             .stream()
 *             .filter(uri -> permissions.canWatch(principal, uri)) // blocking
 *             .toList();
 *         return honoured.with(SubscriptionFilter.RESOURCE_SUBSCRIPTIONS,
 *                 allowed.isEmpty() ? null : allowed);
 *     });
 *     return ctx.isBlocking() ? restricted : restricted.subscribeOn(Schedulers.boundedElastic());
 * }
 * }</pre>
 *
 * @author Daniel Garnier-Moiroux
 */
@FunctionalInterface
public interface SubscriptionRestriction {

	/**
	 * The subscriptions this caller may receive.
	 * @param ctx the {@code subscriptions/listen} request, including the transport
	 * context that identifies the caller
	 * @param requested the filter the client sent
	 * @param honoured the part of {@code requested} the server and its features agree to
	 * honour
	 * @return the subscriptions this caller may receive, of which the server keeps the
	 * part it honours
	 */
	Mono<SubscriptionFilter> restrict(McpRequestContext ctx, SubscriptionFilter requested, SubscriptionFilter honoured);

}
