package com.emailservice.common.api;

import java.util.List;

/** List envelope of docs/07 §1; {@code nextCursor} is null when there is nothing more. */
public record ListResponse<T>(List<T> data, String nextCursor) {

	public static <T> ListResponse<T> of(List<T> data) {
		return new ListResponse<>(data, null);
	}

}
