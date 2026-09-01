package com.github.santosleijon.tryhardhttpclient;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

/**
 * A minimal {@link HttpResponse} test double for use in unit tests.
 *
 * @param <T> the body type
 */
public final class FakeHttpResponse<T> implements HttpResponse<T> {

    private final int statusCode;
    private final T body;
    private final HttpRequest request;

    public FakeHttpResponse(int statusCode, T body, HttpRequest request) {
        this.statusCode = statusCode;
        this.body = body;
        this.request = request;
    }

    public FakeHttpResponse(int statusCode, T body) {
        this(statusCode, body, null);
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public T body() {
        return body;
    }

    @Override
    public HttpRequest request() {
        return request;
    }

    @Override
    public Optional<HttpResponse<T>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), (k, v) -> true);
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return request != null ? request.uri() : URI.create("http://localhost/");
    }

    @Override
    public HttpClient.Version version() {
        return HttpClient.Version.HTTP_1_1;
    }
}
