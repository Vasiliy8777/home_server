from contextlib import contextmanager
from types import SimpleNamespace
import unittest
from music_runtime_fixes import guard_context, guard_job, honor_disabled_planning, include_failure_details


class OffloadRecoveryTest(unittest.TestCase):
    def test_failure_during_gpu_transfer_is_cleaned_up(self):
        state = SimpleNamespace(on_gpu=False, cleaned=0)
        @contextmanager
        def load(self):
            self.on_gpu = True
            raise MemoryError('simulated CUDA OOM during transfer')
            yield
        def recover(self):
            self.on_gpu = False
            self.cleaned += 1
        with self.assertRaises(MemoryError):
            with guard_context(load, recover)(state):
                self.fail('failed transfer must not enter generation')
        self.assertFalse(state.on_gpu)
        self.assertEqual(state.cleaned, 1)

    def test_nested_context_is_not_prematurely_offloaded(self):
        state = SimpleNamespace(on_gpu=False, recovered=0)
        @contextmanager
        def load(self):
            if self.on_gpu:
                yield
                return
            self.on_gpu = True
            try:
                yield
            finally:
                self.on_gpu = False
        def recover(self):
            self.recovered += 1
        guarded = guard_context(load, recover)
        with guarded(state):
            with guarded(state):
                self.assertTrue(state.on_gpu)
            self.assertTrue(state.on_gpu)
        self.assertFalse(state.on_gpu)
        self.assertEqual(state.recovered, 0)

    def test_job_cleans_up_even_when_generation_raises(self):
        calls = []
        def job(**kwargs):
            calls.append('run')
            raise RuntimeError('OOM')
        with self.assertRaises(RuntimeError):
            guard_job(job, lambda kwargs: calls.append('clean'))(selected_handler='model')
        self.assertEqual(calls, ['clean', 'run', 'clean'])

    def test_disabled_planning_does_not_run_hidden_metadata_lm(self):
        for thinking, expected in [(False, False), (True, True)]:
            params = SimpleNamespace(task_type='text2music', thinking=thinking, use_cot_caption=False,
                                     use_cot_language=False, use_cot_metas=True)
            honor_disabled_planning(lambda **kwargs: None)(params=params)
            self.assertEqual(params.use_cot_metas, expected)

    def test_error_comes_from_its_own_job_not_global_log(self):
        store = {'failed': SimpleNamespace(error='CUDA out of memory')}
        rows = include_failure_details(lambda **kwargs: [{'task_id': 'failed', 'status': 2}])(store=store)
        self.assertEqual(rows[0]['error'], 'CUDA out of memory')


if __name__ == '__main__':
    unittest.main()
