"""Tiny Kotlin lexer helpers: find code braces while skipping strings, templates, chars and comments.

Used by the code-map generator and the one-time MainActivity split. Not a parser — it only needs to know which
characters are real code so brace matching never counts a `{` inside a string or comment.
"""
from __future__ import annotations


def code_mask(src: str) -> bytearray:
    """mask[i] == 1 when src[i] is code (not inside a string literal, char literal or comment).

    String templates `${ ... }` are code again (with their own nested strings)."""
    n = len(src)
    mask = bytearray(n)
    i = 0
    # stack of contexts: 'code', ('str', raw), ('tmpl', depth)
    stack: list = [['code', 0]]
    while i < n:
        ctx = stack[-1]
        c = src[i]
        if ctx[0] == 'code' or ctx[0] == 'tmpl':
            if src.startswith('//', i):
                j = src.find('\n', i)
                i = n if j < 0 else j
                continue
            if src.startswith('/*', i):
                depth = 1
                j = i + 2
                while j < n and depth:
                    if src.startswith('/*', j):
                        depth += 1; j += 2
                    elif src.startswith('*/', j):
                        depth -= 1; j += 2
                    else:
                        j += 1
                i = j
                continue
            if src.startswith('"""', i):
                stack.append(['str', True]); i += 3; continue
            if c == '"':
                stack.append(['str', False]); i += 1; continue
            if c == "'":
                # char literal: 'x', '\n', '\u0041', '\''
                j = i + 1
                if j < n and src[j] == '\\':
                    j += 2
                    while j < n and src[j] != "'":
                        j += 1
                else:
                    j += 1
                if j < n and src[j] == "'":
                    i = j + 1
                    continue
                mask[i] = 1; i += 1; continue
            if ctx[0] == 'tmpl':
                if c == '{':
                    ctx[1] += 1
                elif c == '}':
                    if ctx[1] == 0:
                        stack.pop(); i += 1; continue
                    ctx[1] -= 1
            mask[i] = 1
            i += 1
            continue
        # inside a string
        raw = ctx[1]
        if raw:
            if src.startswith('"""', i):
                j = i + 3
                while j < n and src[j] == '"':
                    j += 1
                stack.pop(); i = j; continue
        else:
            if c == '\\':
                i += 2; continue
            if c == '"':
                stack.pop(); i += 1; continue
            if c == '\n':
                stack.pop(); i += 1; continue  # unterminated: recover
        if src.startswith('${', i):
            stack.append(['tmpl', 0]); i += 2; continue
        i += 1
    return mask


def match_brace(src: str, mask: bytearray, open_idx: int) -> int:
    """Index of the `}` closing the `{` at open_idx (code braces only)."""
    depth = 0
    for k in range(open_idx, len(src)):
        if not mask[k]:
            continue
        ch = src[k]
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return k
    raise ValueError('unbalanced braces from %d' % open_idx)


def function_span(src: str, mask: bytearray, fun_idx: int) -> tuple[int, int]:
    """(start, end) of the function whose `fun` keyword line starts at fun_idx; end is exclusive (after `}` or the
    expression body's last line)."""
    k = fun_idx
    paren = 0
    seen_paren = False
    n = len(src)
    while k < n:
        if mask[k]:
            ch = src[k]
            if ch == '(':
                paren += 1; seen_paren = True
            elif ch == ')':
                paren -= 1
            elif paren == 0 and seen_paren and ch == '{':
                end = match_brace(src, mask, k)
                e = src.find('\n', end)
                return fun_idx, (n if e < 0 else e + 1)
            elif paren == 0 and seen_paren and ch == '=':
                # expression body: ends at the first newline where bracket depth is 0 and the next line is not a
                # continuation (.foo / ?: / && / || / operator)
                depth = 0
                j = k + 1
                while j < n:
                    if mask[j]:
                        cj = src[j]
                        if cj in '([{':
                            depth += 1
                        elif cj in ')]}':
                            depth -= 1
                    if src[j] == '\n' and depth == 0 and src[k + 1:j].strip():
                        nxt = src.find('\n', j + 1)
                        following = src[j + 1: nxt if nxt >= 0 else n].strip()
                        if not following.startswith(('.', '?.', '?:', '&&', '||', '+', '-', '*', '/', ':')):
                            return fun_idx, j + 1
                    j += 1
                return fun_idx, n
        k += 1
    raise ValueError('no body for function at %d' % fun_idx)
