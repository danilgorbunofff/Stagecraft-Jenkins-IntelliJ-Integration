"""Stagecraft: segment a Jenkins console log into stages using only the console
text (charter section 9.3). Reference implementation and executable spec for
the Kotlin `ConsoleStages`; see test_parse_stages.py for the cases it must pass.

What console text can and cannot tell you:

- Stage names and line ranges for sequential and nested stages: exact.
- Stages inside `parallel`: names only. Since JEP-210 the plain console carries
  no per-branch prefix, so interleaved output cannot be attributed to a branch.
  Such stages get the whole parallel region as their range and
  `interleaved: true`.
- Which lane a stage inside a parallel region belongs to: not recoverable, and
  not guessed. All `Branch: <label>` frames are opened before any lane body
  runs, and each lane's frames stay open while the other lanes run, so the
  enclosing brace is usually a sibling lane rather than the parent. Lanes are
  attributed from the `Branch:` declarations (by name, else by declaration
  order and flagged `laneBinding: positional`); anything deeper is reported
  against the parallel stage with `parentUncertain: true` instead of being
  fabricated into a sibling lane.
- Stage status: not present. Skipped stages are recognised from Declarative's
  `Stage "X" skipped due to ...` line; the failed stage is *inferred* and
  labelled with the basis of the inference. Never present it as fact.
"""
import json
import re
import sys

ANSI = re.compile(r'\x1b\[[0-9;]*m')
MARKER = re.compile(r'^\[Pipeline\] ')
NAMED_OPEN = re.compile(r'^\[Pipeline\] \{\s*\((.*)\)\s*$')  # stage or parallel branch body
BLOCK_OPEN = re.compile(r'^\[Pipeline\] \{$')
BLOCK_CLOSE = re.compile(r'^\[Pipeline\] \}$')
STAGE_CLOSE = re.compile(r'^\[Pipeline\] // stage$')
PARALLEL_OPEN = re.compile(r'^\[Pipeline\] parallel$')
PARALLEL_CLOSE = re.compile(r'^\[Pipeline\] // parallel$')
END = re.compile(r'^\[Pipeline\] End of Pipeline$')
SKIPPED = re.compile(r'^Stage "(.+)" skipped due to (.+)$')
FINISHED = re.compile(r'^Finished: ([A-Z_]+)$')
BRANCH_PREFIX = 'Branch: '
SYNTHETIC_PREFIX = 'Declarative: '
POST_ACTIONS = 'Declarative: Post Actions'

# Lines that mark the first real error. Order does not matter; the earliest line wins.
ERROR_LINE = re.compile(
    r'^(?:ERROR: |FATAL: |\[ERROR\] |FAILURE: |BUILD FAILED\b|Exception in thread )'
    r"|^Build step '.+' marked build as failure$"
)


def parse_console_text(text):
    """Return (stages, diagnostics). Stages are in document order."""
    stages = []        # every stage frame ever opened, in open order
    stack = []         # open frames: {'kind': 'stage'|'branch'|'block', ...}
    regions = []       # open parallel regions: {'first', 'base', 'stages', 'branches'}
    closed_regions = []
    end_line = None
    result = None
    first_error = None
    stray_stage_close = 0
    last_popped = None
    is_pipeline = False

    for i, raw in enumerate(text.splitlines(), start=1):
        line = ANSI.sub('', raw).rstrip('\r')
        if MARKER.match(line):
            is_pipeline = True
        if first_error is None and ERROR_LINE.match(line):
            first_error = i

        m = NAMED_OPEN.match(line)
        if m:
            name = m.group(1)
            if name.startswith(BRANCH_PREFIX):
                stack.append({'kind': 'branch', 'first': i,
                              'label': name[len(BRANCH_PREFIX):]})
                if regions:
                    regions[-1]['branches'].append(name[len(BRANCH_PREFIX):])
                continue
            parent, placement = _place_stage(stack, regions, name)
            stage = {'name': name, 'first': i, 'last': None,
                     'parent': parent['name'] if parent else None,
                     'depth': (parent['depth'] + 1) if parent else 0,
                     'synthetic': name.startswith(SYNTHETIC_PREFIX),
                     'interleaved': bool(regions), 'skipped': None, **placement}
            stages.append(stage)
            stack.append({'kind': 'stage', 'first': i, 'stage': stage})
            if regions:
                regions[-1]['stages'].append(stage)
            continue
        if BLOCK_OPEN.match(line):
            stack.append({'kind': 'block', 'first': i})
            continue
        if BLOCK_CLOSE.match(line):
            if stack:
                frame = stack.pop()
                last_popped = frame['kind']
                # Inside a parallel region braces close in arbitrary order, so a
                # pop says nothing about *which* stage ended; the region close does.
                if frame['kind'] == 'stage' and not regions:
                    frame['stage']['last'] = i
            continue
        if STAGE_CLOSE.match(line):
            # Informational only: the preceding `}` already closed the stage.
            # It must never pop frames, or it destroys enclosing node/withEnv/stage frames.
            if last_popped != 'stage' and not regions:
                stray_stage_close += 1
            last_popped = None
            continue
        if PARALLEL_OPEN.match(line):
            regions.append({'first': i, 'base': len(stack), 'stages': [],
                            'branches': [], 'claimed': set()})
            continue
        if PARALLEL_CLOSE.match(line) and regions:
            region = regions.pop()
            del stack[region['base']:]
            for s in region['stages']:
                s['first'], s['last'] = region['first'], i
            closed_regions.append({'firstLine': region['first'], 'lastLine': i,
                                   'branches': region['branches']})
            continue
        m = SKIPPED.match(line)
        if m:
            for s in reversed(stages):
                if s['name'] == m.group(1):
                    s['skipped'] = m.group(2)
                    break
            continue
        if END.match(line):
            end_line = i
            continue
        m = FINISHED.match(line)
        if m:
            result = m.group(1)

    for s in stages:
        if s['last'] is None:  # log truncated, or build still running
            s['last'] = i
            s['open'] = True

    stages.sort(key=lambda s: s['first'])
    diagnostics = {
        'stackLeftAtEnd': len(stack),
        'endMarkerSeen': end_line is not None,
        'isPipelineLog': is_pipeline,
        'fallbackMode': ('stages' if stages else
                         'pipeline-without-stages' if is_pipeline else 'plain-log'),
        'result': result,
        'firstErrorLine': first_error,
        'firstErrorStage': _innermost_stage_at(stages, first_error),
        'inferredFailedStage': _infer_failed_stage(stages, result, first_error),
        'parallelRegions': closed_regions,
        'unattributedStages': [s['name'] for s in stages if s['parentUncertain']],
        'strayStageClose': stray_stage_close,
    }
    return stages, diagnostics


