# QwenChat

Локальный чат с моделью Qwen2.5-0.5B-Instruct на [llama.cpp](https://github.com/ggml-org/llama.cpp) для Android. Работает офлайн после загрузки модели, без Google Services.

## Возможности

- Чат с историей: несколько диалогов, переключение и удаление, заголовок по первому вопросу.
- Модель скачивается при первом запуске из [Hugging Face](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) с докачкой и проверкой размера, дальше интернет не нужен.
- Кванты Q2_K, Q3_K_M, Q4_K_M, Q5_K_M, Q8_0 (по умолчанию Q3_K_M).
- Прикрепление DOCX, TXT и PDF: вопросы по содержимому и краткий пересказ.
- Тёмная и светлая темы, крупный шрифт, настройки контекста, числа потоков и температуры.
- Настройка mmap: отключается, если памяти не хватает.

## Установка

1. Скачайте APK из [Releases](../../releases).
2. Откройте файл на устройстве и разрешите установку из неизвестных источников.
3. В приложении выберите квантование — модель загрузится один раз.

Требования: Android 8.0 и выше, arm64-v8a, желательно 2 ГБ ОЗУ и 0,5 ГБ свободного места.

APK подписан отладочным ключом: подходит для личной установки, но не для публикации в Google Play.

## Сборка

Нужны JDK 17, Android SDK с platform 35, NDK 26.3.11579264 и CMake 3.22.1.

```bash
git clone --recursive https://github.com/gvrud/QwenChat.git
cd QwenChat
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

APK появится в `app/build/outputs/apk/debug/app-debug.apk`.

llama.cpp подключён подмодулем в `libs/llama.cpp`. Если репозиторий склонирован без `--recursive`:

```bash
git submodule update --init --recursive
```

## Структура

| Путь | Назначение |
| --- | --- |
| `app/src/main/cpp/qwen_jni.cpp` | JNI-обёртка над llama.cpp: загрузка модели, генерация, потоковая выдача токенов |
| `app/src/main/java/.../MainActivity.java` | Весь интерфейс: чат, меню, настройки, темы |
| `Llm.java` | Сборка промпта в формате ChatML и запуск генерации |
| `ModelStore.java` | Выбранный квант, настройки, каталог моделей |
| `Downloader.java` | Докачка GGUF с Hugging Face |
| `DocReader.java` | Разбор DOCX, TXT и PDF без сторонних библиотек |
| `ChatStore.java` | История чатов в `files/chats.json` |

## Ограничения

- Модель 0,5B: короткие ответы, ошибки в фактах и арифметике — это ограничение самой модели, а не приложения.
- Длинные документы обрезаются под размер контекста, остаются начало и конец.
- Поддерживается только arm64-v8a; сборки для armeabi-v7a и x86 нет.
- Сканированные PDF без текстового слоя не распознаются: OCR нет.

## Лицензия

MIT — см. [LICENSE](LICENSE). llama.cpp распространяется по MIT, модель Qwen2.5-0.5B-Instruct — по Apache 2.0.
