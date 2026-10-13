package com.emailservice.common.scheduling;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;

/**
 * Runs every {@link PeriodicTask} of a worker instance, each on its own schedule. A failing round
 * is logged and the task runs again at its next interval. Tests drive the tasks directly
 * (app.worker.autostart=false).
 */
@Component
@ConditionalOnWorkerRole
class PeriodicTasks implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(PeriodicTasks.class);

	private final List<PeriodicTask> tasks;

	private final boolean autostart;

	private ScheduledExecutorService scheduler;

	PeriodicTasks(List<PeriodicTask> tasks, @Value("${app.worker.autostart:true}") boolean autostart) {
		this.tasks = tasks;
		this.autostart = autostart;
	}

	@Override
	public boolean isAutoStartup() {
		return autostart;
	}

	@Override
	public void start() {
		scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("periodic-", 0).factory());
		for (PeriodicTask task : tasks) {
			long millis = task.interval().toMillis();
			scheduler.scheduleWithFixedDelay(() -> runSafely(task), millis, millis, TimeUnit.MILLISECONDS);
		}
	}

	private static void runSafely(PeriodicTask task) {
		try {
			task.run();
		}
		catch (RuntimeException ex) {
			log.error("Periodic task {} failed; it runs again at its next interval", task.name(), ex);
		}
	}

	@Override
	public void stop() {
		if (scheduler != null) {
			scheduler.shutdownNow();
		}
	}

	@Override
	public boolean isRunning() {
		return scheduler != null && !scheduler.isShutdown();
	}

}
