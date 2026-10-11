package com.emailservice.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.emailservice.sending.worker.RecordingEmailSender;

/**
 * Test doubles shared by every integration test, so they all run in one cached Spring context:
 * a recording provider that never sends anything, and a clock tests can move. Tests that need a
 * different behaviour program these beans and restore them, instead of declaring new beans.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SharedTestBeans {

	@Bean
	@Primary
	RecordingEmailSender recordingEmailSender() {
		return new RecordingEmailSender();
	}

	@Bean
	@Primary
	MutableClock mutableClock() {
		return MutableClock.followingSystemTime();
	}

}
