# Mini-UNA

Локальный русскоязычный голосовой помощник под Android. Работает прямо на телефоне: распознаёт команды, выполняет действия и отвечает голосом. Онлайн нужен только для сборки.

## Возможности

- Голосовые команды на русском (ввод через системное распознавание речи, озвучка через TTS)
- Открытие установленных приложений с пониманием разговорных названий: «ютуб» → YouTube, «телега» → Telegram
- Системные действия: настройки (Wi-Fi, Bluetooth, звук, экран), веб-поиск, калькулятор, заметки, таймер, громкость
- Музыка: «включи музыку» (NeonWave — плеер по умолчанию, дальше Spotify / Яндекс Музыка / системный плеер + play), пауза, следующий/предыдущий трек
- Многоступенчатые команды: «поставь таймер на 5 минут и включи музыку» выполнятся по порядку
- Контекст экрана: «что на экране» (служба специальных возможностей, только на устройстве)
- Уведомления: «прочитай уведомления», «следи за Telegram» (избранное озвучивается само)
- Глубокие действия: поиск на YouTube («включи котиков на ютубе»), отправка текста в Telegram
- Действия Назад / Домой / Недавние / Блокировка экрана через службу специальных возможностей (включается отдельно)
- Маскот-сфера на главном экране: эмоции, палитры, слежение за касанием
- Два уровня понимания команд:
  - быстрые детерминированные правила (громкость, таймер, «открой …»),
  - локальная LLM Needle 3 (свой сервер needle на устройстве, localhost HTTP) + fallback-правила

## Требования

- Android 8.0+ (minSdk 26), arm64-v8a для Needle
- JDK 17, Android SDK (compileSdk 35)

## Сборка

Обычная сборка даёт APK без Needle (только правила + fallback):

```bash
gradle assembleDebug
```

Полная сборка с Needle — так делает CI (`.github/workflows/android.yml`):

```bash
# 1. Собрать Cactus-движок (нужен для будущих экспериментов; текущий
# рантайм Needle — отдельный серверный бинарь, см. шаг 2)
git clone --depth 1 https://github.com/cactus-compute/cactus.git /tmp/cactus
cd /tmp/cactus && source ./setup && cactus build --android
mkdir -p app/src/main/jniLibs/arm64-v8a
cp android/libcactus_engine.so app/src/main/jniLibs/arm64-v8a/

# 2. Скачать веса Needle 3 и серверный бинарь needle (android-arm64).
# Приложение поднимает `needle --serve` на localhost и ходит в него по HTTP —
# так обходится требование Cactus-движка к папке-бандлу.
curl -L "https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact?download=true" \
  -o app/src/main/assets/needle3.cact
curl -L "https://huggingface.co/Cactus-Compute/needle3/resolve/main/android-arm64/needle?download=true" \
  -o app/src/main/assets/needle

# 3. Собрать APK
gradle assembleDebug
```

## APK и обновления

CI собирает подписанный `release`-APK постоянным ключом, `versionCode` растёт с номером сборки — обновления ставятся поверх, переустановка не нужна. Ключ лежит только у владельца + base64 в GitHub Secrets, в репозитории его нет.

Скачать последний зелёный APK в папку проекта (всегда один файл `Mini-UNA.apk`):

```bash
./fetch-apk.sh
```

## Структура

```
app/src/main/
  AndroidManifest.xml
  assets/needle_tools.json        # 28 function-tools для Needle
  java/com/cactus/Cactus.kt       # JNI-обёртка движка
  java/com/maxsc2/miniuna/
    MainActivity.kt               # экраны, голос, TTS
    NeedleEngine.kt               # LLM-роутер + fastPath + fallback
    LocalIntentEngine.kt          # keyword-fallback
    ToolRegistry.kt / AndroidTools.kt
    InstalledAppCatalog.kt
    SafetyPolicy.kt               # ALLOW / CONFIRM / BLOCK
    MascotView.kt                 # сфера-маскот
    AccessibilityBridgeService.kt # системные кнопки
```

## Диагностика Needle

Состояние видно на экране настроек («Модель»). Точная причина fallback пишется в logcat:

```bash
adb logcat | grep MiniUNA-Needle
```

Типичные причины: нет `needle3.cact` в APK (локальная сборка без шага 2), нет `libcactus_engine.so` (без шага 1), `nativeInit вернул 0` (память).
