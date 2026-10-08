package com.emailservice.sending;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Where the per-tenant rate limit and daily quota (FR-21) plug in: called after every validation
 * and inside the transaction of the INSERT, so a rejected or failed request never consumes quota
 * and the counter commits or rolls back with the message (AC-21.5). Idempotent repeats return
 * earlier and never reach it (AC-08.7).
 */
@Component
class AcceptanceRateLimiter {

	// TODO(H7): increment rate_limit_counter (minute and day windows) and throw 429 RATE_LIMITED (FR-21).
	void consume(UUID tenantId) {
	}

}
