#!/usr/bin/env python3
"""Heuristic audit: heavy/blocking calls in UI files that are not inside an IO/Default/thread block.

Tracks brace nesting (string-literal aware) and remembers the text that opened each block.
A suspect call is reported when no enclosing block opener mentions a background context.
"""
import re, sys, pathlib

SUSPECT = re.compile(r"(cachedSnapshot\(|MihomoConfigParser\.parse\(|SubConvConverter\.convert\(|UserSubscriptionImporter\.import\(|"
                     r"InetAddress\.getBy|getAllByName\(|openConnection\(|\bSocket\(\)|\.readText\(\)|readBytes\(\)|"
                     r"MihomoLinkConfigBuilder\.build\(|\.execute\(\)|URL\(\"|probe[A-Z]\w*\(|ping[A-Z]\w*\(|scan[A-Z]\w*\(|"
                     r"fetch[A-Z]\w*\(|download[A-Z]\w*\(|register[A-Z]\w*Account\()")
BG = re.compile(r"Dispatchers\.(IO|Default)|\bthread\s*[({]|Thread\s*[({]|\.submit\s*[({]|executor|Executors|"
                r"suspend fun|runInterruptible|doWork|WorkManager|\basync\(Dispatchers|flowOn")

def strip_strings(line, state):
    out = []; i = 0
    while i < len(line):
        c = line[i]
        if state['str']:
            if line.startswith('"""', i) and state['raw']:
                state['str'] = False; state['raw'] = False; i += 3; continue
            if c == '\\' and not state['raw']: i += 2; continue
            if c == '"' and not state['raw']: state['str'] = False; i += 1; continue
            if line.startswith('${', i):
                # template: keep braces balanced by skipping to matching }
                depth = 0; j = i + 1
                while j < len(line):
                    if line[j] == '{': depth += 1
                    elif line[j] == '}':
                        depth -= 1
                        if depth == 0: break
                    j += 1
                i = j + 1; continue
            i += 1; continue
        if line.startswith('//', i): break
        if line.startswith('"""', i): state['str'] = True; state['raw'] = True; i += 3; continue
        if c == '"': state['str'] = True; i += 1; continue
        if c == "'" :
            j = line.find("'", i + 2 if line[i+1:i+2] == '\\' else i + 1)
            i = (j + 1) if j > 0 else i + 1; continue
        out.append(c); i += 1
    if not state['raw']: state['str'] = False
    return ''.join(out)

def audit(path):
    state = {'str': False, 'raw': False}
    stack = []  # opener text per open brace
    hits = []
    for no, raw in enumerate(pathlib.Path(path).read_text().splitlines(), 1):
        code = strip_strings(raw, state)
        if SUSPECT.search(code) and not re.search(r"\bfun\s+\w", code):
            ctx = ' '.join(stack[-40:])
            if not BG.search(ctx) and not BG.search(code):
                hits.append((no, raw.strip()[:150]))
        for ch in code:
            if ch == '{': stack.append(code.strip()[:120])
            elif ch == '}' and stack: stack.pop()
    return hits

if __name__ == '__main__':
    for p in sys.argv[1:]:
        for no, line in audit(p):
            print(f"{p}:{no}: {line}")
