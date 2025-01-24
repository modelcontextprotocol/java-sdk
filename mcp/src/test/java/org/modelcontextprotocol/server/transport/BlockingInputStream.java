/*
* Copyright 2024 - 2024 the original author or authors.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* https://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.modelcontextprotocol.server.transport;

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