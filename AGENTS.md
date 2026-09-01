# TryHardHTTPClient

This repository contains a wrapper that extends Java's native HTTP client `java.net.http.HttpClient` with additional features such as automatic retries. 

Read `docs/requirements.md` before changing production code.

## Required working method

- Do not begin implementation before presenting a plan.
- Identify ambiguous or conflicting requirements and ask before deciding.
- Work on only the requested phase.
- Write or update tests before changing production behavior.
- Run the narrowest relevant tests after each change.
- Run `mvn verify` before claiming completion.
- Never suppress, disable or weaken a failing test to make the build pass.
- Never reduce assertions or coverage thresholds without approval.
- Never commit code changes without approval.
- Never modify requirements, guardrails or CI configuration without approval.
- Report commands run and their exact results.

## Architecture

- Wrap `java.net.http.HttpClient` using composition.
- Keep transport, retry policy, delay calculation, scheduling and
  observability separate.
- Prefer immutable value objects and constructor injection.
- Production code must be thread-safe.
- Do not create global mutable state.
- Do not retain unbounded request history.
- Do not use `Thread.sleep`.
- Do not create one underlying HttpClient per request.
- Do not add dependencies without approval.

## Retry safety

- Retry non-idempotent requests only after explicit caller opt-in.
- Do not retry TLS, authentication or permanent protocol failures.
- Every request body must be reproducible before it can be retried.
- Respect cancellation, interruption, maximum attempts and deadlines.
- Close or consume discarded response bodies.
- Prevent retry storms using capped exponential backoff and jitter.

## Security

- Never print or store credentials, tokens, cookies or message bodies.
- Do not read `.env`, credentials, key stores or files outside this repository.
- Never execute destructive Git or filesystem commands.
- Always bind test servers to a loopback address.

## Testing

- Write unit tests to test retry logic with fake clocks, schedulers and randomness.
- Do not test backoff using real multi-second waits.
- Use a localhost HTTP server for HTTP integration tests.
- Bind test servers to port zero.
- Put timeouts on asynchronous and integration tests.
- Include concurrency, cancellation and body-replay tests.
- Do not mock the library behavior under test.
