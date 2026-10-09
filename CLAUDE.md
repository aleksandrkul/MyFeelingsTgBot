# MyFeelings diary bot — working notes

A personal Telegram diary bot for a single owner, with summaries from a local LLM (Ollama).
`instruction.md` is the design document and the stage plan. `BACKLOG.md` is the open work.

## Build and test

```
./mvnw test           # 118 tests, all must stay green
./mvnw clean package  # target/diary-bot.jar
java -jar target/diary-bot.jar check   # one round-trip through the local model
```

Maven comes from the wrapper; there is no global Maven. Java 21 bytecode, built on whatever JDK is
installed. Never report a change as done without a green `./mvnw test`.

## Conventions that are easy to get wrong

- **Code, comments, logs, commit messages and tests are English. Replies to the owner are not.**
  Every user-facing string lives in `src/main/resources/messages_ru.properties` and
  `messages_en.properties`, reached through `Messages.get(key, args…)`. The two files must hold the
  same keys — `Messages` refuses to start otherwise — so a new string means adding it to both.
- **Counts go through `Messages.plural(noun, count)`**, which picks the Russian form arithmetically.
  A key whose forms are not nominative says so in its name (`within-minute` for "в течение …").
- **Entry text is never logged.** Only ids, dates and lengths. The same goes for the owner's and the
  other person's names: `putSetting` logs the key and the value's length, never the value.
- **Settings keys are strings in exactly one place**, `Settings`. The raw `setting`/`putSetting` on
  the repository are package-private on purpose.
- **Replies carry no parse mode.** Model output contains stray `*` and `_`, and Telegram rejects the
  whole request when they do not form valid markup.
- **The diary day starts at 04:00**, not midnight: see `DiaryDay`. Anything that decides "which day
  is this" goes through it.
- **Ollama options go inside the `options` object** of `/api/chat`. At the top level `num_ctx` is
  silently ignored and long input is truncated without an error.

## How the code is laid out

One package, split by what a class does. `DiaryBot` is the owner filter, the routing and the
`/summary` request. Each conversation owns its state and its constants: `Onboarding` (the two
names), `DayFlow` (closing a day), `UndoRequest` (the two steps of `/undo`). `Telegram` is
everything the bot sends, including the 4096-character split. `Feed`, `Reminder`, `SummaryService`,
`Settings` know nothing about Telegram. Constants are deliberately not gathered into a shared class:
each belongs to one conversation.

## Tests

JUnit 5, no network and no Telegram. `BotHarness` drives a real `DiaryBot` against a temporary
database with a recording client in place of the API, so a whole conversation — messages, button
taps, restarts — runs in milliseconds. Prefer it over mocks.

Pure functions are tested as pure functions: `Reminder.decide` takes the current moment as a
parameter, `Messages.plural` is arithmetic. Keep it that way; it is why the schedule is testable
without waiting for 21:00.

The three tests in `SummaryWithModelTest` need a local Ollama and are **skipped, not failed**, when
none answers. The build has to pass on a machine that is not running a model.
