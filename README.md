# Hunger Games

Серверный мод Hunger Games для **Minecraft 1.21.1 / NeoForge 21.1.244+**.
Сборка использует API NeoForge 21.1.250.
Пакет и Maven group: `ru.studhub.hungergames`, идентификатор мода: `hungergames`.
Ежедневные бои на отдельной карте: набор, ожидание, сундуки с лутом, сужение границы,
наблюдение, возврат игроков и случайные награды. Установка Hunger Games на клиент не требуется.

Обычный матч рассчитан на 16–32 участника. Сундуки заполняются при загрузке чанков:
нетронутые сохраняют содержимое между матчами, открытые обновляются в следующем бою.
Для отдельного события оператор может задать лимиты: `/hg create_event <min> <max>`, где `2 ≤ min ≤ max ≤ 32`.

Перед первым событием установите карту и заполните JSON-пулы по [инструкции настройки](docs/CONFIGURATION.md).
Для сборки с TaCZ подготовлен [пул оружия с патронами, брони и еды](config-presets/hungergames/README.md).
Состояние выполнения работ отмечено в [плане реализации](IMPLEMENTATION_PLAN.md).

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
.\gradlew.bat runGameTestServer
```

- `build` — собирает мод в `build/libs/hungergames-1.4.0.jar`.
- `runClient` — запускает клиент Minecraft с модом.
- `runServer` — запускает выделенный сервер без графического интерфейса.
  Для первого запуска ознакомьтесь с EULA Minecraft и при согласии примите её в `run/eula.txt`.
- `runData` — запускает генераторы данных; результаты сохраняются в `src/generated/resources`.
  Собственные генераторы пока не зарегистрированы.
- `runGameTestServer` — проверяет игровой цикл на отдельном тестовом сервере в `build/gametest`.

В Linux/macOS используйте `./gradlew` вместо `.\gradlew.bat`.
Рабочая папка запусков — `run/`. Игровые файлы и результаты сборки исключены из Git.

## Структура

```text
gradle.properties                              версии и свойства мода
build.gradle                                   сборка и конфигурации запуска
src/main/java/ru/studhub/hungergames/
    HungerGames.java                           точка входа @Mod
    event/, arena/, player/                    события, карта и состояние игроков
    command/, config/, loot/, reward/           команды, настройки, лут и награды
    storage/, compat/, mixin/                   журнал возвратов, Curios и защита карты
src/main/templates/META-INF/neoforge.mods.toml   шаблон метаданных
src/main/resources/assets/hungergames/          клиентские ресурсы и переводы
src/generated/resources/                       результаты генерации данных
src/test/java/                                 модульные тесты
src/gameTest/                                  отдельный мод интеграционных тестов
src/dedicatedTest/                             проверка на готовой карте выделенного сервера
```

Имя, версия, автор, описание и лицензия задаются в `gradle.properties` и автоматически
подставляются в метаданные. При изменении `mod_id` обновите также `HungerGames.MOD_ID`
и namespace ресурсов. Код, использующий клиентские классы Minecraft, размещайте отдельно
от общей точки входа, чтобы мод загружался и на выделенном сервере.

По умолчанию используется лицензия `All Rights Reserved`.

## Документация

- [Настройка карты, команды и JSON-пулы](docs/CONFIGURATION.md)
- [Проверка на предоставленном тестовом сервере](docs/TEST_SERVER.md)
- [NeoForge](https://docs.neoforged.net/)
- [Parchment](https://parchmentmc.org/docs/getting-started)
