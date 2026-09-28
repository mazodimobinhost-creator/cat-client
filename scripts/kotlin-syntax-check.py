#!/usr/bin/env python3
"""Offline Kotlin syntax gate: parse every .kt file with tree-sitter and report ERROR nodes."""
import sys
import pathlib
import tree_sitter_kotlin
from tree_sitter import Language, Parser

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else '.')
lang = Language(tree_sitter_kotlin.language())
parser = Parser(lang)

total = 0
failed = 0
for path in sorted(ROOT.rglob('*.kt')):
    if '/build/' in str(path) or '/.git/' in str(path):
        continue
    total += 1
    source = path.read_bytes()
    tree = parser.parse(source)
    errors = []

    def walk(node):
        if node.type == 'ERROR' or node.is_missing:
            errors.append(node)
        for child in node.children:
            walk(child)

    walk(tree.root_node)
    if errors:
        failed += 1
        print(f'FAIL {path}: {len(errors)} syntax error(s)')
        for node in errors[:6]:
            row, col = node.start_point
            snippet = source[node.start_byte:node.end_byte][:120].decode('utf-8', 'replace').replace('\n', ' ⏎ ')
            print(f'   line {row + 1}:{col + 1} {node.type} -> {snippet}')

print(f'\n{total - failed}/{total} Kotlin files parse cleanly')
sys.exit(1 if failed else 0)
