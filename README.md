# Closed-Ended Puzzle Validator

Референтна имплементация на формален модел за описание на видео игри от тип
**затворен пъзел** (close-ended puzzle). Стажантски проект към катедра
„Софтуерни технологии", ФМИ, СУ „Св. Климент Охридски", 2026 г.

Авторът надгражда модела на Bontchev & Vassileva (2010) и го валидира чрез
описания на четири реални пъзел-игри.

## Структура

```
src/
  main/
    java/org/example/puzzle/
      model/        — domain класове (12-те елемента на модела)
      validator/    — JSON Schema + семантичен валидатор
      cli/          — команден интерфейс
    resources/
      schema/closed-puzzle.schema.json   — JSON Schema (Draft 2020-12)
      examples/                          — 4 case study описания
        memory-game.json
        matching-stars.json
        fifteen-puzzle.json
        lights-out.json
  test/
    java/org/example/puzzle/validator/
      CaseStudyTests.java                — позитивни тестове за всички 4 игри
      NegativeValidationTests.java       — 5 негативни сценария
docs/
  internship-report.docx                 — отчет (≈20 стр.)
```

## Build и тестове

```powershell
mvn test                  # 9 теста, всички трябва да минат
mvn package               # създава target/closed-puzzle-validator-1.0-SNAPSHOT.jar
```

## CLI

Build a runnable fat jar (включва всички зависимости) и го пусни директно:

```cmd
mvn -q -DskipTests package
java -jar target\closed-puzzle-validator-1.0-SNAPSHOT.jar src\main\resources\examples\memory-game.json
```

Алтернативно, без билд на jar:

```cmd
mvn -q -DskipTests compile
mvn -q exec:java -Dexec.mainClass=org.example.puzzle.cli.ValidatorCli -Dexec.args="src\main\resources\examples\memory-game.json"
```

Изход:

```
== src\main\resources\examples\memory-game.json ==
  OK — no issues.
  -> 0 error(s), 0 warning(s)
```

## Двустепенна валидация

1. **Структурна** (`SchemaValidator`) — JSON Schema Draft 2020-12 проверка
   с `networknt/json-schema-validator`.
2. **Семантична** (`SemanticValidator`):
   - cross-references (P→PT, O→OT, R→A, R.fire→E, InitDisp→O,P);
   - капацитет на позициите;
   - уникалност на идентификаторите;
   - наличие и форма на FC (decidable boolean expression);
   - откриване на „мъртви" действия и непознати таргети на събития.

## Покритие на четирите case studies

| Игра                     | Тип (таксономия)         | Покрити елементи на модела |
|--------------------------|--------------------------|----------------------------|
| Memory Game              | finding by remembering   | events с условни effects, isVisible, F |
| Matching Stars (1↔1)     | association              | drag-and-drop, capacity, containsTyped |
| 15-puzzle                | arrangement / ordering   | constraint move (manhattan), checkProperty |
| Lights Out 3×3           | action puzzle            | event с няколко target-а, side effects |

## Зависимости

* Java 21+ (тествано с Java 25), Maven 3.9+
* Jackson Databind 3.1 (`tools.jackson.core:jackson-databind`)
* networknt JSON Schema Validator 3.0
* JUnit Jupiter 6

