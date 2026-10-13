package com.emailservice.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** FR-34 thresholds and minimums, on the pure decision. */
class AnomalyRulesTest {

	static final AnomalyDetector.Thresholds DEFAULTS = new AnomalyDetector.Thresholds(3, 50, 0.04, 0.001);

	static AnomalyDetector.Stats stats(long lastHour, long results24h, long bounced24h, long delivered7d,
			long complained7d, long complained24h) {
		return new AnomalyDetector.Stats(UUID.randomUUID(), "t", null, lastHour, results24h, bounced24h, delivered7d,
				complained7d, complained24h);
	}

	static Optional<String> reason(AnomalyDetector.Stats stats, long[] history) {
		return AnomalyDetector.decide(stats, DEFAULTS, history).map(AnomalyDetector.Decision::reason);
	}

	static long[] everyHour(long count) {
		long[] hours = new long[AnomalyDetector.HISTORY_HOURS];
		Arrays.fill(hours, count);
		return hours;
	}

	@Test
	void ac_34_1_volumeAboveThreeTimesTheHourlyP95Pauses() {
		assertThat(reason(stats(301, 0, 0, 0, 0, 0), everyHour(100))).contains("VOLUME_ANOMALY");
		assertThat(reason(stats(300, 0, 0, 0, 0, 0), everyHour(100))).isEmpty();
	}

	@Test
	void ac_34_5_neverBelowTheHourlyMinimum() {
		assertThat(reason(stats(50, 0, 0, 0, 0, 0), new long[0])).as("new tenant, no history").isEmpty();
		assertThat(reason(stats(51, 0, 0, 0, 0, 0), new long[0])).contains("VOLUME_ANOMALY");
	}

	@Test
	void ac_34_1_hoursWithoutMessagesCountAsZeroInTheP95() {
		// The top 5 % of 336 hours is 16.8 hours: 16 busy hours leave the p95 on a quiet one, 17 do not.
		long[] history = new long[16];
		Arrays.fill(history, 1_000);
		assertThat(AnomalyDetector.p95(history)).isZero();
		long[] busy = new long[17];
		Arrays.fill(busy, 1_000);
		assertThat(AnomalyDetector.p95(busy)).isEqualTo(1_000);
	}

	@Test
	void ac_34_2_bouncesNeedTwoHundredResults() {
		assertThat(reason(stats(0, 200, 9, 0, 0, 0), new long[0])).contains("BOUNCE_RATE");
		assertThat(reason(stats(0, 200, 8, 0, 0, 0), new long[0])).as("exactly 4 % does not pause").isEmpty();
		assertThat(reason(stats(0, 199, 100, 0, 0, 0), new long[0])).isEmpty();
	}

	@Test
	void ac_34_3_complaintsByRateWithVolumeOrThreeInADay() {
		assertThat(reason(stats(0, 0, 0, 1_000, 2, 0), new long[0])).contains("COMPLAINT_RATE");
		assertThat(reason(stats(0, 0, 0, 1_000, 1, 0), new long[0])).isEmpty();
		assertThat(reason(stats(0, 0, 0, 999, 50, 2), new long[0])).isEmpty();
		assertThat(reason(stats(0, 0, 0, 10, 3, 3), new long[0])).contains("COMPLAINT_RATE");
	}

	@Test
	void complaintsWinOverBouncesAndVolume() {
		assertThat(reason(stats(10_000, 1_000, 900, 0, 3, 3), new long[0])).contains("COMPLAINT_RATE");
	}

}
