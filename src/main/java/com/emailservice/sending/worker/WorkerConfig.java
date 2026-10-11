package com.emailservice.sending.worker;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** The worker's protection around the provider, shared by the loop and the processor. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWorkerRole
class WorkerConfig {

	@Bean
	CircuitBreaker circuitBreaker(AppProperties properties, Clock clock, MeterRegistry meters) {
		CircuitBreaker breaker = new CircuitBreaker(properties.provider().circuitBreakerFailures(),
				Duration.ofSeconds(properties.provider().circuitBreakerOpenSeconds()), clock);
		Gauge.builder("email.circuit.breaker.open", breaker, b -> b.state() == CircuitBreaker.State.CLOSED ? 0 : 1)
			.description("1 while the provider circuit breaker is open or probing (AC-13.9)")
			.register(meters);
		return breaker;
	}

	@Bean
	ProviderRateLimiter providerRateLimiter(AppProperties properties) {
		return new ProviderRateLimiter(properties.provider().workerProviderRps(), ProviderRateLimiter.Ticker.SYSTEM);
	}

}
