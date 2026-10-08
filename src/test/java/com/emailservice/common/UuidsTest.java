package com.emailservice.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Message ids are UUIDv7 (docs/06 §3.4): version 7, RFC variant, ordered by creation time. */
class UuidsTest {

	@Test
	void hasVersionSevenAndTheRfcVariant() {
		UUID id = Uuids.v7();
		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
	}

	@Test
	void embedsTheTimestampSoIdsSortByCreationTime() {
		UUID earlier = Uuids.v7(1_789_000_000_000L);
		UUID later = Uuids.v7(1_789_000_000_001L);
		assertThat(earlier.toString()).isLessThan(later.toString());
		assertThat(earlier.getMostSignificantBits() >>> 16).isEqualTo(1_789_000_000_000L);
	}

	@Test
	void isUniqueWithinTheSameMillisecond() {
		Set<UUID> ids = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			ids.add(Uuids.v7(1_789_000_000_000L));
		}
		assertThat(ids).hasSize(10_000);
	}

}
