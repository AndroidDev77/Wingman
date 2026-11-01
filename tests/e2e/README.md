# WING-025 end-to-end acceptance suite

Results, CI links and the connector matrix: [`docs/e2e_results.md`](../../docs/e2e_results.md).

## Run it

```bash
scripts/e2e/run.sh            # whole backend suite (about 3.5 minutes)
scripts/e2e/run.sh -k x2      # one scenario
```

Needs `uv` and either Postgres 16 server binaries (a throwaway cluster is started, as for the
backend DB tests) or `WINGMAN_E2E_DATABASE_URL` pointing at an EMPTY database. Port 8000 must be
free. Artifacts land in `tests/e2e/.artifacts/` (`server.log`, `junit.xml`,
`zero_duplicate_sends.json`).

The Android part runs on an emulator: `scripts/e2e/emulator-tests.sh` (CI job `emulator` in
`.github/workflows/e2e.yml`). Locally it needs a running emulator, `scripts/dev-run.sh` with the
dev auth stub, and the APKs from
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pwingman.useMockApi=false`.

## What is real and what is simulated

| Real | Simulated (and labeled) |
|---|---|
| HTTP API (`scripts/dev-run.sh`), dev-stub JWTs through the normal verifier, Postgres migrated by Alembic, the automation controller, the single dispatcher, the background worker loop, process restarts (SIGKILL and SIGTERM) | The LLM (`[dev mock]` deterministic handler, I-016); the `mock` transport (`MockConnector`); for `sms` connections in the backend suite, `SimulatedSmsDevice` (claims, one "radio" attempt per key, outcome envelopes; sends nothing) |

## Files

| File | What |
|---|---|
| `harness.py` | HTTP client (`Api`), server control (`Server`), contract validation, `SimulatedSmsDevice`, read-only DB observer |
| `conftest.py` | Starts `scripts/dev-run.sh` once per session; mints dev-stub tokens with the backend CLI |
| `test_flows.py` | F1 to F9: onboarding, pairing and injection, personalized reply, approve/edit/reject/send, memory, autopilot with delay, pause and takeover (D-014), unpair, isolation |
| `test_failures.py` | X1 to X5: duplicate inbound (concurrent and sequential), offline device and lease expiry, retry on the worker, takeover mid-flight, server restart mid-flow |
| `test_zz_invariants.py` | Runs last: zero duplicate sends over the whole run; no errors in the server log |
