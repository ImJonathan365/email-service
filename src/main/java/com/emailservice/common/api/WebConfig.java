package com.emailservice.common.api;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.web.ClientIpResolver;

@Configuration(proxyBeanMethods = false)
class WebConfig {

	/** Filter order shared by the authentication filters, which must run after the request id. */
	static final int REQUEST_ID_ORDER = Ordered.HIGHEST_PRECEDENCE;

	@Bean
	FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
		FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
		registration.setOrder(REQUEST_ID_ORDER);
		return registration;
	}

	@Bean
	ClientIpResolver clientIpResolver(AppProperties properties) {
		return new ClientIpResolver(properties.http().trustedProxyRanges());
	}

}
