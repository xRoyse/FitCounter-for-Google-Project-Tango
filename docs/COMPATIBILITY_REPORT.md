# Совместимость FitCounter

## Исходный проект и устройство

Полностью прочитано ТЗ «Учебная_практика_по_ПМ01.docx» с рабочего стола OneDrive. Требования к Python, настольной веб-камере и файлу MediaPipe адаптированы к Java и Android. Исходный шаблон с minSdk 23 собрался на AGP 9.4.1, Gradle 9.6.0 и JBR 25. Установлены платформы Android 19 и 37 и Build Tools 36.0.0.

Во время реализации ADB обнаружил Project Tango Tablet Development Kit (`yellowstone`), Android 4.4.2, API 19, NVIDIA Tegra, четыре ядра ARMv7 с NEON, ABI `armeabi-v7a` и `armeabi`. Пакет `com.google.tango` имеет версию 1.47 и versionCode 190012533. NVIDIA Camera HAL сообщает о двух задних и одной передней камере. Обычный Android Camera API вернул NV21 и прошёл проверку повторного открытия с PoseNet. Поэтому приложение использует стандартный API и не требует запуска или установки Tango Service.

## RGB-камера

Android Camera API предоставляет предварительный просмотр и буферизованные кадры NV21 на API 19. Это основной источник. Реальная доступность обычного цветного сенсора зависит от HAL прошивки Tango. В архивном hello_video SDK 1.55 (minSdk 19) сервис открывает TANGO_CAMERA_COLOR, сигнализирует о новом кадре и обновляет GL_TEXTURE_EXTERNAL_OES. Архивный JAR также предоставляет experimentalConnectOnFrameListener с TangoImageBuffer (ширина, высота, stride, формат, время и ByteBuffer). Буфер надо копировать до возврата callback. Для этого режима нужны совместимые JAR и Tango Service. Сервис должен отключаться после освобождения цветной камеры. Камеры глубины и отслеживания приложению не нужны.

Источники: [Android Camera](https://developer.android.com/reference/android/hardware/Camera), [hello_video](https://github.com/googlearchive/tango-examples-java/tree/5e727dd818e32e8cc6293f7cb02358312c45e03e/java_basic_examples/hello_video), [архив SDK](https://github.com/googlearchive/tango-examples-java/tree/5e727dd818e32e8cc6293f7cb02358312c45e03e/TangoReleaseLibs).

## Сравнение CPU-подходов

| Подход | Минимальная платформа и операции | Веса и ограничения |
| --- | --- | --- |
| MoveNet Lightning INT8, TFLite 2.16.1 | Манифест AAR объявляет API 19, ELF-заметка ARMv7 указывает API 21. У 2.14.0 эта заметка указывает API 26. Нужна сборка под API 19. Регистр 2.16.1 поддерживает все 19 операций графа, включая RESIZE_BILINEAR v3, GATHER_ND и INT8 Conv/Depthwise v3. | Официальные веса Apache 2.0, около 2,9 МБ, RGB UINT8 [1,192,192,3], FLOAT32 [1,1,17,3]. INT8 и FP16 дали координаты на реальной фотографии на Windows в LiteRT 2.1.2. Это не тест Android. |
| PoseNet MobileNetV1, TFLite 1.13.1 | В AAR есть ARMv7 и x86, в ARMv7 ELF есть DT_HASH и стандартные библиотеки libc/liblog/libdl/libm. Веса используют CONV_2D v1 и DEPTHWISE_CONV_2D v1. 1.13.1 не поддерживает MoveNet, а 1.15.0 и 2.0.0 ещё не поддерживают RESIZE_BILINEAR v3. | Официальные веса около 13,3 МБ, вход RGB FLOAT32 [1,257,257,3], тепловые карты [1,9,9,17] и смещения [1,9,9,34]. Нужен отдельный декодер. Больший вход и float-вычисления могут быть медленнее. |
| ncnn CPU | Историческая сборка с NDK позволяет явно задать Android 19 для ARMv7 NEON. Современные готовые архивы могут иметь более высокий минимум. Нужны конвертация и проверка всех операций графа, собственный JNI. | BSD-3-Clause для движка. Лицензия весов проверяется отдельно. Производительность целевого устройства неизвестна. |
| MediaPipe Tasks Pose Landmarker | Официальный Android-пример требует API 24. | Имеет 33 точки и использует файл .task из исходного ТЗ. Для API 19 использовать другой стек. |

Источники: [MoveNet](https://www.kaggle.com/models/google/movenet), [операции TFLite 2.16.1](https://github.com/tensorflow/tensorflow/blob/v2.16.1/tensorflow/lite/core/kernels/register.cc), [PoseNet](https://github.com/tensorflow/examples/blob/master/lite/examples/pose_estimation/android/app/download.gradle), [ncnn](https://github.com/Tencent/ncnn/wiki/how-to-build/533fc989f0e34dc30484353087fc035dfc4c1909), [MediaPipe](https://github.com/google-ai-edge/mediapipe-samples/tree/main/examples/pose_landmarker/android), [NDK](https://developer.android.com/ndk/downloads/revision_history).

Основной движок — MoveNet INT8 с нативной сборкой TFLite под API 19. В готовых ELF для ARMv7 и x86 проверены Android ABI note 19, таблица DT_HASH и отсутствие нестандартных динамических библиотек. Резервный движок — PoseNet и TFLite 1.13.1.

## Приёмочные проверки

- APK содержит MoveNet Lightning INT8 v4, библиотеки для `armeabi-v7a` и `x86`, minSdk 19 и подпись APK V1. Единственное разрешение приложения — CAMERA. Сборка, lint и девять JVM-тестов прошли.
- Физический Tango, Android 4.4.2 ARMv7. Итоговый APK установлен. Прошли три теста навигации, SQLite и распознавания тела на фотографии. Для 30 повторных вызовов MoveNet на реальной фотографии после одного прогрева время вызова модели с копированием тензоров составило 91/133 мс на медиане и 95-м перцентиле. В предыдущем прогоне той же нативной библиотеки были получены 77/84 мс.
- Эмулятор Android 4.4.2 x86. В авиарежиме прошли пять инструментальных тестов, включая передачу 60 кадров NV21 в MoveNet и повторное открытие камеры. В замере 640×480 частота обработки была 2,78 кадра/с, время вызова модели 346/366 мс на медиане и 95-м перцентиле. От callback Camera до результата прошло 368/383 мс. При быстром повороте прежняя версия столкнулась с SIGFPE в эмулированной камере. После явного выбора поддерживаемой частоты кадров и короткой задержки перед открытием камера осталась доступной после четырёх поворотов и возврата из фона. [Код эмулированной камеры AOSP](https://android.googlesource.com/platform/development/+/50a86be/tools/emulator/system/camera/EmulatedFakeCameraDevice.cpp).
- После первого успешного прогона камеры с PoseNet CameraService физического планшета оставил старые Camera2Client в состоянии DISCONNECTED и отклонил новые подключения. В приложении освобождение камеры теперь завершается до освобождения SurfaceTexture.
  При следующем подключении CameraService восстановился. На Tango прошли все шесть инструментальных тестов, включая цепочку задняя (ID 0) → передняя (ID 2) → задняя и поток через MoveNet. В этом прогоне обработка 640×480 составила 8,08 кадра/с, inference — 85/115 мс по медиане и 95-му перцентилю. Живые подходы с упражнениями отдельно не проверялись.
