package com.emailservice.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Minimal HTTP client for integration tests against the running server. */
public final class TestHttp {

	private static final HttpClient CLIENT = HttpClient.newHttpClient();

	private final String baseUrl;

	public TestHttp(int port) {
		this.baseUrl = "http://localhost:" + port;
	}

	public Request get(String path) {
		return new Request("GET", path);
	}

	public Request post(String path) {
		return new Request("POST", path);
	}

	public Request patch(String path) {
		return new Request("PATCH", path);
	}

	public Request delete(String path) {
		return new Request("DELETE", path);
	}

	public final class Request {

		private final HttpRequest.Builder builder;

		private final String method;

		private HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();

		private Request(String method, String path) {
			this.method = method;
			this.builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
		}

		public Request header(String name, String value) {
			builder.header(name, value);
			return this;
		}

		public Request json(String json) {
			builder.header("Content-Type", "application/json");
			body = HttpRequest.BodyPublishers.ofString(json);
			return this;
		}

		public HttpResponse<String> send() throws IOException, InterruptedException {
			return CLIENT.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
		}

	}

}
