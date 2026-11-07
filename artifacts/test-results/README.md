# Артефакты тестирования

- `results.csv` — все прогоны с замерами (время в миллисекундах, RTF = время до расшифровки / длина аудио).
- `exports/device_*.json|txt` — файлы, сохранённые кнопками «Сохранить JSON/TXT» в приложении на эмуляторе.
- `exports/mac_*.json` — тот же конвейер на Mac через Python sherpa-onnx (`tools/mac_check.py`).
- Эталоны лежат в `testdata/ref_*.json`, проверка — `python3 tools/eval.py <экспорт> <эталон> --duration-ms N`.
- `live/` — живая запись: метрики, черновик и окончательная расшифровка прогонов на 48 с и 15 мин 46 с, строки `LIVE_METRICS` из logcat.
- `preproc_ab*.json` — A/B предобработки звука (`tools/preproc_ab.py`), сводка в `docs/TEST_RESULTS.md`.
