package com.emailservice.sending.worker;

import java.util.concurrent.TimeUnit;

/**
 * WORKER_PROVIDER_RPS per worker instance (AC-12.6, NFR-21): calls are spaced 1/RPS apart with no
 * burst, so no one-second window ever holds more than RPS calls. Instances do not coordinate; the
 * sum across instances is kept under the account limit by configuration.
 */
final class ProviderRateLimiter {

	/** Time source and sleep, replaceable in tests. */
	interface Ticker {

		long nanoTime();

		void sleepNanos(long nanos) throws InterruptedException;

		Ticker SYSTEM = new Ticker() {

			@Override
			public long nanoTime() {
				return System.nanoTime();
			}

			@Override
			public void sleepNanos(long nanos) throws InterruptedException {
				TimeUnit.NANOSECONDS.sleep(nanos);
			}
		};

	}

	private final long intervalNanos;

	private final Ticker ticker;

	private long nextSlot;

	private boolean started;

	ProviderRateLimiter(int permitsPerSecond, Ticker ticker) {
		this.intervalNanos = TimeUnit.SECONDS.toNanos(1) / permitsPerSecond;
		this.ticker = ticker;
	}

	/** Blocks until this caller's slot; slots are handed out in call order. */
	void acquire() throws InterruptedException {
		long slot;
		synchronized (this) {
			long now = ticker.nanoTime();
			slot = !started || now - nextSlot > 0 ? now : nextSlot;
			started = true;
			nextSlot = slot + intervalNanos;
		}
		long wait = slot - ticker.nanoTime();
		if (wait > 0) {
			ticker.sleepNanos(wait);
		}
	}

}
