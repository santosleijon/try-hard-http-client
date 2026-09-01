# TryHardHttpClient

A small Java 21 library that wraps `java.net.http.HttpClient` with configurable automatic retries.

## Requirements

- Java 21+
- Maven 3.x

## Quick start

Add the library to your project and build a client:

```java
import api.com.github.santosleijon.tryhardhttpclient.TryHardHttpClient;
import api.com.github.santosleijon.tryhardhttpclient.RetryPolicy;

HttpClient httpClient = HttpClient.newHttpClient();

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(4)           // 1 initial attempt + up to 3 retries
        .build();

TryHardHttpClient client = TryHardHttpClient.builder(httpClient)
        .retryPolicy(policy)
        .build();
```

Send a request:

```java
CompletableFuture<HttpResponse<String>> future = client.sendAsync(
        () -> HttpRequest.newBuilder(URI.create("https://api.example.com/data")).GET().build(),
        HttpResponse.BodyHandlers.ofString());

HttpResponse<String> response = future.join();
```

The `requestSupplier` lambda is called once per attempt, which allows the library to replay the request body on retry without buffering it internally.

## Use cases

### Retrying transient server errors on GET requests

By default the policy retries GET, HEAD, PUT, DELETE, and OPTIONS requests when the server responds with status codes `408`, `429`, `500`, `502`, `503`, or `504`.

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(5)
        .build();
```

When `maxAttempts` is reached the final HTTP response is returned to the caller unchanged — the library never throws because of a bad status code alone.

### Retrying POST or PATCH requests (explicit opt-in)

Non-idempotent methods are not retried by default. Opt in with `allowRetryForMethods`:

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .allowRetryForMethods(Set.of("POST"))
        .build();
```

Every attempt must be able to reproduce the request body. Pass a `Supplier<HttpRequest>` that recreates the full request, including its body, on each call.

### Retrying on network exceptions

Exceptions are not retried without explicit opt-in. Enable retry on specific exception types:

```java
RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(4)
        .retryableExceptions(Set.of(IOException.class))
        .build();
```

`SSLException` and its subtypes are never retried regardless of this setting, because TLS handshake failures and certificate errors are not transient.

### Cancelling an in-flight request

The `CompletableFuture` returned by `sendAsync` is cancellation-aware. Cancelling it prevents any pending retry from starting:

```java
CompletableFuture<HttpResponse<String>> future = client.sendAsync(supplier, handler);

// elsewhere
future.cancel(true); // scheduled retry will not fire
```

### Custom delay strategy

Override the default exponential-with-jitter backoff:

```java
DelayStrategy fixed = attemptNumber -> Duration.ofSeconds(2);

RetryPolicy policy = RetryPolicy.builder()
        .maxAttempts(3)
        .delayStrategy(fixed)
        .build();
```

The built-in `DelayStrategy.exponentialWithJitter(base, cap)` uses the formula  
`min(base × 2^(attemptNumber−1) + jitter, cap)`, where jitter is a uniformly distributed random value in `[0, base)`. This prevents retry storms when many clients hit the same server at once.

```java
DelayStrategy backoff = DelayStrategy.exponentialWithJitter(
        Duration.ofMillis(100),   // base — also the jitter upper bound
        Duration.ofSeconds(10));  // cap
```

### Custom retry scheduler

By default a single JVM-scoped `ScheduledExecutorService` backed by daemon threads is shared across all client instances. Inject your own scheduler for testing or lifecycle control:

```java
RetryScheduler myScheduler = (task, delay) -> {
    ScheduledFuture<?> f = myExecutor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
    return () -> f.cancel(false);
};

TryHardHttpClient client = TryHardHttpClient.builder(httpClient)
        .retryPolicy(policy)
        .scheduler(myScheduler)
        .build();
```

## API overview

All public types live in `com.github.santosleijon.tryhardhttpclient.api`.

| Type | Role |
|---|---|
| `TryHardHttpClient` | Entry point. Call `TryHardHttpClient.builder(httpClient)` to construct an instance. |
| `TryHardHttpClient.Builder` | Fluent builder. `retryPolicy` is mandatory; `scheduler` is optional. |
| `RetryPolicy` | Determines whether and after how long to retry. Call `RetryPolicy.builder()` to construct. |
| `RetryContext` | Immutable snapshot of one completed attempt: request, optional response, optional exception, attempt number. |
| `RetryDecision` | Immutable result of `RetryPolicy.evaluate()`: retry flag, human-readable reason, and delay. |
| `DelayStrategy` | Computes the delay before each retry attempt. |
| `RetryScheduler` | Schedules a `Runnable` after a `Duration`. Returns a `ScheduledTask` cancel handle. |
| `ScheduledTask` | Cancel handle returned by `RetryScheduler.schedule()`. |

Implementations are in `com.github.santosleijon.tryhardhttpclient.impl` and are not part of the public API.

## Key design decisions

### Composition over reimplementation

`TryHardHttpClient` wraps a caller-provided `java.net.http.HttpClient` using composition. All actual HTTP protocol handling is delegated to the JDK client; the library only decides whether and when to retry.

### Request supplier instead of a fixed request

`sendAsync` accepts `Supplier<HttpRequest>` rather than a single `HttpRequest`. This makes body replay explicit and correct: each attempt calls the supplier and gets a fresh, unconsumed request. The caller decides how to reconstruct the body — the library never buffers it.

### Retry safety by default

Non-idempotent methods (POST, PATCH) are not retried unless the caller explicitly opts in. This prevents accidental duplicate mutations caused by a transparent retry layer.

TLS failures (`SSLException`) are never retried because they indicate a configuration or security problem, not a transient server error.

### Capped exponential backoff with jitter

Delays grow as `base × 2^n` up to a configurable cap. Uniform random jitter in `[0, base)` desynchronises clients that all failed at the same instant, reducing the chance of a retry storm.

### Single shared scheduler

All client instances share one `ScheduledExecutorService` by default, initialised on first use (initialization-on-demand holder pattern). Daemon threads mean the scheduler never prevents JVM shutdown. A custom `RetryScheduler` can be injected for testing or when a managed executor is required.

### Cancellation propagates to retries

Cancelling the `CompletableFuture` returned by `sendAsync` cancels any scheduled but not-yet-started retry attempt. The `ScheduledTask` handle returned by the scheduler is stored and cancelled atomically, so there is no window where a retry fires after the caller has cancelled.

### Immutability and thread safety

`TryHardHttpClient`, `RetryPolicy`, `RetryDecision`, `RetryContext`, and `DelayStrategy` instances are immutable and safe to share across threads. No global mutable state is retained between requests.

## Building and testing

```bash
# compile and run all tests
mvn verify

# run only unit tests
mvn test
```

Tests use JUnit 5 and the JDK embedded HTTP server (`com.sun.net.httpserver.HttpServer`) bound to port zero on localhost. Retry logic is tested with injected fake clocks, schedulers, and randomness — no real sleep delays.
