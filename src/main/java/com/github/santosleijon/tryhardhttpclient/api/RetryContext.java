package com.github.santosleijon.tryhardhttpclient.api;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * Immutable snapshot of the state at the point where a retry decision must be made.
 *
 * <p>Exactly one of {@code response} and {@code failure} is present; never both, never neither.
 *
 * @param request       the request that was sent
 * @param response      the HTTP response received, if any
 * @param failure       the exception thrown by the HTTP transport, if any
 * @param attemptNumber the 1-based index of the attempt that just completed
 */
public record RetryContext(
        HttpRequest request,
        Optional<HttpResponse<?>> response,
        Optional<Throwable> failure,
        int attemptNumber) {
}
