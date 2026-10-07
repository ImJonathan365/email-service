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

	@Bean
	ClientIpResolver clientIpResolver(AppProperties properties) {
		return new ClientIpResolver(properties.http().trustedProxyRanges());
	}

}
