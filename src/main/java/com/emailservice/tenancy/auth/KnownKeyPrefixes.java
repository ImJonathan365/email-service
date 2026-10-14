package com.emailservice.tenancy.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.emailservice.common.persistence.SystemDataSource;

/**
 * The prefixes of the active API keys, held in memory per instance (docs/08 §4, owner 2026-10-12).
 * Prefixes are not secrets. The set only decides which requests an IP over the failure limit may
 * still send to the database: a known prefix always gets the full check there, so revocation
 * stays immediate. Refreshed at most every 60 s, and at once when this instance issues or revokes.
 */
@Component
public class KnownKeyPrefixes {

	private static final Logger log = LoggerFactory.getLogger(KnownKeyPrefixes.class);

	static final Duration REFRESH_EVERY = Duration.ofSeconds(60);

	private final JdbcClient jdbc;

	private final Clock clock;

	private final ReentrantLock refreshing = new ReentrantLock();

	private volatile Set<String> prefixes;

	private volatile Instant loadedAt = Instant.MIN;

	KnownKeyPrefixes(@SystemDataSource JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/**
	 * Whether a request with this prefix deserves a database lookup. Before the first successful
	 * load nothing is known, so everything does: in doubt, authenticate normally.
	 */
	boolean mightBeActive(String prefix) {
		refreshIfStale();
		Set<String> current = prefixes;
		return current == null || current.contains(prefix);
	}

	/** Issuing in this instance: usable here at once, elsewhere after their next refresh. */
	public void added(String prefix) {
		Set<String> current = prefixes;
		if (current != null) {
			Set<String> copy = new HashSet<>(current);
			copy.add(prefix);
			prefixes = Set.copyOf(copy);
		}
	}

	/** Revoking in this instance. Revocation itself does not depend on this: the database decides. */
	public void removed(String prefix) {
		Set<String> current = prefixes;
		if (current != null) {
			Set<String> copy = new HashSet<>(current);
			copy.remove(prefix);
			prefixes = Set.copyOf(copy);
		}
	}

	void refreshIfStale() {
		Duration age = Duration.between(loadedAt, clock.instant());
		// A clock that went backwards (an NTP step) also makes the set stale.
		boolean fresh = !age.isNegative() && age.compareTo(REFRESH_EVERY) < 0;
		if (fresh || !refreshing.tryLock()) {
			return;
		}
		try {
			prefixes = Set.copyOf(jdbc.sql("""
					SELECT key_prefix FROM api_key
					WHERE status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > now())
					""").query(String.class).list());
			loadedAt = clock.instant();
		}
		catch (DataAccessException ex) {
			// Keep the previous set; the next request tries again.
			log.warn("Could not refresh the known API key prefixes", ex);
		}
		finally {
			refreshing.unlock();
		}
	}

}
