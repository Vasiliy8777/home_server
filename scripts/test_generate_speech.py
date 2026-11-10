import unittest
import tempfile
from pathlib import Path
from unittest.mock import Mock
from generate_speech import split_text, prepare_clone_prompt, synthesize_part


class SpeechTextTest(unittest.TestCase):
    def test_no_words_or_punctuation_are_lost_between_parts(self):
        text = ('Привет, мир!\nВторая строка... Пауза? Да.\n' * 100) + 'Конец'
        parts = split_text(text)
        self.assertGreater(len(parts), 1)
        self.assertEqual(''.join(parts), text)
        self.assertTrue(all(len(part) <= 451 for part in parts))

    def test_unbroken_text_and_short_text(self):
        self.assertEqual(''.join(split_text('Я' * 1200)), 'Я' * 1200)
        self.assertEqual(split_text('Привет!'), ['Привет!'])
        self.assertEqual(split_text(''), [])

    def test_clone_uses_reference_transcript_and_reuses_prompt_for_new_text(self):
        model = Mock()
        model.create_voice_clone_prompt.return_value = ['voice-prompt']
        request = dict(taskType='speech_clone', referenceText='Слова из образца.', voiceOnly=False)
        with tempfile.TemporaryDirectory() as folder:
            reference = Path(folder) / 'reference.wav'
            reference.touch()
            prompt = prepare_clone_prompt(model, request, reference)
            model.create_voice_clone_prompt.assert_called_once_with(ref_audio=str(reference),
                    ref_text='Слова из образца.', x_vector_only_mode=False)
            synthesize_part(model, request, 'Совершенно новый текст.', 'Russian', prompt)
            model.generate_voice_clone.assert_called_once_with(text='Совершенно новый текст.',
                    language='Russian', voice_clone_prompt=['voice-prompt'], max_new_tokens=2048)
            model.generate_voice_design.assert_not_called()
            request['voiceOnly'] = True
            prepare_clone_prompt(model, request, reference)
            self.assertIsNone(model.create_voice_clone_prompt.call_args.kwargs['ref_text'])
            self.assertTrue(model.create_voice_clone_prompt.call_args.kwargs['x_vector_only_mode'])
            with self.assertRaises(ValueError):
                prepare_clone_prompt(model, dict(voiceOnly=False, referenceText=''), reference)
        with self.assertRaises(ValueError):
            prepare_clone_prompt(model, request, reference)


if __name__ == '__main__':
    unittest.main()
