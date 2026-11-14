#!/usr/bin/env python3
"""Startup guard — forbids Context work in Activity/Application constructors.

A class-level property initializer (or init block) in an Activity runs inside
the Activity's constructor: `ActivityThread` creates the instance with
`Class.newInstance()` BEFORE `attachBaseContext()` is called, so the Activity's
base context is NULL at that moment. Any Context call there — reading
SharedPreferences, building a View with `this`, resolving resources, getting a
system service — throws NullPointerException while launching, which looks to
the user like «the app opens and closes instantly»:

  java.lang.RuntimeException: Unable to instantiate activity
    ComponentInfo{com.cat.client/com.cat.client.MainActivity}
  Caused by: java.lang.NullPointerException: Attempt to invoke virtual method
    'android.content.SharedPreferences android.content.Context.getSharedPreferences(...)'
    on a null object reference
    at android.content.ContextWrapper.getSharedPreferences(ContextWrapper.java)
    at com.cat.client.MainActivity.<init>(...)

That exact bug shipped from beta70 to beta77 (scannerForceV6 property) and no
test caught it: compilation is happy, unit tests never instantiate the
Activity, and the emulator smoke step has no KVM on the runner. This static
guard closes the hole: it parses every Kotlin source, finds classes that are
Activities/Applications/Services/Receivers/Providers, and fails on any property
initializer or init block that touches the Context. `by lazy` / `lazy { }` are
allowed because they defer execution past construction.

Exit code 0 = clean, 1 = violations (printed with file:line).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/cat/client"

RISKY_BASES = (
    "Activity",
    "AppCompatActivity",
    "ComponentActivity",
    "Application",
    "Service",
    "VpnService",
    "IntentService",
    "ContentProvider",
    "BroadcastReceiver",
    "AppWidgetProvider",
    "TileService",
)

# Context-dependent calls that must not run in a constructor.
CTX_CALL = re.compile(
    r"\b(getSharedPreferences|getSystemService|getString|getResources|getAssets"
    r"|getTheme|getPreferences|getPackageManager|obtainStyledAttributes"
    r"|getFilesDir|getCacheDir|getExternalFilesDir|openFileOutput|registerReceiver"
    r"|startService|bindService|getContentResolver)\s*\("
)
# `Something(this)` — passing the Activity as a Context to a constructor/factory.
THIS_ARG = re.compile(r"\b[A-Z][A-Za-z0-9_]*\s*\(\s*(this|this@[A-Za-z0-9_]+)\s*[,)]")

CLASS_DECL = re.compile(
    r"^(?:internal\s+|open\s+|abstract\s+|private\s+|sealed\s+)*class\s+([A-Za-z0-9_]+)\s*(?:<[^>]*>)?\s*(?::|$)"
)
PROPERTY = re.compile(
    r"^ {4}(?:(?:private|internal|protected|public)\s+)?(?:val|var)\s+[A-Za-z0-9_]+"
)
INIT_BLOCK = re.compile(r"^ {4}init\s*\{")


def class_bases(lines: list[str], start: int) -> tuple[str, int]:
    """Return (class name, index just past the declaration) — joins continuation."""
    decl = lines[start].rstrip()
    end = start
    while "{" not in decl and end + 1 < len(lines):
        end += 1
        decl += " " + lines[end].strip()
    match = CLASS_DECL.match(lines[start])
    name = match.group(1) if match else "?"
    bases = decl.split(":", 1)[1] if ":" in decl else ""
    return name, bases, end


def risky_class(name: str, bases: str) -> bool:
    return any(
        re.search(rf"(?<![A-Za-z0-9_]){base}\s*(\(|\{{|,|$)", bases) for base in RISKY_BASES
    )


def initializer_text(lines: list[str], start: int) -> str:
    """Join a property line with its continuation lines."""
    text = lines[start].rstrip()
    index = start
    while True:
        stripped = text.rstrip()
        opens = text.count("(") - text.count(")")
        deeper = index + 1 < len(lines) and lines[index + 1].startswith("        ")
        continues = stripped.endswith(("=", "(", ",", ".", "&&", "||", "?:", "->"))
        if index + 1 < len(lines) and (opens > 0 or continues or (deeper and not stripped.endswith(("{", "}")))):
            index += 1
            text += " " + lines[index].strip()
            continue
        break
    return text


def block_text(lines: list[str], start: int) -> tuple[str, int]:
    """Collect an init { ... } block via brace counting."""
    text = ""
    depth = 0
    index = start
    while index < len(lines):
        text += lines[index] + "\n"
        depth += lines[index].count("{") - lines[index].count("}")
        if depth <= 0 and "{" in lines[start]:
            break
        index += 1
    return text, index


def scan_file(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    problems: list[str] = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if CLASS_DECL.match(line) and line.startswith(("class", "internal ", "open ", "abstract ")):
            name, bases, cursor = class_bases(lines, index)
            if not risky_class(name, bases):
                index = cursor + 1
                continue
            index = cursor + 1
            # Walk the class body until column-0 closing brace.
            while index < len(lines) and not lines[index].startswith("}"):
                body = lines[index]
                if PROPERTY.match(body) and "=" in body:
                    text = initializer_text(lines, index)
                    head = text.split("{", 1)[0]
                    if "by lazy" not in head and not re.search(r"=\s*lazy\s*\(", text):
                        if CTX_CALL.search(text) or THIS_ARG.search(text):
                            problems.append(f"{path}:{index + 1}: constructor-time Context use -> {text.strip()[:120]}")
                elif INIT_BLOCK.match(body):
                    text, end = block_text(lines, index)
                    if CTX_CALL.search(text) or THIS_ARG.search(text):
                        problems.append(f"{path}:{index + 1}: init block touches Context -> {text.strip().splitlines()[0][:120]}")
                    index = end
                index += 1
        else:
            index += 1
    return problems


def main() -> int:
    files = sorted(SRC.glob("*.kt"))
    if len(sys.argv) > 1:
        files = [Path(arg) for arg in sys.argv[1:]]
    problems: list[str] = []
    for path in files:
        problems.extend(scan_file(path))
    if problems:
        print("STARTUP GUARD FAILED — these run inside a constructor, where the Activity context is still null:")
        for problem in problems:
            print("  " + problem)
        print("\nFix: move the read into onCreate/onResume, use applicationContext, or defer with `by lazy`.")
        return 1
    print(f"startup guard OK — no constructor-time Context use in {len(files)} Kotlin sources")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
