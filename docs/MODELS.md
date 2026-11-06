# Модели

Все модели лежат в APK (`app/src/main/assets/models`), скачиваются скриптом `tools/fetch_models.sh` при сборке. В git их нет из-за размера.

| Задача | Модель | Файл в APK | Размер | Лицензия | Источник |
|---|---|---|---|---|---|
| Распознавание, русский | GigaAM v3 CTC с пунктуацией (Сбер), int8 | `asr/model.int8.onnx` | 215 МБ | MIT | sherpa-onnx `asr-models/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16` |
| Распознавание, английский | NVIDIA NeMo Parakeet TDT-CTC 110M, int8 | `asr_en/model.int8.onnx` | 126 МБ | CC-BY-4.0 | sherpa-onnx `asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8` |
| Поиск речи | Silero VAD | `diar/silero_vad.onnx` | 0,6 МБ | MIT | sherpa-onnx `asr-models/silero_vad.onnx` |
| Голосовые отпечатки | NVIDIA NeMo TitaNet-small | `diar/titanet_small.onnx` | 38 МБ | CC-BY-4.0 | sherpa-onnx `speaker-recongition-models/nemo_en_titanet_small.onnx` |
| Оформление и итоги | Qwen2.5-0.5B-Instruct q8, окно 1280 токенов | `llm/qwen2.5-0.5b-instruct-q8.task` | 521 МБ | Apache-2.0 | Hugging Face `litert-community/Qwen2.5-0.5B-Instruct` |

Движки:
- sherpa-onnx 2.0.1 (Apache-2.0), готовый AAR с GitHub, внутри ONNX Runtime; используется только arm64-v8a.
- MediaPipe LLM Inference `com.google.mediapipe:tasks-genai:0.10.35` (Apache-2.0).

Шрифты: Artegra Sans Extended (фирменный шрифт команды, как в «Поле»/«Облаке») и Inter (OFL).

## Параметры

- VAD: порог 0,5, минимальная пауза 0,25 с, минимальная фраза 0,25 с, максимальная фраза 12 с; к каждой фразе добавляется до 400 мс контекста с каждой стороны, но не больше половины паузы до соседней фразы.
- Кластеризация: средняя связь, косинусное расстояние, порог 0,6, число говорящих не задаётся.
- Склейка: соседние фразы одного говорящего с паузой < 600 мс объединяются; фрагменты < 250 мс отбрасываются.
- ASR: фрагменты длиннее 20 с режутся в самой тихой 100-мс точке последней трети окна.
- LLM: top-k 1, температура 0, seed 7; оформление пачками по 6 реплик; итоги — до 10 окон по ~1200 символов, один пример ответа в промпте, шаблон чата задаётся вручную (ChatML Qwen).
