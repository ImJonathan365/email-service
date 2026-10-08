package com.emailservice.common.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * What each APP_ROLE runs (docs/05 §1): the HTTP API (/v1, /webhooks, /admin/v1) only with api or
 * all, the queue worker only with worker or all. Actuator is always on, so every role can be
 * probed by the orchestrator.
 */
public final class RoleConditions {

	private RoleConditions() {
	}

	static AppRole role(ConditionContext context) {
		return AppRole.parse(context.getEnvironment().getProperty("app.role", "all"));
	}

	@Target({ ElementType.TYPE, ElementType.METHOD })
	@Retention(RetentionPolicy.RUNTIME)
	@Conditional(ApiRole.class)
	public @interface ConditionalOnApiRole {

	}

	@Target({ ElementType.TYPE, ElementType.METHOD })
	@Retention(RetentionPolicy.RUNTIME)
	@Conditional(WorkerRole.class)
	public @interface ConditionalOnWorkerRole {

	}

	static final class ApiRole implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return Set.of(AppRole.API, AppRole.ALL).contains(role(context));
		}

	}

	static final class WorkerRole implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return Set.of(AppRole.WORKER, AppRole.ALL).contains(role(context));
		}

	}

}
