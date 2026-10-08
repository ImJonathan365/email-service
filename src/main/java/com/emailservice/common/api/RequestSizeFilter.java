package com.emailservice.common.api;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MAX_REQUEST_BYTES (AC-07.6): a declared Content-Length over the limit is answered with 413
 * before reading; a chunked body is counted while it is read and fails with the same 413.
 */
public class RequestSizeFilter extends OncePerRequestFilter {

	private final long maxBytes;

	private final ProblemWriter problems;

	public RequestSizeFilter(long maxBytes, ProblemWriter problems) {
		this.maxBytes = maxBytes;
		this.problems = problems;
	}

	/** Raised while reading an oversized chunked body; mapped to 413 by the exception handler. */
	public static final class PayloadTooLargeException extends IOException {

		PayloadTooLargeException() {
			super("Request body exceeds MAX_REQUEST_BYTES");
		}

	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			problems.write(request, response, tooLarge());
			return;
		}
		chain.doFilter(new LimitedRequest(request, maxBytes), response);
	}

	static ApiException tooLarge() {
		return new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "The request body exceeds the maximum allowed size.");
	}

	private static final class LimitedRequest extends HttpServletRequestWrapper {

		private final long maxBytes;

		LimitedRequest(HttpServletRequest request, long maxBytes) {
			super(request);
			this.maxBytes = maxBytes;
		}

		@Override
		public ServletInputStream getInputStream() throws IOException {
			ServletInputStream delegate = super.getInputStream();
			return new ServletInputStream() {

				private long read;

				@Override
				public int read() throws IOException {
					int value = delegate.read();
					if (value >= 0) {
						count(1);
					}
					return value;
				}

				@Override
				public int read(byte[] buffer, int offset, int length) throws IOException {
					int n = delegate.read(buffer, offset, length);
					if (n > 0) {
						count(n);
					}
					return n;
				}

				private void count(int n) throws PayloadTooLargeException {
					read += n;
					if (read > maxBytes) {
						throw new PayloadTooLargeException();
					}
				}

				@Override
				public boolean isFinished() {
					return delegate.isFinished();
				}

				@Override
				public boolean isReady() {
					return delegate.isReady();
				}

				@Override
				public void setReadListener(ReadListener listener) {
					delegate.setReadListener(listener);
				}
			};
		}

	}

}
