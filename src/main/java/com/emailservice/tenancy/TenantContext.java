package com.emailservice.tenancy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant of the current unit of work. A scoped value cannot leak to another request on a
 * reused (virtual) thread, unlike a ThreadLocal that someone forgets to clear.
 */
public final class TenantContext {

	private static final ScopedValue<UUID> TENANT_ID = ScopedValue.newInstance();

	private TenantContext() {
	}

	public static void run(UUID tenantId, Runnable operation) {
		ScopedValue.where(TENANT_ID, Objects.requireNonNull(tenantId)).run(operation);
	}

	public static <R, X extends Throwable> R call(UUID tenantId, ScopedValue.CallableOp<? extends R, X> operation)
			throws X {
		return ScopedValue.where(TENANT_ID, Objects.requireNonNull(tenantId)).call(operation);
	}

	public static Optional<UUID> current() {
		return TENANT_ID.isBound() ? Optional.of(TENANT_ID.get()) : Optional.empty();
	}

}
