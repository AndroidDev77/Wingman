"""Drafts are suggestions: the reply generator, its prompts and the drafts router import
nothing that can send (no connector, dispatcher or automation controller).

Checked statically (every import statement, including the transitive closure inside
``app``) and at runtime (importing the modules in a fresh interpreter loads none of
them). Mirrors the WING-009 analyzer boundary test.
"""

import ast
import subprocess
import sys
from pathlib import Path
from types import ModuleType

import app.ai.prompts as prompts_package
import app.ai.prompts.modes as modes_module
import app.ai.prompts.reply as reply_prompt_module
import app.ai.prompts.style as style_module
import app.ai.reply_generator as generator_module
import app.api.drafts as drafts_module

BACKEND = Path(__file__).resolve().parents[3]

FORBIDDEN_PREFIXES = (
    "app.connectors",
    "app.api.demo_connector",
    "app.services.dispatcher",
    "app.services.automation",
    "app.services.dispatch",
)
FORBIDDEN_NAMES = {"send", "dispatch", "Connector", "MockConnector", "Dispatcher"}

AI_MODULES: tuple[ModuleType, ...] = (
    generator_module,
    prompts_package,
    modes_module,
    reply_prompt_module,
    style_module,
)
ALL_MODULES = (*AI_MODULES, drafts_module)


def _imports(path: Path) -> list[tuple[str, list[str]]]:
    tree = ast.parse(path.read_text())
    found: list[tuple[str, list[str]]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            found += [(a.name, []) for a in node.names]
        elif isinstance(node, ast.ImportFrom) and node.module:
            found.append((node.module, [a.name for a in node.names]))
    return found


def _module_path(module: str) -> Path | None:
    rel = Path(*module.split("."))
    for candidate in (BACKEND / rel.with_suffix(".py"), BACKEND / rel / "__init__.py"):
        if candidate.is_file():
            return candidate
    return None


def _closure(start: Path) -> dict[str, Path]:
    seen: dict[str, Path] = {}
    todo = [start]
    while todo:
        for module, _ in _imports(todo.pop()):
            if not module.startswith("app") or module in seen:
                continue
            path = _module_path(module)
            if path is not None:
                seen[module] = path
                todo.append(path)
    return seen


def _forbidden(module: str) -> bool:
    return any(module == p or module.startswith(p + ".") for p in FORBIDDEN_PREFIXES)


def test_no_direct_send_imports() -> None:
    for mod in ALL_MODULES:
        assert mod.__file__ is not None
        for module, names in _imports(Path(mod.__file__)):
            assert not _forbidden(module), f"{mod.__name__} imports {module}"
            assert not FORBIDDEN_NAMES.intersection(names), f"{mod.__name__}: {names}"


def test_no_send_path_in_transitive_app_imports() -> None:
    for mod in ALL_MODULES:
        assert mod.__file__ is not None
        reachable = _closure(Path(mod.__file__))
        bad = sorted(m for m in reachable if _forbidden(m))
        assert bad == [], f"{mod.__name__} reaches {bad}"
    closure = _closure(Path(generator_module.__file__ or ""))
    assert "app.ai.llm_gateway" in closure
    assert "app.ai.memory" in closure
    assert "app.services.preferences" in closure


def test_importing_generator_loads_no_send_module_at_runtime() -> None:
    code = (
        "import sys\n"
        "import app.ai.reply_generator, app.ai.prompts, app.api.drafts\n"
        f"bad = [m for m in sys.modules if m.startswith({FORBIDDEN_PREFIXES!r})]\n"
        "print(','.join(sorted(bad)))\n"
    )
    out = subprocess.run(  # noqa: S603 - fixed interpreter and code
        [sys.executable, "-c", code],
        cwd=BACKEND,
        capture_output=True,
        text=True,
        check=True,
        timeout=60,
    )
    assert out.stdout.strip() == "", out.stdout


def test_model_output_cannot_carry_send_or_policy_instructions() -> None:
    from app.ai.reply_generator import DraftResult, ReplyOutput, UserClaim

    for model in (ReplyOutput, UserClaim):
        fields = set(model.model_fields)
        assert not {
            f for f in fields if "send" in f or "recipient" in f or "approv" in f or "policy" in f
        }, fields
        assert model.model_config.get("extra") == "forbid"
    # Approval flags exist only on the generator's result, computed in code.
    assert "approval_reasons" in DraftResult.model_fields
    assert "approval_reasons" not in ReplyOutput.model_fields
