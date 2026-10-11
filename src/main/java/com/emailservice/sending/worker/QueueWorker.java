package com.emailservice.sending.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.provider.EmailSender;

/**
 * The worker loop (FR-12): claims only as many messages as it has free slots (AC-12.1), sends each
 * on a virtual thread and sweeps expired locks every minute. Runs with APP_ROLE=worker|all.
 */
@Component
@ConditionalOnWorkerRole
class QueueWorker implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(QueueWorker.class);

	static final Duration SWEEP_INTERVAL = Duration.ofSeconds(60);

	private final QueueRepository queue;

	private final MessageProcessor processor;

	private final StuckMessageSweeper sweeper;

	private final AppProperties.Worker settings;

	private final CircuitBreaker breaker;

	private final EmailSender sender;

	private final boolean autostart;

	private final String instanceId = instanceId();

	private final Semaphore slots;

	private ExecutorService executor;

	private Thread loop;

	private volatile boolean running;

	QueueWorker(QueueRepository queue, MessageProcessor processor, StuckMessageSweeper sweeper, CircuitBreaker breaker,
			EmailSender sender, AppProperties properties, @Value("${app.worker.autostart:true}") boolean autostart) {
		this.queue = queue;
		this.processor = processor;
		this.sweeper = sweeper;
		this.breaker = breaker;
		this.sender = sender;
		this.settings = properties.worker();
		this.autostart = autostart;
		this.slots = new Semaphore(settings.concurrency());
	}

	@Override
	public void start() {
		if (!sender.supportsIdempotency()) {
			log.warn("Provider '{}' does not deduplicate by idempotency key: a lost response can become a "
					+ "duplicate email (ADR-0010). Acceptable for local development only", sender.name());
		}
		running = true;
		executor = Executors.newVirtualThreadPerTaskExecutor();
		if (autostart) {
			loop = Thread.ofVirtual().name("queue-worker").start(this::run);
			log.info("Queue worker started with concurrency {}", settings.concurrency());
		}
	}

	@Override
	public void stop() {
		running = false;
		if (loop != null) {
			loop.interrupt();
		}
		executor.shutdown();
		try {
			// In-flight sends get their lock time to finish; anything left is reclaimed later.
			executor.awaitTermination(settings.lockTimeoutSeconds(), TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private void run() {
		long nextSweep = 0;
		while (running) {
			try {
				if (System.nanoTime() - nextSweep >= 0) {
					sweeper.reclaim();
					nextSweep = System.nanoTime() + SWEEP_INTERVAL.toNanos();
				}
				int free = slots.drainPermits();
				// While the circuit breaker is open nothing is claimed, so nothing fails (AC-13.9).
				int permitted = breaker.permittedClaims(free);
				List<QueueRepository.Claimed> claimed = permitted == 0 ? List.of()
						: queue.claim(permitted, settings.lockTimeoutSeconds(), instanceId);
				slots.release(free - claimed.size());
				for (QueueRepository.Claimed message : claimed) {
					executor.submit(() -> {
						try {
							processor.process(message);
						}
						finally {
							slots.release();
						}
					});
				}
				if (claimed.size() < free || free == 0) {
					Thread.sleep(settings.pollIntervalMs());
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return;
			}
			catch (RuntimeException ex) {
				log.error("Queue worker iteration failed; retrying after the poll interval", ex);
				try {
					Thread.sleep(settings.pollIntervalMs());
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}
	}

	/** One synchronous round: claim up to the concurrency and process here. Tests drive the queue with it. */
	List<QueueRepository.Claimed> runOnce() {
		int permitted = breaker.permittedClaims(settings.concurrency());
		List<QueueRepository.Claimed> claimed = permitted == 0 ? List.of()
				: queue.claim(permitted, settings.lockTimeoutSeconds(), instanceId);
		claimed.forEach(processor::process);
		return claimed;
	}

	private static String instanceId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "unknown";
		}
		return host + "-" + ProcessHandle.current().pid();
	}

}
