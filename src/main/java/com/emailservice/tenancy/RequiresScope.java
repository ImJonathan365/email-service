package com.emailservice.tenancy;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The scope a /v1 handler requires (FR-33, docs/07 §2). Mandatory: a /v1 handler without it is
 * refused at runtime, so forgetting it fails closed.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresScope {

	Scope value();

}
