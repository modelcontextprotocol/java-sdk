/*
 * Copyright 2024-2026 the original author or authors.
 */

package io.modelcontextprotocol.client.transport;

import java.util.List;

final class HttpClientLeakTestSupport {

	private HttpClientLeakTestSupport() {
	}

	static int selectorManagerThreadCount() {
		return selectorManagerThreadNames().size();
	}

	static List<String> selectorManagerThreadNames() {
		return Thread.getAllStackTraces()
			.keySet()
			.stream()
			.map(Thread::getName)
			.filter(name -> name.contains("HttpClient") && name.contains("SelectorManager"))
			.sorted()
			.toList();
	}

	static int forceGcUntilStable() throws InterruptedException {
		int previousCount = Integer.MAX_VALUE;
		int stableIterations = 0;
		int currentCount = previousCount;

		for (int i = 0; i < 40; i++) {
			System.gc();
			System.runFinalization();
			Thread.sleep(250);

			currentCount = selectorManagerThreadCount();
			if (currentCount == previousCount) {
				stableIterations++;
				if (stableIterations >= 4) {
					break;
				}
			}
			else {
				stableIterations = 0;
				previousCount = currentCount;
			}
		}

		return currentCount;
	}

	static void pauseForSelectorStartup() throws InterruptedException {
		Thread.sleep(150);
	}

}
