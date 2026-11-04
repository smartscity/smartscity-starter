"""Guard comparative reporting against omitted failures, missing cases and source drift."""
import json
from pathlib import Path
import tempfile
import unittest

from report import write_report
from run import host_issues, parse_host_state


class ComparisonReportTest(unittest.TestCase):
    def render(self, mutate=lambda result: None):
        result = {'status': 'MEASURED', 'sourceUnchanged': True, 'errors': [], 'jmh': [
            {'profile': p, 'threads': t, 'implementation': i, 'idsPerSecond': n,
             'timeoutsPerSecond': 7 if i == 'smartscity' else 0,
             'rejectionsPerSecond': 9 if i == 'baidu-cached' else 0}
            for p in ('standard', 'high-throughput') for t in (1, 4, 8)
            for i, n in (('smartscity', 200), ('baidu-cached', 100), ('baidu-default', 5))],
            'latencyProbes': [{'profile': 'high-throughput', 'implementation': 'baidu-cached',
                              'lateSuccessfulIds': 1234567, 'timeouts': 0}]}
        mutate(result)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            original = json.dumps(result)
            (path / 'result.json').write_text(original)
            console = write_report(path)
            self.assertEqual((path / 'result.json').read_text(), original)
            return console, (path / 'benchmark-report.md').read_text()

    def test_all_implementations_and_late_successes_remain_visible(self):
        console, report = self.render()
        self.assertIn('18/18 scenarios', console)
        self.assertIn('2.00x', console)
        self.assertIn('Baidu Default', console)
        self.assertIn('1,234,567', report)
        self.assertIn('7.000', report)
        self.assertIn('9.000', report)
        self.assertNotIn('INCOMPLETE / NOT_VERIFIED', report)

    def test_missing_case_is_not_complete(self):
        console, report = self.render(lambda result: result['jmh'].pop())
        self.assertIn('17/18 scenarios', console)
        self.assertIn('INCOMPLETE / NOT_VERIFIED', report)
        self.assertIn('NOT_RECORDED', report)

    def test_source_drift_is_not_upgraded(self):
        _, report = self.render(lambda result: result.update(
            status='NOT_COMPARABLE', sourceUnchanged=False))
        self.assertIn('NOT_COMPARABLE', report)
        self.assertIn('INCOMPLETE / NOT_VERIFIED', report)

    def test_duplicate_case_is_not_complete(self):
        _, report = self.render(lambda result: result['jmh'].append(result['jmh'][0]))
        self.assertIn('Duplicate scenarios', report)
        self.assertIn('INCOMPLETE / NOT_VERIFIED', report)


class HostObservationTest(unittest.TestCase):
    def test_last_limit_is_used_and_power_is_parsed(self):
        state = parse_host_state({'thermal': 'CPU_Speed_Limit = 100\nCPU_Speed_Limit = 40\n'
                                  'CPU_Scheduler_Limit = 100\nCPU_Available_CPUs = 12',
                                  'logicalCpus': 12,
                                  'power': "Now drawing from 'Battery Power'"})
        self.assertEqual(state['cpuSpeedLimit'], 40)
        self.assertEqual(state['powerSource'], 'Battery Power')
        self.assertEqual(host_issues([state]), ['Observed cpuSpeedLimit=40%'])

    def test_missing_readings_are_unknown_not_full_speed(self):
        state = parse_host_state({'thermal': 'No thermal warning level has been recorded'})
        self.assertNotIn('cpuSpeedLimit', state)
        self.assertNotIn('powerSource', state)
        self.assertEqual(host_issues([state]), [])

    def test_power_change_and_scheduler_limits_invalidate_comparison(self):
        issues = host_issues([{'powerSource': 'Battery Power', 'cpuSpeedLimit': 100},
                              {'powerSource': 'AC Power', 'cpuSchedulerLimit': 50,
                               'availableCpus': 6, 'logicalCpus': 12}])
        self.assertIn('Power source changed during measurement', issues)
        self.assertIn('Observed cpuSchedulerLimit=50%', issues)
        self.assertIn('Observed 6/12 available CPUs', issues)

    def test_high_load_alone_does_not_invent_a_cpu_limit(self):
        self.assertEqual(host_issues([{'loadAverage': [40, 30, 20], 'cpuSpeedLimit': 100}]), [])

    def test_report_preserves_environment_invalidation_with_unchanged_source(self):
        _, report = ComparisonReportTest().render(lambda result: result.update(
            status='NOT_COMPARABLE', environmentIssues=['Observed cpuSpeedLimit=40%']))
        self.assertIn('Source fingerprint: UNCHANGED', report)
        self.assertIn('Environment invalidates performance comparison', report)
        self.assertNotIn('2.00x', report)
        self.assertIn('INCOMPLETE / NOT_VERIFIED', report)


if __name__ == '__main__':
    unittest.main()
