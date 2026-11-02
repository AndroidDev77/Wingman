# Dating-platform experiments (WING-021)

Templates only. This folder contains **no code that talks to Tinder, Bumble or Hinge** and no
recorded observations yet. See `docs/integrations/dating_platform_matrix.md` for the procedure.

| File | Purpose |
|---|---|
| `notification_observation.schema.json` | JSON Schema for one redacted notification observation (matrix §8). Structure only: lengths, key names, flags |
| `live_test_observation.schema.json` | JSON Schema for one redacted step of the owner's live test of an experimental API connector (matrix §6f) |
| `results_template.md` | Human-readable results sheet per app and run |
| `templates/*.template.json` | Valid, empty starting points (marked TEMPLATE, not observations) |
| `templates/invalid/*.json` | Negative examples the schemas must reject (raw text field; a challenge recorded as pass) |

Rules:

* Never commit message text, names, phone numbers, OTP or email codes, tokens, session keys,
  device or install ids, photos or screenshots of chats. Hash ids with a per-run salt.
* A captcha, attestation or other challenge is recorded as `blocked`, never `pass`.
* Agents never run live tests or enter credentials. Only the owner or a tester does, on their own
  device and account.

Validate (needs only `uv`):

```bash
uv run --quiet --no-project --with jsonschema==4.23.0 python - <<'PY'
import json, pathlib, sys
from jsonschema import Draft202012Validator, FormatChecker
root = pathlib.Path("experiments/dating_platforms")
schemas = {
    "dating_notification_observation.v1": json.loads((root / "notification_observation.schema.json").read_text()),
    "dating_live_test_observation.v1": json.loads((root / "live_test_observation.schema.json").read_text()),
}
ok = True
for s in schemas.values():
    Draft202012Validator.check_schema(s)
for path in sorted(root.glob("templates/**/*.json")):
    doc = json.loads(path.read_text())
    errs = list(Draft202012Validator(schemas[doc["schema_version"]], format_checker=FormatChecker()).iter_errors(doc))
    expect_invalid = "invalid" in path.parts
    good = bool(errs) == expect_invalid
    ok &= good
    print(("ok  " if good else "FAIL"), path, "(rejected)" if errs else "(valid)")
sys.exit(0 if ok else 1)
PY
```
