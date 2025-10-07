/*
 * Copyright 2024-2024 the original author or authors.
 */
package io.modelcontextprotocol.server.transport;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * HTTP Response.
 *
 * @param statusCode HTTP Status code
 * @param statusReason HTTP Status Reason
 * @param body response Body
 */
public record HttpJsonRpcResponse(int statusCode, String statusReason, McpSchema.JSONRPCResponse body) {

	public HttpJsonRpcResponse(int statusCode, String statusReason, McpSchema.JSONRPCResponse body) {
		this.statusCode = statusCode;
		this.statusReason = statusReason;
		this.body = body;
	}

	public HttpJsonRpcResponse(int status, McpSchema.JSONRPCResponse rsp) {
		this(status, reason(status), rsp);
	}

	static String reason(int statusCode) {
		return switch (statusCode) {
			case 100 -> "Continue";
			case 101 -> "Switching Protocols";
			case 102 -> "Processing";
			case 103 -> "Early Hints";
			case 200 -> "Ok";
			case 201 -> "Created";
			case 202 -> "Accepted";
			case 203 -> "Non-Authoritative Information";
			case 204 -> "No Content";
			case 205 -> "Reset Content";
			case 206 -> "Partial Content";
			case 207 -> "Multi Status";
			case 208 -> "Already imported";
			case 226 -> "IM Used";
			case 300 -> "Multiple Choices";
			case 301 -> "Moved Permanently";
			case 302 -> "Found";
			case 303 -> "See Other";
			case 304 -> "Not Modified";
			case 305 -> "Use Proxy";
			case 306 -> "Switch Proxy";
			case 307 -> "Temporary Redirect";
			case 308 -> "Permanent Redirect";
			case 400 -> "Bad Request";
			case 401 -> "Unauthorized";
			case 402 -> "Payment Required";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 405 -> "Method Not Allowed";
			case 406 -> "Not Acceptable";
			case 407 -> "Proxy Authentication Required";
			case 408 -> "Request Timeout";
			case 409 -> "Conflict";
			case 410 -> "Gone";
			case 411 -> "Length Required";
			case 412 -> "Precondition Failed";
			case 413 -> "Request Entity Too Large";
			case 414 -> "Request-URI Too Long";
			case 415 -> "Unsupported Media Type";
			case 416 -> "Requested Range Not Satisfiable";
			case 417 -> "Expectation Failed";
			case 418 -> "I am a teapot";
			case 420 -> "Enhance your calm";
			case 421 -> "Misdirected Request";
			case 422 -> "Unprocessable Entity";
			case 423 -> "Locked";
			case 424 -> "Failed Dependency";
			case 425 -> "Too Early";
			case 426 -> "Upgrade Required";
			case 428 -> "Precondition Required";
			case 429 -> "Too Many Requests";
			case 431 -> "Request Header Fields Too Large";
			case 444 -> "No Response";
			case 450 -> "Blocked by Windows Parental Controls";
			case 451 -> "Unavailable For Legal Reasons";
			case 494 -> "Request Header Too Large";
			case 500 -> "Internal Server Error";
			case 501 -> "Not Implemented";
			case 502 -> "Bad Gateway";
			case 503 -> "Service Unavailable";
			case 504 -> "Gateway Timeout";
			case 505 -> "HTTP Version Not Supported";
			case 506 -> "Variant Also Negotiates";
			case 507 -> "Insufficient Storage";
			case 508 -> "Loop Detected";
			case 509 -> "Bandwidth Limit Exceeded";
			case 510 -> "Not Extended";
			case 511 -> "Network Authentication Required";
			case 522 -> "Connection Timed Out";
			default -> null;
		};
	}
}
