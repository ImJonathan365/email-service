package com.emailservice.tenancy.admin;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.emailservice.audit.AuditLog;
import com.emailservice.common.api.ProblemWriter;
import com.emailservice.common.api.RequestIdFilter;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnApiRole;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.common.web.ClientIpResolver;

@ConditionalOnApiRole
@Configuration(proxyBeanMethods = false)
class AdminConfig {

	@Bean
	FilterRegistrationBean<AdminAuthenticationFilter> adminAuthenticationFilter(AppProperties properties,
			ClientIpResolver clientIpResolver, ProblemWriter problems, AuditLog auditLog,
			@SystemDataSource JdbcClient systemJdbc) {
		var filter = new AdminAuthenticationFilter(new AdminCredentials(properties.admin().apiKeys()),
				clientIpResolver, problems, auditLog, systemJdbc);
		var registration = new FilterRegistrationBean<>(filter);
		registration.addUrlPatterns("/admin/v1/*");
		registration.setOrder(RequestIdFilter.ORDER + 10);
		return registration;
	}

}
