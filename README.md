# Hunger Games

Основа мода Hunger Games для **Minecraft 1.21.1 / NeoForge 21.1.259**.
Пакет и Maven group: `ru.studhub.hungergames`, идентификатор мода: `hungergames`.
Точка входа подготовлена для разработки игровой логики.

## Требования

- JDK 21, включая Gradle JVM в настройках IDE.
- IntelliJ IDEA, Eclipse или VS Code с поддержкой Java и Gradle.

Откройте корень проекта как Gradle-проект и дождитесь загрузки зависимостей.
Используйте Gradle Wrapper из проекта: устанавливать Gradle отдельно не требуется.

## Сборка и запуск

Команды для PowerShell из корня проекта:

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runData
```

- `build` — собирает мод в `build/libs/hungergames-1.0.0.jar`.
- `runClient` — запускает клиент Minecraft с модом.
- `runServer` — запускает выделенный сервер без графического интерфейса.
  Для первого запуска ознакомьтесь с EULA Minecraft и при согласии примите её в `run/eula.txt`.
- `runData` — запускает генераторы данных; результаты сохраняются в `src/generated/resources`.
  Собственные генераторы пока не зарегистрированы.

В Linux/macOS используйте `./gradlew` вместо `.\gradlew.bat`.
Рабочая папка запусков — `run/`. Игровые файлы и результаты сборки исключены из Git.

## Структура

```text
gradle.properties                              версии и свойства мода
build.gradle                                   сборка и конфигурации запуска
src/main/java/ru/studhub/hungergames/
    HungerGames.java                           общая точка входа @Mod
src/main/templates/META-INF/neoforge.mods.toml   шаблон метаданных
src/main/resources/assets/hungergames/          клиентские ресурсы и переводы
src/generated/resources/                       результаты генерации данных
```

Имя, версия, автор, описание и лицензия задаются в `gradle.properties` и автоматически
подставляются в метаданные. При изменении `mod_id` обновите также `HungerGames.MOD_ID`
и namespace ресурсов. Код, использующий клиентские классы Minecraft, размещайте отдельно
от общей точки входа, чтобы мод загружался и на выделенном сервере.

По умолчанию используется лицензия `All Rights Reserved`.

## Документация

- [NeoForge](https://docs.neoforged.net/)
- [Parchment](https://parchmentmc.org/docs/getting-started)
