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
      engine/       — интерпретатор: изпълнява играта по модела
      server/       — локален HTTP/JSON сървър за Unity клиента
      cli/          — команден интерфейс
    resources/
      schema/closed-puzzle.schema.json   — JSON Schema (Draft 2020-12)
      examples/                          — 4 case study описания
        memory-game.json
        matching-stars.json
        fifteen-puzzle.json
        lights-out.json
  test/
    java/org/example/puzzle/
      validator/CaseStudyTests.java      — позитивни тестове за всички 4 игри
      validator/NegativeValidationTests.java — негативни сценарии
      engine/CaseStudyPlayTests.java     — изиграва 4-те игри до край
      engine/EngineSemanticsTests.java   — семантика на изпълнението
      server/EngineServerTests.java      — протоколът по HTTP
unity/                                   — Unity 6 клиент (Assets/PuzzlePlayer)
docs/
  internship-report.docx                 — отчет (≈20 стр.)
```

## Build и тестове

```powershell
mvn test                  # 32 теста, всички трябва да минат
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

## Engine и HTTP сървър

```cmd
java -jar target\closed-puzzle-validator-1.0-SNAPSHOT.jar serve --port 0 --parent-pid <pid>
```

Сървърът слуша само на `127.0.0.1` и отпечатва `LISTENING <port>` на първия ред от stdout.
`--port 0` избира свободен порт (по подразбиране 8765). С `--parent-pid` сървърът спира сам,
когато процесът-родител (Unity) приключи.

| Заявка | Тяло | Отговор |
|---|---|---|
| `GET /health` | — | `{"status":"ok"}` |
| `GET /examples` | — | имената на вградените 4 игри |
| `POST /validate` | модел | доклад от валидатора |
| `POST /games` | модел | 201: нова сесия (`sessionId`, `model`, `state`, `status`) |
| `POST /games?example=memory-game` | — | 201: сесия от вградена игра |
| `GET /games/{id}` | — | `state`, `status` |
| `POST /games/{id}/actions` | `{"action":"ClickCard","params":{"target":"O01"}}` | `accepted`, `message`, `rules`, `events`, `sounds`, `status`, `state` |
| `POST /games/{id}/time` | `{"ms":1500}` | като при действие; придвижва логическия часовник |
| `DELETE /games/{id}` | — | 204 |
| `POST /shutdown` | — | спира сървъра |

Параметри на действията: `target` за click; `obj` и/или `src`, и `dst` за move.
`status` съдържа `finished`, `result`, `clock` и `nextTimerInMs`: когато не е `null`, клиентът
изчаква толкова и праща `/time`. Отхвърлен ход е 200 с `accepted:false`. HTTP грешките са 400
(лоша заявка), 404 (непозната сесия/маршрут) и 422 (невалиден модел, с `issues`).

Семантиката на изпълнение (оценка на правилата, събития с `trigger`, атомарност, отложени
ефекти) е описана в Javadoc-а на `GameEngine`.

## Unity клиент (`unity/`)

Тънък клиент на Unity 6 (6000.4.5f1). Не знае правилата на нито една игра: генерира сцената от
P/O на модела, праща действията от A и показва състоянието, което върне engine-ът.

1. `mvn -q -DskipTests package` в корена на repo-то;
2. Unity Hub → *Add project from disk* → папка `unity/` (при първото отваряне се създава сцената
   `Assets/Scenes/PuzzlePlayer.unity`);
3. *Play* → избор на игра от менюто или път до JSON модел.

Формите идват от незадължителния атрибут `shape` на PT и OT (`circle`, `square`, `triangle`,
`diamond`, `heart`, `polygon-N`, `star-N` за N от 3 до 12). Engine-ът го игнорира, а клиентът
рисува формата вместо името на типа. Без `shape` обектът е заоблен квадрат с етикет.

Клик върху обект изпраща click действията (`target`), а влачене или клик върху обект и после
позиция изпраща move действията (`obj`, `dst`). За build: меню *Puzzle Player → Copy Engine Jar to
StreamingAssets*. На машината трябва да има Java 21+ в `PATH` или в `JAVA_HOME`.

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

