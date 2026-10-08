package com.emailservice.common;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7 (RFC 9562) for message ids: time-ordered, so new rows land at the end of the primary key
 * index, with 74 random bits from a CSPRNG so ids stay unguessable (NFR-08).
 */
public final class Uuids {

	private static final SecureRandom RANDOM = new SecureRandom();

	private Uuids() {
	}

	public static UUID v7() {
		return v7(System.currentTimeMillis());
	}

	static UUID v7(long epochMillis) {
		long randA = RANDOM.nextInt(1 << 12);
		long randB = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL;
		long msb = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | randA;
		long lsb = 0x8000_0000_0000_0000L | randB;
		return new UUID(msb, lsb);
	}

}
