package com.emailservice.tenancy.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.emailservice.common.api.ProblemWriter;
import com.emailservice.common.api.RequestIdFilter;
import com.emailservice.common.web.ClientIpResolver;

@Configuration(proxyBeanMethods = false)
class AuthConfig implements WebMvcConfigurer {

	@Bean
	FilterRegistrationBean<ApiKeyAuthenticationFilter> apiKeyAuthenticationFilter(ApiKeyAuthenticator authenticator,
			ClientIpResolver clientIpResolver, ProblemWriter problems) {
		var registration = new FilterRegistrationBean<>(
				new ApiKeyAuthenticationFilter(authenticator, clientIpResolver, problems));
		registration.addUrlPatterns("/v1/*");
		registration.setOrder(RequestIdFilter.ORDER + 10);
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ScopeInterceptor()).addPathPatterns("/v1/**");
	}

}
