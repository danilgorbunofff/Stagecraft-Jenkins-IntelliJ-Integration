"""Executable spec for the console stage parser (charter section 9.3).
Run: python tools/test_parse_stages.py

Real-server cases read the Day-0 fixtures; synthetic cases cover shapes the Day-0
container did not produce (nested, parallel, post, skipped, truncated, no stages).
Port every case to the Kotlin `ConsoleStages` tests."""
import json
import os
import unittest

from parse_stages import parse_console_text, to_json

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'docs', 'fixtures')


def fixture(name):
    with open(os.path.join(FIXTURES, name), encoding='utf-8') as f:
        return f.read()


def parse(text):
    return to_json(*parse_console_text(text))


def ranges(result):
    return [(s['name'], s['firstLine'], s['lastLine']) for s in result['stages']]


def log(*lines):
    return '\n'.join(lines)


OPEN = '[Pipeline] Start of Pipeline'
END = '[Pipeline] End of Pipeline'


def stage(name, *body):
    return ['[Pipeline] stage', '[Pipeline] { (%s)' % name, *body, '[Pipeline] }', '[Pipeline] // stage']


class RealServerFixtures(unittest.TestCase):

    def test_multibranch_main_matches_recorded_parse(self):
        r = parse(fixture('08.console-main.txt'))
        recorded = json.loads(fixture('09.parse-main.json'))
        self.assertEqual(ranges(r), ranges(recorded))
        self.assertEqual([s['name'] for s in r['stages']],
                         ['Declarative: Checkout SCM', 'Checkout', 'Build', 'Deploy to staging'])
        self.assertTrue(r['stages'][0]['synthetic'])
        self.assertEqual(r['diagnostics']['stackLeftAtEnd'], 0)

    def test_multibranch_main_error_is_outside_every_stage(self):
        # Declarative prints the failure message after End of Pipeline, not inside the stage.
        d = parse(fixture('08.console-main.txt'))['diagnostics']
        self.assertEqual(d['result'], 'FAILURE')
        self.assertEqual(d['firstErrorLine'], 72)
        self.assertIsNone(d['firstErrorStage'])
        self.assertEqual(d['inferredFailedStage'],
                         {'name': 'Deploy to staging', 'basis': 'last-executed-stage'})

    def test_nosv_seed_matches_recorded_parse(self):
        r = parse(fixture('nosv.console-seed.txt'))
        self.assertEqual(ranges(r), ranges(json.loads(fixture('nosv.parse.json'))))
        self.assertEqual(r['diagnostics']['result'], 'SUCCESS')
        self.assertIsNone(r['diagnostics']['inferredFailedStage'])

    def test_freestyle_degrades_to_plain_log(self):
        r = parse(fixture('08.console-freestyle-fail.txt'))
        d = r['diagnostics']
        self.assertEqual(r['stageCount'], 0)
        self.assertFalse(d['isPipelineLog'])
        self.assertEqual(d['fallbackMode'], 'plain-log')
        self.assertEqual(d['firstErrorLine'], 8)  # "Build step 'Execute shell' marked build as failure"


