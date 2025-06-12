/*
* Copyright 2024 - 2024 the original author or authors.
*/
package io.modelcontextprotocol.server.transport;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BlockingInputStream extends InputStream {

	private final BlockingQueue<Integer> queue = new LinkedBlockingQueue<>();

	private volatile boolean completed = false;

	private volatile boolean closed = false;

	@Override
	public int read() throws IOException {
		if (closed) {
			throw new IOException("Stream is closed");
		}

		try {
			Integer value = queue.poll();
			if (value == null) {
				if (completed) {
					return -1;
				}
				value = queue.take(); // Blocks until data is available
				if (value == null && completed) {
					return -1;
				}
			}
			return value;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Read interrupted", e);
		}
	}

	public void write(int b) {
		if (!closed && !completed) {
			queue.offer(b);
		}
	}

	public void write(byte[] data) {
		if (!closed && !completed) {
			for (byte b : data) {
				queue.offer((int) b & 0xFF);
			}
		}
	}

	public void complete() {
		this.completed = true;
	}

	@Override
	public void close() {
		this.closed = true;
		this.completed = true;
		this.queue.clear();
	}

}