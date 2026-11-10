"""Offline, one-job Qwen3-TTS worker. Exiting releases all CPU/CUDA memory."""
import argparse
import json
import os
from pathlib import Path


def split_text(text, limit=450):
    """Keep every input character, prefer paragraph/word boundaries."""
    parts = []
    while len(text) > limit:
        cut = max(text.rfind('\n', 0, limit + 1), text.rfind('. ', 0, limit + 1),
                  text.rfind('! ', 0, limit + 1), text.rfind('? ', 0, limit + 1))
        if cut < limit // 3:
            cut = text.rfind(' ', 0, limit + 1)
        if cut <= 0:
            cut = limit
        else:
            cut += 1
        parts.append(text[:cut])
        text = text[cut:]
    if text:
        parts.append(text)
    return parts


def prepare_clone_prompt(model, request, reference):
    if not reference or not Path(reference).is_file():
        raise ValueError('A local voice reference is required')
    voice_only = request.get('voiceOnly', False)
    transcript = request.get('referenceText', '')
    if not voice_only and not transcript.strip():
        raise ValueError('A reference transcript is required in full cloning mode')
    return model.create_voice_clone_prompt(ref_audio=str(reference),
            ref_text=None if voice_only else transcript, x_vector_only_mode=voice_only)


def synthesize_part(model, request, text, language, voice_prompt=None):
    if request.get('taskType') == 'speech_clone':
        return model.generate_voice_clone(text=text, language=language,
                voice_clone_prompt=voice_prompt, max_new_tokens=2048)
    return model.generate_voice_design(text=text, language=language,
            instruct=request['description'], max_new_tokens=2048)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--request', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--reference')
    args = parser.parse_args()
    os.environ['HF_HUB_OFFLINE'] = '1'
    os.environ['TRANSFORMERS_OFFLINE'] = '1'
    os.environ['HF_HUB_DISABLE_TELEMETRY'] = '1'
    import numpy as np
    import soundfile as sf
    import torch
    from transformers import set_seed
    from qwen_tts import Qwen3TTSModel
    request = json.loads(Path(args.request).read_text(encoding='utf-8'))
    languages = dict(ru='Russian', en='English', de='German', fr='French', es='Spanish',
                     it='Italian', pt='Portuguese', zh='Chinese', ja='Japanese', ko='Korean')
    set_seed(int(request['seed']))
    model = Qwen3TTSModel.from_pretrained(args.model, device_map='cuda:0',
                                         dtype=torch.bfloat16, attn_implementation='sdpa',
                                         local_files_only=True)
    chunks = split_text(request['lyrics'])
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = []
    # Write incrementally: intermediate parts and exact text remain available for diagnosis.
    with torch.inference_mode():
        # Reuse one reference prompt so long texts preserve the same speaker across parts.
        voice_prompt = prepare_clone_prompt(model, request, args.reference) if request.get('taskType') == 'speech_clone' else None
        with sf.SoundFile(str(output), mode='w', samplerate=24000, channels=1, subtype='PCM_16', format='WAV') as combined:
            for i, text in enumerate(chunks):
                print(f'Speech part {i + 1}/{len(chunks)}', flush=True)
                wavs, rate = synthesize_part(model, request, text, languages[request['language']], voice_prompt)
                if rate != 24000 or not wavs or len(wavs[0]) == 0 or not np.isfinite(wavs[0]).all():
                    raise RuntimeError('Speech model returned invalid audio')
                # 2048 frames at 12Hz; reaching this limit risks a truncated sentence.
                if len(wavs[0]) / rate >= 169:
                    raise RuntimeError('Speech part reached generation limit; shorten the text or use a faster delivery')
                part = output.with_name(f'speech-part-{i + 1:03d}.wav')
                sf.write(str(part), wavs[0], rate, subtype='PCM_16')
                combined.write(wavs[0])
                manifest.append(dict(part=part.name, text=text, seconds=len(wavs[0]) / rate))
                output.with_name('speech-parts.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Speech completed', flush=True)


if __name__ == '__main__':
    main()
