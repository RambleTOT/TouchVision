import os
import hashlib
import torch
import torch.hub
from scipy.io import wavfile
import numpy as np
import warnings

# Отключаем предупреждения от torch.hub
warnings.filterwarnings("ignore", category=UserWarning)

class SpeechGenerator:
    def __init__(self, cache_dir, device="cpu"):
        self.device = torch.device(device)
        self.cache_dir = cache_dir
        os.makedirs(self.cache_dir, exist_ok=True)

        # Предварительно загруженные модели (без omegaconf)
        self.model = None
        self.sample_rate = 24000
        self.speaker = 'xenia'  # Русский голос по умолчанию

    def _load_model(self):
        if self.model is not None:
            return

        try:
            # Альтернативный способ загрузки без omegaconf
            model = torch.jit.load(os.path.join(os.path.dirname(__file__), 'silero_model.pt'))
            model.to(self.device)
            self.model = model
        except Exception as e:
            raise RuntimeError(f"Model loading failed: {str(e)}")

    def generate(self, text):
        cache_file = os.path.join(self.cache_dir, hashlib.md5(text.encode()).hexdigest() + ".wav")

        if os.path.exists(cache_file):
            return cache_file

        self._load_model()

        try:
            # Упрощенный синтез речи
            audio = self.model.apply_tts(
                texts=[text],
                speaker=self.speaker,
                sample_rate=self.sample_rate
            )[0]

            # Нормализация и сохранение
            audio = (audio / torch.max(torch.abs(audio))) * 32767
            audio = audio.short().cpu().numpy()

            wavfile.write(cache_file, self.sample_rate, audio)
            return cache_file
        except Exception as e:
            raise RuntimeError(f"Speech generation failed: {str(e)}")