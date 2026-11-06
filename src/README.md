# Исходный код

- `mobile/android` — Android-приложение «Реплика» (Kotlin, Jetpack Compose, Gradle).

## Сборка

Из корня репозитория скачать движок и модели (≈1 ГБ):

```bash
sh tools/fetch_models.sh
```

Собрать APK (JDK 17+, Android SDK 36):

```bash
cd src/mobile/android && ./gradlew :app:assembleRelease
```

Готовый файл: `src/mobile/android/app/build/outputs/apk/release/app-release.apk`.
