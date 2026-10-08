package com.emailservice.sending.worker;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.RoleConditions.ConditionalOnWorkerRole;
import com.emailservice.templates.schema.SensitiveVariables;
import com.emailservice.templates.schema.VariablesSchema;

import tools.jackson.databind.json.JsonMapper;

/** reclaimStuckMessages (docs/05 §5, AC-11.3), in one system transaction holding the advisory lock. */
@Component
@ConditionalOnWorkerRole
class StuckMessageSweeper {

	private final QueueRepository queue;

	private final JsonMapper jsonMapper;

	private final int maxAttempts;

	StuckMessageSweeper(QueueRepository queue, JsonMapper jsonMapper, AppProperties properties) {
		this.queue = queue;
		this.jsonMapper = jsonMapper;
		this.maxAttempts = properties.worker().maxAttempts();
	}

	@Transactional("systemTransactionManager")
	void reclaim() {
		List<UUID> failed = queue.reclaimExpired(maxAttempts);
		// Exhausted messages are now terminal: their x-sensitive variables go too (AC-23.5).
		for (UUID id : failed) {
			QueueRepository.StoredVariables stored = queue.variables(id);
			VariablesSchema schema = VariablesSchema.parse(jsonMapper.readTree(stored.variablesSchema()), "variablesSchema");
			queue.replaceVariables(id, jsonMapper
				.writeValueAsString(SensitiveVariables.strip(schema, jsonMapper.readTree(stored.variables()))));
		}
	}

}
