package com.emailservice.sending;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** Body of POST /v1/emails (docs/07 §3). Unknown fields are ignored, except the two rejected below. */
public record SendEmailRequest(
		@Schema(example = "password-reset") @NotBlank @Size(max = 64) String templateKey,
		Integer templateVersion,
		@Schema(example = "es-CR") String locale,
		@NotNull @Valid Recipient to,
		@Size(max = 5) List<String> cc,
		@Size(max = 5) List<String> bcc,
		@Schema(description = "Sender address; its domain must be in the tenant's allowedFromDomains (AC-09.2)")
		String from,
		@Schema(example = "Pulpería La Esquina vía Colmena") String fromName,
		String replyTo,
		@Schema(type = "object") @NotNull JsonNode variables,
		@Size(max = 10) List<String> tags,
		@Schema(type = "object") JsonNode metadata,
		@Schema(hidden = true) JsonNode sendAt,
		@Schema(hidden = true) JsonNode attachments) {

	public record Recipient(@Schema(example = "ana@example.com") @NotBlank String email,
			@Schema(example = "Ana Pérez") @Size(max = 200) String name) {
	}

	@Override
	public String toString() {
		return "SendEmailRequest[templateKey=" + templateKey + "]";
	}

}
