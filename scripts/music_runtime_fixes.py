"""Local ACE-Step entry point with recoverable CPU offload; no vendor files changed."""
from contextlib import contextmanager
from functools import wraps
import logging

LOG = logging.getLogger('muz_gen.runtime')


def guard_context(original, recover):
    """An OOM during __enter__ must not leave a partially transferred model on CUDA."""
    @wraps(original)
    @contextmanager
    def guarded(self, *args, **kwargs):
        entered = False
        try:
            with original(self, *args, **kwargs) as value:
                entered = True
                yield value
        finally:
            if not entered:
                try:
                    recover(self, *args, **kwargs)
                except Exception:
                    LOG.exception('Failed to recover a partially loaded model')
    return guarded


def guard_job(original, cleanup):
    @wraps(original)
    def guarded(**kwargs):
        cleanup(kwargs)
        try:
            return original(**kwargs)
        finally:
            cleanup(kwargs)
    return guarded


def honor_disabled_planning(original):
    @wraps(original)
    def generate(**kwargs):
        params = kwargs.get('params')
        if (params is not None and params.task_type == 'text2music' and not params.thinking
                and not params.use_cot_caption and not params.use_cot_language):
            params.use_cot_metas = False
        return original(**kwargs)
    return generate


def include_failure_details(original):
    @wraps(original)
    def collect(*args, **kwargs):
        rows = original(*args, **kwargs)
        store = kwargs.get('store') if 'store' in kwargs else args[2]
        for row in rows:
            if row.get('status') == 2:
                record = store.get(row['task_id'])
                error = getattr(record, 'error', None) if record else None
                if error:
                    row['error'] = str(error)[:2000]
        return rows
    return collect


def main():
    import gc
    import torch
    from acestep.handler import AceStepHandler
    from acestep.llm_inference import LLMHandler

    def recover_dit(handler, model_name):
        if not handler.offload_to_cpu:
            return
        if model_name == 'model' and not handler.offload_dit_to_cpu:
            return
        model = getattr(handler, model_name, None)
        if model is not None:
            dtype = handler._get_vae_dtype('cpu') if model_name == 'vae' else handler.dtype
            handler._recursive_to_device(model, 'cpu', dtype)
        if model_name == 'model' and hasattr(handler, 'silence_latent'):
            handler.silence_latent = handler.silence_latent.to('cpu')
        handler._release_system_memory()

    def recover_lm(handler):
        if not handler.offload_to_cpu or handler.llm_backend in ('vllm', 'mlx'):
            return
        if handler.llm is not None:
            handler.llm.to('cpu')
        gc.collect()
        if torch.cuda.is_available():
            torch.cuda.empty_cache()

    def cleanup_job(kwargs):
        # The API uses one worker: no other generation can own these models here.
        handler = kwargs.get('selected_handler')
        if handler is not None:
            for name in ('model', 'vae', 'text_encoder'):
                try:
                    recover_dit(handler, name)
                except Exception:
                    LOG.exception('Could not offload %s', name)
        lm = kwargs.get('llm_handler')
        if lm is not None:
            try:
                recover_lm(lm)
            except Exception:
                LOG.exception('Could not offload music LM')

    AceStepHandler._load_model_context = guard_context(AceStepHandler._load_model_context, recover_dit)
    LLMHandler._load_model_context = guard_context(LLMHandler._load_model_context, recover_lm)
    from acestep.api.http import query_result_service, query_result_route
    query_result_service.collect_query_results = include_failure_details(query_result_service.collect_query_results)
    query_result_route.collect_query_results = query_result_service.collect_query_results
    from acestep import api_server
    api_server.run_blocking_generate = guard_job(api_server.run_blocking_generate, cleanup_job)
    api_server.generate_music = honor_disabled_planning(api_server.generate_music)
    print('[muz_gen] Enabled offload recovery, job cleanup, and detailed task errors', flush=True)
    print('[muz_gen] CUDA allocator:', torch.cuda.get_allocator_backend(), flush=True)
    api_server.main()


if __name__ == '__main__':
    main()