def _nearest_stage(frames):
    return next((f['stage'] for f in reversed(frames) if f['kind'] == 'stage'), None)


def _place_stage(stack, regions, name):
    """Return (parent stage or None, placement extras) for a stage frame opening now.

    Outside `parallel` the nearest open stage frame really is the parent. Inside a
    parallel region it usually is not: the lanes run concurrently, so that frame is
    a sibling lane. See the module docstring - nothing here is presented as fact.
    """
    if not regions:
        return _nearest_stage(stack), {'branch': None, 'laneBinding': None,
                                       'parentUncertain': False}
    region = regions[-1]
    owner = _nearest_stage(stack[:region['base']])
    unclaimed = [b for b in region['branches'] if b not in region['claimed']]
    open_lanes = [f for f in stack[region['base']:] if f['kind'] == 'stage']
    if name in unclaimed:
        region['claimed'].add(name)
        return owner, {'branch': name, 'laneBinding': 'name', 'parentUncertain': False}
    # Declarative emits every `Branch:` frame before any lane body runs, so while the
    # lanes are still being opened the k-th unclaimed lane is the k-th unclaimed branch.
    if unclaimed and all(f['stage']['branch'] for f in open_lanes):
        label = unclaimed[0]
        region['claimed'].add(label)
        return owner, {'branch': label, 'laneBinding': 'positional', 'parentUncertain': True}
    if len(open_lanes) == 1 and open_lanes[0]['stage']['branch']:
        lane = open_lanes[0]['stage']['branch']
        return open_lanes[0]['stage'], {'branch': lane, 'laneBinding': None,
                                        'parentUncertain': False}
    return owner, {'branch': None, 'laneBinding': None,
                   'parentUncertain': bool(open_lanes) or bool(region['branches'])}


def _innermost_stage_at(stages, line):
    if line is None:
        return None
    inside = [s for s in stages if not s['interleaved'] and s['first'] <= line <= s['last']]
    return max(inside, key=lambda s: s['depth'])['name'] if inside else None


def _infer_failed_stage(stages, result, first_error):
    """Best guess at the failed stage. Returned with its basis; it is never certain."""
    if result != 'FAILURE':
        return None
    inside = _innermost_stage_at(stages, first_error)
    # A post block runs after the failure and its own errors are usually follow-on noise.
    if inside and inside != POST_ACTIONS:
        return {'name': inside, 'basis': 'first-error-inside-stage'}
    # Declarative marks every stage after a failure as skipped "due to earlier failure(s)",
    # so the failed one is the last real stage that was not skipped.
    candidates = [s for s in stages if not s['synthetic'] and s['skipped'] is None]
    if not candidates:
        return None
    last = max(candidates, key=lambda s: s['first'])
    basis = 'last-executed-stage'
    if last['interleaved']:
        basis += ', parallel-ambiguous'
    return {'name': last['name'], 'basis': basis}


def to_json(stages, diagnostics):
    return {
        'stageCount': len(stages),
        'stages': [{'name': s['name'], 'firstLine': s['first'], 'lastLine': s['last'],
                    'logLines': s['last'] - s['first'] + 1, 'parent': s['parent'],
                    'synthetic': s['synthetic'], 'interleaved': s['interleaved'],
                    'skipped': s['skipped'], 'open': s.get('open', False),
                    'branch': s['branch'], 'laneBinding': s['laneBinding'],
                    'parentUncertain': s['parentUncertain']}
                   for s in stages],
        'diagnostics': diagnostics,
    }


def main():
    if len(sys.argv) < 2:
        print("usage: parse_stages.py <consoleText file> [out json]")
        sys.exit(2)
    with open(sys.argv[1], encoding='utf-8', errors='replace') as f:
        text = f.read()
    out = json.dumps(to_json(*parse_console_text(text)), indent=2)
    print(out)
    if len(sys.argv) > 2:
        with open(sys.argv[2], 'w', encoding='utf-8', newline='\n') as f:
            f.write(out + '\n')


if __name__ == '__main__':
    main()
