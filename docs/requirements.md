# Requirements for TryHardHttpClient

## Scope

Implement a small Java 21 library wrapping `java.net.http.HttpClient` to provide HTTP requests with configurable automatic retries.

The library must use composition. It must not replace or reimplement HTTP protocol handling.

## Requirements

**REQ-001:** The library shall require Java 21+.

**REQ-002:** The primary type shall be `TryHardHttpClient`.

**REQ-003:** The library shall wrap a caller-provided `java.net.http.HttpClient`.

**REQ-004:** The library shall provide an asynchronous send operation returning
`CompletableFuture<HttpResponse<T>>`.

**REQ-005:** The send operation shall accept a `Supplier<HttpRequest>` so that a new request can be created for every
attempt.

**REQ-006:** `maxAttempts` shall mean the total number of attempts, including the initial attempt.

**REQ-007:** A GET request that receives the following HTTP response code shall be retried until it succeeds or reaches `maxAttempts`: 408/429/500/502/503/504

**REQ-008:** When `maxAttempts` is reached, the final HTTP response shall be returned to the caller.

**REQ-009:** Retry delays shall use an injected scheduler and shall not use `Thread.sleep`.

**REQ-010:** POST or PATCH requests shall not be retried by default.

**REQ-011:** Cancelling the returned future shall prevent any scheduled future attempt from starting.
