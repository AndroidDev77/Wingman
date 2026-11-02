# WING-020 iMessage companion feasibility spike

Pure-logic Python model of the macOS companion described in
[`docs/integrations/imessage.md`](../../docs/integrations/imessage.md). It runs on Linux
against a **synthetic** `chat.db`-shaped SQLite fixture. It does not call any Apple API,
never runs `osascript`, and never touches the network.

Python is used here only because the authoring environment had no Mac and no Swift
toolchain. WING-030 should be written in **Swift**; this package is its executable spec
(the same identities, cursor, dedup, send ledger and outcome rules). See
"Recommended stack" in the doc.

```bash
cd macos/imessage_spike
uv run --frozen pytest          # fixture tests (Linux)
uv run --frozen ruff check . && uv run --frozen ruff format --check .
```

| Path | What |
|---|---|
| `wingman_imessage/` | the logic (reader, typedstream fallback, envelopes, state, poller, AppleScript builder, send plan, capabilities) |
| `tests/fixture_db.py` | builds the synthetic chat.db from the community-documented schema |
| `tests/` | fixture, cursor/restart, dedup, `attributedBody`, groups, injection, contract validation |
| `manual/mac_probe.py` | the **manual** real-Mac probe (never run by CI) |
| `manual/RUN_TEMPLATE.md` | what to record for a real-Mac run |
