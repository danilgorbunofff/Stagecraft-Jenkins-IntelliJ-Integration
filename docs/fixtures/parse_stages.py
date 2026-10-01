"""Stagecraft Day-0 gate, check 9: segment a Jenkins console log into stages
using only the console text (charter section 9.3, stack-based parser)."""
import re
import sys
import json

STAGE_OPEN = re.compile(r'^\[Pipeline\] \{\s*\((.*)\)\s*$')
STAGE_CLOSE = re.compile(r'^\[Pipeline\] // stage$')
BLOCK_OPEN = re.compile(r'^\[Pipeline\] \{$')
BLOCK_CLOSE = re.compile(r'^\[Pipeline\] \}$')
END = re.compile(r'^\[Pipeline\] End of Pipeline$')


def parse_console_text(text):
    """Return (stages, diagnostics). stages: name, first, last, lineCount."""
    stages = []
    stack = []
    end_seen = False
    for i, line in enumerate(text.splitlines(), start=1):
        m = STAGE_OPEN.match(line)
        if m:
            stack.append({'kind': 'stage', 'name': m.group(1), 'first': i})
            continue
        if BLOCK_OPEN.match(line):
            stack.append({'kind': 'block', 'name': None, 'first': i})
            continue
        if BLOCK_CLOSE.match(line):
            if stack:
                frame = stack.pop()
                if frame['kind'] == 'stage':
                    stages.append({'name': frame['name'],
                                   'first': frame['first'], 'last': i,
                                   'lineCount': i - frame['first'] + 1})
            continue
        if STAGE_CLOSE.match(line):
            # Defensive: a stage frame left on the stack without a matching }
            while stack:
                frame = stack.pop()
                if frame['kind'] == 'stage':
                    stages.append({'name': frame['name'],
                                   'first': frame['first'], 'last': i,
                                   'lineCount': i - frame['first'] + 1})
                    break
            continue
        if END.match(line):
            end_seen = True
    if not end_seen and not stages:
        # Not a pipeline log at all (e.g. freestyle): no stages to report.
        stages = []
    return stages, {'stackLeftAtEnd': len(stack), 'endMarkerSeen': end_seen, 'isPipelineLog': end_seen or len(stack) > 0}


def main():
    if len(sys.argv) < 2:
        print("usage: parse_stages.py <consoleText file> [out json]")
        sys.exit(2)
    text = open(sys.argv[1], encoding='utf-8', errors='replace').read()
    stages, diag = parse_console_text(text)
    result = {
        'stageCount': len(stages),
        'stages': [{'name': s['name'], 'firstLine': s['first'],
                    'lastLine': s['last'], 'logLines': s['lineCount']}
                   for s in stages],
        'diagnostics': diag,
    }
    out = json.dumps(result, indent=2)
    print(out)
    if len(sys.argv) > 2:
        open(sys.argv[2], 'w', encoding='utf-8').write(out)


if __name__ == '__main__':
    main()
