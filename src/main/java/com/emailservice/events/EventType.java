package com.emailservice.events;

/** email_event.type, mapped from the provider's event names (docs/02 §2.1). */
enum EventType {

	SENT, DELIVERED, DELIVERY_DELAYED, BOUNCED, COMPLAINED, OPENED, CLICKED, FAILED, SUPPRESSED, OTHER;

	static EventType fromResend(String providerType) {
		return switch (providerType) {
			case "email.sent" -> SENT;
			case "email.delivered" -> DELIVERED;
			case "email.delivery_delayed" -> DELIVERY_DELAYED;
			case "email.bounced" -> BOUNCED;
			case "email.complained" -> COMPLAINED;
			case "email.opened" -> OPENED;
			case "email.clicked" -> CLICKED;
			case "email.failed" -> FAILED;
			case "email.suppressed" -> SUPPRESSED;
			default -> OTHER;
		};
	}

}
