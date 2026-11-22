# FitCounter

Приложение для Android 4.4.2 (API 19), которое считает отжимания и приседания по видео цветной камеры. Интерфейс написан на Java и XML. Модель находится в APK, вычисления выполняются локально, история хранится в SQLite. В приложении нет разрешения INTERNET.

С версии 1.1 новый дизайн включён в приложение. На широком экране Tango камера и управление подходом находятся рядом. На узком экране блоки располагаются друг под другом. [Интерактивный прототип](docs/fitcounter-redesign.html) показывает пять визуальных состояний.

## Сборка и установка

Нужны Gradle Wrapper 9.6.0, AGP 9.4.1, Android SDK 37, Build Tools 36.0.0 и JDK 25 для Gradle. Подходит встроенный JBR 25 из Android Studio. Установите путь к SDK через Android Studio или файл local.properties.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.fitcounter/.MainActivity
```

При нескольких подключённых устройствах используйте `adb -s <serial>`. Для инструментальных тестов на API 19 подходят физическое устройство или x86 AVD с включённой камерой.

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.example.fitcounter.test/androidx.test.runner.AndroidJUnitRunner
```

Для проверки модели и счётчика по локальным видео используйте сценарии из [технического отчёта](docs/POSE_FIX_REPORT.md).

## Использование

1. Выберите упражнение и поставьте устройство неподвижно. Для отжиманий покажите камере тело сбоку, включая плечо, локоть, запястье и бедро. Для приседаний встаньте сбоку или вполоборота и оставьте в кадре ноги целиком.
2. Нажмите **Начать**. Дождитесь, когда приложение распознает исходное положение. Выполните движение вниз и вернитесь наверх. Делайте движение плавно и ненадолго задерживайтесь в крайних положениях, чтобы модель успела подтвердить фазу. При потере нужных суставов снова займите исходное положение. Пороги можно подстроить между подходами.
3. Нажмите **Завершить**, чтобы сохранить подход с ненулевым результатом. **Сбросить** обнуляет текущий подход. В календаре отмечены дни с тренировками, а статистика показывает суммы за неделю с понедельника и за текущий месяц.

Если доступны обе камеры, кнопка **Переключить камеру** меняет переднюю и заднюю камеры. Во время подхода счёт сохраняется. После переключения займите исходное положение для следующего повторения.

## Движки позы

- **MoveNet Lightning INT8 v4** выбран по умолчанию. Он использует TensorFlow Lite 2.16.1, собранный из исходников через CMake 3.22.1 и NDK r25c. ABI `armeabi-v7a` и `x86` целятся в API 19. Исходники JNI и скрипт сборки находятся в проекте. SHA-256 модели `cd7cc22fa946e5d146a7b98d496853e1923e22828d3972d579973f27f91bb105`. [Вес и лицензия MoveNet](https://www.kaggle.com/models/google/movenet), [исходники TensorFlow Lite 2.16.1](https://github.com/tensorflow/tensorflow/tree/v2.16.1/tensorflow/lite).
- **PoseNet MobileNetV1** доступен через `-PposeBackend=posenet` и использует `org.tensorflow:tensorflow-lite:1.13.1`. SHA-256 модели `7bcc1efa3010de45640406a4ce0acc2ef89a742021f11adacdca2fd6e494fa07`. [Источник модели PoseNet](https://github.com/tensorflow/examples/blob/master/lite/examples/pose_estimation/android/app/download.gradle).
- Для тестов используются JUnit 4.13.2, AndroidX Test Runner 1.5.2 и AndroidX Test Ext JUnit 1.1.5. Фотография отжимания есть только в APK тестов. Фото Sgt. 1st Class Alicia Lacy, U.S. Army, public domain, пропорционально уменьшено до ширины 640 пикселей. [Источник тестовой фотографии DVIDS](https://www.dvidshub.net/image/1651694/camp-mabry-graduates-first-class-army-master-fitness-trainers).

## Пересборка нативной библиотеки

Для обычной сборки APK используйте готовые библиотеки проекта. Для их воспроизведения распакуйте исходники TensorFlow v2.16.1, NDK r25c и Android SDK CMake 3.22.1 с Ninja. При необходимости передайте скрипту локальные исходники зависимостей, чтобы вложенные сборки CMake использовали заданные ревизии.

| Аргумент | Исходники |
| --- | --- |
| `-MlDtypesRoot` | [ml_dtypes 780b6d0](https://github.com/jax-ml/ml_dtypes/tree/780b6d0ee01ffbfac45f7ec5418bc08f2b166483) |
| `-PthreadpoolRoot` | [pthreadpool 4fe0e1e](https://github.com/Maratyszcza/pthreadpool/tree/4fe0e1e183925bf8cfa6aae24237e724a96479b8) |
| `-FxdivRoot` | [FXdiv 63058ef](https://github.com/Maratyszcza/FXdiv/tree/63058eff77e11aa15bf531df5dd34395ec3017c8) |

```powershell
.\tools\build-movenet.ps1 -TensorFlowRoot <tf> -Ndk <ndk> -CmakeBin <cmake-bin> -MlDtypesRoot <ml-dtypes> -PthreadpoolRoot <pthreadpool> -FxdivRoot <fxdiv>
.\gradlew.bat -PposeBackend=movenet :app:assembleDebug
```

На проверенной прошивке Tango FitCounter получает цветной поток через API Android Camera. Версия устройства, проверенные операции и измерения приведены в [отчёте о совместимости](docs/COMPATIBILITY_REPORT.md). Статусы работ перечислены в [TASK_PROGRESS.md](TASK_PROGRESS.md).