class RealServerParallelFixtures(unittest.TestCase):
    """Console output captured from live builds (docs/day0-verification.md, R2/R3).

    All of these were produced by parallel-demo / parallel-nested style jobs where
    every branch frame opens before any lane body runs, so the enclosing brace is a
    sibling lane rather than the real parent.
    """

    def test_parallel_lanes_are_siblings_under_the_parallel_stage(self):
        r = parse(fixture('10.console-parallel-demo.txt'))
        self.assertEqual([s['name'] for s in r['stages']],
                         ['Setup', 'Parallel', 'Branch A', 'Branch B', 'Branch C', 'Teardown'])
        for s in r['stages']:
            if s['name'].startswith('Branch '):
                self.assertEqual(s['parent'], 'Parallel')   # not chained A -> B -> C
                self.assertEqual(s['branch'], s['name'])
                self.assertEqual(s['laneBinding'], 'name')
                self.assertFalse(s['parentUncertain'])
        self.assertEqual(r['diagnostics']['unattributedStages'], [])
        self.assertEqual(r['diagnostics']['parallelRegions'],
                         [{'firstLine': 14, 'lastLine': 51,
                           'branches': ['Branch A', 'Branch B', 'Branch C']}])

    def test_stage_nested_in_a_lane_is_not_attributed_to_a_sibling_lane(self):
        # Lane A and Lane B open before either body runs, so there is no data that
        # binds A Inner to Lane A. Attaching it to Lane B would be a fabrication.
        r = parse(fixture('11.console-parallel-nested.txt'))
        by_name = {s['name']: s for s in r['stages']}
        for lane in ('Lane A', 'Lane B'):
            self.assertEqual(by_name[lane]['parent'], 'Lane Parallel')
            self.assertEqual(by_name[lane]['branch'], lane)
        for inner in ('A Inner', 'B Inner'):
            self.assertEqual(by_name[inner]['parent'], 'Lane Parallel')
            self.assertIsNone(by_name[inner]['branch'])
            self.assertTrue(by_name[inner]['parentUncertain'])
        self.assertEqual(r['diagnostics']['unattributedStages'], ['A Inner', 'B Inner'])

    def test_sequential_lane_keeps_its_own_nesting(self):
        # A lane whose body is sequential must still nest: only one lane frame is open,
        # so the enclosing brace is unambiguous.
        text = log(OPEN, '[Pipeline] node', '[Pipeline] {',
                   '[Pipeline] stage', '[Pipeline] { (Par)',
                   '[Pipeline] parallel',
                   '[Pipeline] { (Branch: L1)',
                   '[Pipeline] stage', '[Pipeline] { (L1)',
                   '[Pipeline] stage', '[Pipeline] { (L1 Inner)',
                   'inner', '[Pipeline] }', '[Pipeline] // stage',
                   '[Pipeline] }', '[Pipeline] // stage',
                   '[Pipeline] }', '[Pipeline] // parallel',
                   '[Pipeline] }', '[Pipeline] // stage',
                   '[Pipeline] }', '[Pipeline] }', '[Pipeline] // node', END)
        by_name = {s['name']: s for s in parse(text)['stages']}
        self.assertEqual(by_name['L1']['parent'], 'Par')
        self.assertEqual(by_name['L1']['branch'], 'L1')
        self.assertEqual(by_name['L1 Inner']['parent'], 'L1')
        self.assertFalse(by_name['L1 Inner']['parentUncertain'])

    def test_real_nested_stages(self):
        r = parse(fixture('12.console-nested-stages-demo.txt'))
        by_name = {s['name']: s for s in r['stages']}
        self.assertEqual(by_name['Inner One']['parent'], 'Outer')
        self.assertEqual(by_name['Inner Two']['parent'], 'Outer')
        self.assertIsNone(by_name['After Outer']['parent'])
        self.assertEqual(r['stageCount'], 4)

    def test_real_skipped_stage_and_post_actions(self):
        r = parse(fixture('13.console-skipped-post-demo.txt'))
        by_name = {s['name']: s for s in r['stages']}
        self.assertEqual(by_name['Skipped']['skipped'], 'when conditional')
        self.assertTrue(by_name['Declarative: Post Actions']['synthetic'])
        self.assertEqual(r['diagnostics']['result'], 'FAILURE')
        self.assertEqual(r['diagnostics']['inferredFailedStage'],
                         {'name': 'Fails', 'basis': 'last-executed-stage'})
        self.assertEqual(r['diagnostics']['firstErrorLine'], 35)


