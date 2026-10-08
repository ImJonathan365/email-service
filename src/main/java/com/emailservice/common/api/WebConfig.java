package com.emailservice.common.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.web.ClientIpResolver;

@Configuration(proxyBeanMethods = false)
class WebConfig {

	@Bean
	FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(RequestIdFilter.ORDER);
		return registration;
	}

	/**
	 * Body size limits, checked before authentication so an oversized body is never read.
	 * MAX_REQUEST_BYTES is the send limit (AC-07.6); template versions carry up to 256 KB of HTML
	 * plus text and schema (AC-04.10), so they get their own, larger cap.
	 */
	static final long TEMPLATE_REQUEST_MAX_BYTES = 1024 * 1024;

	@Bean
	FilterRegistrationBean<RequestSizeFilter> sendRequestSizeFilter(AppProperties properties, ProblemWriter problems) {
		FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(
				new RequestSizeFilter(properties.sending().maxRequestBytes(), problems));
		registration.addUrlPatterns("/v1/emails");
		registration.setOrder(RequestIdFilter.ORDER + 5);
		return registration;
	}

	@Bean
	FilterRegistrationBean<RequestSizeFilter> templateRequestSizeFilter(ProblemWriter problems) {
		FilterRegistrationBean<RequestSizeFilter> registration = new FilterRegistrationBean<>(
				new RequestSizeFilter(TEMPLATE_REQUEST_MAX_BYTES, problems));
		registration.addUrlPatterns("/v1/templates/*");
		registration.setOrder(RequestIdFilter.ORDER + 5);
		return registration;
	}

	@Bean
	ClientIpResolver clientIpResolver(AppProperties properties) {
		return new ClientIpResolver(properties.http().trustedProxyRanges());
	}

}
