# /// script
# requires-python = ">=3.12"
# dependencies = [
#   "openapi-spec-validator==0.9.0",
#   "jsonschema[format-nongpl]==4.26.0",
#   "referencing==0.37.0",
#   "pyyaml>=6.0",
# ]
# ///
"""Validate the Wingman contracts. Exits non-zero on any failure.

Run from anywhere:  contracts/validate.sh   (or: uv run --script contracts/validate.py)

Checks:
  1. openapi.yaml is a valid OpenAPI 3.1 document (openapi-spec-validator).
  2. Every JSON Schema in schemas/ is a valid Draft 2020-12 schema.
  3. Contract conventions: every operation has an operationId; every product path is
     under /v1; every POST requires the Idempotency-Key header; every error response
     is application/problem+json; every /v1 operation is authenticated.
  4. Every example in examples/manifest.json validates against its schema, with
     format assertion (date-time, uuid, email, uri-reference) enabled.
  5. Every example under examples/invalid/ FAILS validation (negative tests). When a
     manifest entry has `expect_error`, at least one error must contain that text, so a
     negative example cannot pass by failing for an unrelated reason.
  6. No example file is missing from, or orphaned by, the manifest.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError
from openapi_spec_validator import validate as validate_openapi
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

HERE = Path(__file__).resolve().parent
OPENAPI = HERE / "openapi.yaml"
SCHEMAS = HERE / "schemas"
EXAMPLES = HERE / "examples"
HTTP_METHODS = {"get", "put", "post", "delete", "patch", "head", "options", "trace"}

failures: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)
    print(f"  FAIL {msg}")


def ok(msg: str) -> None:
    print(f"  ok   {msg}")


def load_openapi() -> dict[str, Any]:
    with OPENAPI.open() as f:
        doc = yaml.safe_load(f)
    assert isinstance(doc, dict)
    return doc


def build_registry(openapi: dict[str, Any]) -> Registry:
    resources: list[tuple[str, Resource[Any]]] = [
        (OPENAPI.as_uri(), DRAFT202012.create_resource(openapi)),
    ]
    for path in sorted(SCHEMAS.glob("*.json")):
        schema = json.loads(path.read_text())
        res = DRAFT202012.create_resource(schema)
        # Reachable both by file location (openapi.yaml's relative $ref) and by $id.
        resources.append((path.as_uri(), res))
        if "$id" in schema:
            resources.append((schema["$id"], res))
    return Registry().with_resources(resources)


def check_openapi(openapi: dict[str, Any]) -> None:
    print("[1] OpenAPI 3.1 document")
    if openapi.get("openapi", "").split(".")[:2] != ["3", "1"]:
        fail(f"openapi version must be 3.1.x, got {openapi.get('openapi')!r}")
        return
    try:
        validate_openapi(openapi, base_uri=OPENAPI.as_uri())
    except Exception as exc:  # noqa: BLE001 - report any validator error
        fail(f"openapi.yaml invalid: {exc}")
        return
    ok(f"openapi.yaml is valid OpenAPI {openapi['openapi']}")


def check_json_schemas() -> None:
    print("[2] JSON Schemas (Draft 2020-12)")
    for path in sorted(SCHEMAS.glob("*.json")):
        try:
            Draft202012Validator.check_schema(json.loads(path.read_text()))
        except (SchemaError, json.JSONDecodeError) as exc:
            fail(f"{path.relative_to(HERE)}: {exc}")
            continue
        ok(str(path.relative_to(HERE)))


def check_conventions(openapi: dict[str, Any]) -> None:
    print("[3] Contract conventions")
    params = openapi["components"]["parameters"]
    responses = openapi["components"]["responses"]
    global_security = openapi.get("security")
    op_ids: set[str] = set()
    n_ops = 0

    def resolve(obj: dict[str, Any], table: dict[str, Any]) -> dict[str, Any]:
        ref = obj.get("$ref")
        return table[ref.rsplit("/", 1)[-1]] if ref else obj

    for path, item in openapi["paths"].items():
        if path != "/health" and not path.startswith("/v1/"):
            fail(f"{path}: product paths must be under /v1")
        shared = [resolve(p, params) for p in item.get("parameters", [])]
        for method, op in item.items():
            if method not in HTTP_METHODS:
                continue
            n_ops += 1
            where = f"{method.upper()} {path}"
            op_id = op.get("operationId")
            if not op_id:
                fail(f"{where}: missing operationId")
            elif op_id in op_ids:
                fail(f"{where}: duplicate operationId {op_id}")
            else:
                op_ids.add(op_id)
            all_params = shared + [resolve(p, params) for p in op.get("parameters", [])]
            if method == "post":
                idem = [p for p in all_params if p["in"] == "header" and p["name"] == "Idempotency-Key"]
                if not idem or not idem[0].get("required"):
                    fail(f"{where}: POST must require the Idempotency-Key header")
            security = op.get("security", global_security)
            if path.startswith("/v1/") and not security:
                fail(f"{where}: /v1 operations must be authenticated")
            for status, resp in op.get("responses", {}).items():
                if status != "default" and int(status) < 400:
                    continue
                resp = resolve(resp, responses)
                types = set(resp.get("content", {}))
                if types != {"application/problem+json"}:
                    fail(f"{where} {status}: error responses must be application/problem+json")
    if not failures:
        ok(f"{n_ops} operations: /v1 prefix, operationIds, Idempotency-Key, auth, problem+json")


def validator_for(schema_ref: str, registry: Registry) -> Draft202012Validator:
    file_part, _, pointer = schema_ref.partition("#")
    uri = (HERE / file_part).resolve().as_uri() + (f"#{pointer}" if pointer else "")
    return Draft202012Validator(
        {"$ref": uri}, registry=registry, format_checker=FormatChecker()
    )


def check_examples(registry: Registry) -> None:
    print("[4/5] Examples (valid must pass, invalid/ must fail)")
    manifest = json.loads((EXAMPLES / "manifest.json").read_text())
    listed: set[str] = set()
    n_valid = n_invalid = 0
    for entry in manifest["examples"]:
        rel = entry["file"]
        listed.add(rel)
        path = EXAMPLES / rel
        if not path.is_file():
            fail(f"manifest lists missing file examples/{rel}")
            continue
        instance = json.loads(path.read_text())
        try:
            errors = sorted(
                validator_for(entry["schema"], registry).iter_errors(instance),
                key=lambda e: list(e.absolute_path),
            )
        except Exception as exc:  # noqa: BLE001 - unresolvable ref etc.
            fail(f"examples/{rel}: could not validate against {entry['schema']}: {exc}")
            continue
        expect_invalid = rel.startswith("invalid/")
        if expect_invalid:
            n_invalid += 1
            expected = entry.get("expect_error")
            if errors and expected and not any(expected in e.message for e in errors):
                got = "; ".join(e.message for e in errors[:3])
                fail(f"examples/{rel} rejected, but not for {expected!r} (got: {got})")
            elif errors:
                ok(f"examples/{rel} rejected as expected ({len(errors)} error(s))")
            else:
                fail(f"examples/{rel} should be INVALID ({entry.get('why_invalid')}) but passed")
        else:
            n_valid += 1
            if errors:
                for err in errors[:5]:
                    loc = "/" + "/".join(str(p) for p in err.absolute_path)
                    fail(f"examples/{rel} at {loc}: {err.message}")
            else:
                ok(f"examples/{rel} -> {entry['schema']}")
    if n_invalid == 0:
        fail("no negative examples under examples/invalid/")

    print("[6] Manifest coverage")
    on_disk = {
        str(p.relative_to(EXAMPLES))
        for p in EXAMPLES.rglob("*.json")
        if p.name != "manifest.json"
    }
    for orphan in sorted(on_disk - listed):
        fail(f"examples/{orphan} is not listed in manifest.json")
    if on_disk <= listed:
        ok(f"{n_valid} valid + {n_invalid} invalid examples, all listed")


def main() -> int:
    openapi = load_openapi()
    check_openapi(openapi)
    check_json_schemas()
    check_conventions(openapi)
    check_examples(build_registry(openapi))
    print()
    if failures:
        print(f"CONTRACT VALIDATION FAILED: {len(failures)} problem(s)")
        return 1
    print("CONTRACT VALIDATION PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