class SyntheticShapes(unittest.TestCase):

    def test_nested_stage_close_does_not_truncate_outer(self):
        text = log(OPEN, '[Pipeline] node', '[Pipeline] {',
                   '[Pipeline] stage', '[Pipeline] { (Outer)',
                   *stage('Inner', 'inner line'),
                   'outer tail line',
                   '[Pipeline] }', '[Pipeline] // stage',
                   '[Pipeline] }', '[Pipeline] // node', END)
        r = parse(text)
        self.assertEqual(ranges(r), [('Outer', 5, 12), ('Inner', 7, 9)])
        self.assertEqual(r['stages'][1]['parent'], 'Outer')
        self.assertEqual(r['diagnostics']['stackLeftAtEnd'], 0)
        self.assertEqual(r['diagnostics']['strayStageClose'], 0)

    def test_parallel_stages_are_named_but_marked_interleaved(self):
        text = log(OPEN, *['[Pipeline] stage', '[Pipeline] { (Par)', '[Pipeline] parallel',
                           '[Pipeline] { (Branch: A)', '[Pipeline] { (Branch: B)',
                           '[Pipeline] stage', '[Pipeline] { (A)',
                           '[Pipeline] stage', '[Pipeline] { (B)',
                           'a-out', 'b-out',
                           '[Pipeline] }', '[Pipeline] // stage',
                           '[Pipeline] }', '[Pipeline] }', '[Pipeline] // stage',
                           '[Pipeline] }', '[Pipeline] // parallel',
                           '[Pipeline] }', '[Pipeline] // stage'], END)
        r = parse(text)
        names = [s['name'] for s in r['stages']]
        self.assertNotIn('Branch: A', names)
        self.assertEqual(names, ['Par', 'A', 'B'])
        par, a, b = r['stages']
        self.assertEqual((par['firstLine'], par['lastLine']), (3, 20))
        self.assertFalse(par['interleaved'])
        for s in (a, b):
            self.assertTrue(s['interleaved'])
            self.assertEqual((s['firstLine'], s['lastLine']), (4, 19))  # the whole parallel region
        self.assertEqual(r['diagnostics']['parallelRegions'],
                         [{'firstLine': 4, 'lastLine': 19, 'branches': ['A', 'B']}])
        self.assertEqual(r['diagnostics']['stackLeftAtEnd'], 0)

    def test_post_actions_and_skipped_stages_do_not_mislead_inference(self):
        text = log(OPEN,
                   *stage('Build', 'ok'),
                   *stage('Test', '+ ./gradlew test', 'tests failing'),
                   *stage('Publish', 'Stage "Publish" skipped due to earlier failure(s)'),
                   *stage('Declarative: Post Actions', 'cleaning up'),
                   END, 'ERROR: script returned exit code 1', 'Finished: FAILURE')
        r = parse(text)
        by_name = {s['name']: s for s in r['stages']}
        self.assertEqual(by_name['Publish']['skipped'], 'earlier failure(s)')
        self.assertTrue(by_name['Declarative: Post Actions']['synthetic'])
        self.assertEqual(r['diagnostics']['inferredFailedStage'],
                         {'name': 'Test', 'basis': 'last-executed-stage'})

    def test_error_inside_stage_wins_over_heuristic(self):
        text = log(OPEN,
                   *stage('Compile', '[ERROR] /ws/src/main/java/a/B.java:[12,5] cannot find symbol'),
                   *stage('Declarative: Post Actions', 'ERROR: post step failed too'),
                   END, 'Finished: FAILURE')
        d = parse(text)['diagnostics']
        self.assertEqual(d['firstErrorStage'], 'Compile')
        self.assertEqual(d['inferredFailedStage'],
                         {'name': 'Compile', 'basis': 'first-error-inside-stage'})

    def test_truncated_log_is_still_a_pipeline(self):
        # The head-only fetch (charter 9.7) produces exactly this: no End marker.
        text = log(OPEN, '[Pipeline] node', '[Pipeline] {', *stage('Build', 'x'),
                   '[Pipeline] stage', '[Pipeline] { (Deploy)', 'still going')
        r = parse(text)
        d = r['diagnostics']
        self.assertTrue(d['isPipelineLog'])
        self.assertFalse(d['endMarkerSeen'])
        self.assertEqual(d['stackLeftAtEnd'], 2)  # node block + open Deploy stage
        self.assertTrue(r['stages'][-1]['open'])
        self.assertEqual(r['stages'][-1]['lastLine'], 11)

    def test_pipeline_without_stages(self):
        text = log(OPEN, '[Pipeline] node', '[Pipeline] {', '[Pipeline] sh', '+ make',
                   '[Pipeline] }', '[Pipeline] // node', END, 'Finished: SUCCESS')
        r = parse(text)
        self.assertEqual(r['stageCount'], 0)
        self.assertEqual(r['diagnostics']['fallbackMode'], 'pipeline-without-stages')

    def test_ansi_and_crlf_do_not_hide_markers(self):
        text = '\r\n'.join([OPEN, '\x1b[0m[Pipeline] stage', '[Pipeline] { (Build)\x1b[0m',
                            'x', '[Pipeline] }', '[Pipeline] // stage', END])
        self.assertEqual(ranges(parse(text)), [('Build', 3, 5)])


if __name__ == '__main__':
    unittest.main(verbosity=2)
