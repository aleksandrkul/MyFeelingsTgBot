# Tasks ready to hand to a session

Each block below is self-contained: paste it as the whole prompt. Project conventions live in
`CLAUDE.md` and are picked up automatically; rationale for each task is in `BACKLOG.md` under the
number given.

**Every task ends the same way:** `./mvnw test` green, new behaviour covered by a test, no
user-facing string added to only one of the two `.properties` files.

## Running these in parallel

Wave A tasks touch disjoint files and can all run at once. Wave B tasks all touch
`Telegram.java` / `DayFlow.java` / `DiaryBot.java` and must run **one at a time, in the order
given** — B1 changes a signature the rest depend on. Wave C is independent of both.

| | | touches |
|---|---|---|
| **A1** | SQLite backup | new `Backup.java`, `Reminder` scheduler, `DiaryBot.close` |
| **A2** | log hygiene test | new test only |
| **A3** | truncated summary | `OllamaClient`, `SummaryService`, both `.properties` |
| **A4** | one time formatter | `Entry`, `DayFlow`, `Feed` |
| **A5** | unused `summaries` table | `DiaryRepository` |
| **B1** | edit messages in place | `Telegram`, `DayFlow`, `BotHarness` |
| **B2** | button rows | `Telegram`, `DayFlow` |
| **B3** | persistent keyboard | `Telegram`, `DiaryBot`, both `.properties` |
| **B4** | `/undo` button | `Telegram`, `UndoRequest`, `DiaryBot` |
| **B5** | clear the chat | `Telegram`, `DiaryBot`, `DiaryRepository`, both `.properties` |
| **C1** | evaluation set | new fixture + `SummaryWithModelTest` |

A4 touches `DayFlow` in one place only and is a three-line change; run it whenever.

---

## Wave A — independent

### A1 — Daily backup of the database (backlog 1.1, M)

```
The diary database at ./data/diary.db has no backup, and it is meant to be kept for years.

Add a daily backup. Copying the file while the bot runs can produce a corrupt copy, so use
SQLite's `VACUUM INTO 'target.db'` from the existing connection, not a file copy. Write to a
directory from a new env var BACKUP_DIR (default ./data/backups), name files diary-YYYY-MM-DD.db,
and keep the last 14, deleting older ones. Schedule it with a daily tick, reusing the pattern in
Reminder (its decision is a pure function taking the current moment as a parameter — do the same so
the schedule is testable without waiting a day). Shut it down in DiaryBot.close.

Then prove a restore works: a test that writes entries, takes a backup, opens the backup as a
separate DiaryRepository and reads the same entries back. instruction.md's definition of done
requires a tested restore, so that test is the point of the task, not an extra.

Document BACKUP_DIR in instruction.md's env table and tick the backup item in stage 6.
```

### A2 — Prove that logs never contain entry text (backlog 1.6, S)

```
The project promises that diary entry text never reaches the log. It is followed by hand and
nothing enforces it.

Add a test that attaches a capturing logback appender to the root logger, drives a whole
conversation through BotHarness — a plain entry, a /new closing with a feeling and a mark, /last,
/undo, and the onboarding with the two names — and asserts that no captured event's formatted
message or arguments contain any of the texts that were sent, nor either name.

Use distinctive strings so a match cannot be a coincidence. If the test finds a leak, fix the
logging rather than weakening the test. Tick the log item in stage 6 of instruction.md.
```

### A3 — Tell the owner when a summary was cut off (backlog 1.3, S)

```
OllamaClient logs a warning when the model stops on the context limit (done_reason == "length")
and then returns the truncated text, which the bot sends to the owner as if it were complete. The
owner reads a summary that ends mid-thought with no idea why.

Carry that flag out of OllamaClient — return a small record of the reply plus whether it was cut —
and have SummaryService append a sentence to the summary when it was. The sentence names the cause
and what to do: ask for fewer days, or raise OLLAMA_NUM_CTX. Add the string to both
messages_ru.properties and messages_en.properties.

Cover it with a test that does not need Ollama: the flag's effect on the message is what matters, so
test SummaryService against a stub rather than the real model.
```

### A4 — One time formatter instead of three (backlog 3.11, S)

```
DateTimeFormatter.ofPattern("HH:mm") is defined three times: Entry.java, DayFlow.java and inside
Feed.today(). Same format, three copies.

Put it in one place and use it from all three. Pure cleanup: ./mvnw test must stay green with no
test changed.
```

### A5 — Decide what to do about the unused `summaries` table (backlog 4.3, S)

```
DiaryRepository creates a `summaries` table that nothing ever reads or writes. It belongs to
stage 5 of instruction.md (weekly chunking and the summary cache), which is deliberately deferred.

An empty table invites the assumption that caching already works. Either delete the CREATE TABLE
until the stage happens, or keep it with a comment saying which stage owns it and that it is unused
on purpose. Pick one, do it, and say in the commit message which and why.
```

---

## Wave B — one at a time, in this order

### B1 — Edit one message in place instead of sending five (backlog 3.3, M)

```
Closing a day currently sends five separate messages: the question, the feeling buttons, the
conversation buttons, the card, and a "Сохранил карточку за …" confirmation. The chat fills up with
the bot's own messages until the diary is hard to read back.

Make the whole closing conversation one message that changes as the owner answers, using
EditMessageText. Confirming edits the card in place and removes the buttons, so the save is visible
without a new message, and the separate confirmation message goes away entirely.

This needs the id of the message that was sent. Telegram.attempt currently throws away what
execute() returns; have the send methods return the Message. BotHarness's recording client returns
null from every call — make it return a Message with an incrementing id, and record edits as well as
sends so the tests can assert on the final state of the one message.

Keep the existing DayCardTest assertions meaningful: they currently read the last reply, and after
this change the interesting thing is the latest state of the edited message. Update them to match
the new shape rather than deleting them.
```

### B2 — Feeling buttons on more than one row (backlog 3.6, S)

```
DayFlow.askFeeling puts all five feeling buttons in a single InlineKeyboardRow. On a phone Telegram
shrinks the labels until they truncate.

Let Telegram.sendWithButtons take several rows instead of one, and lay the five feelings out as
three plus two. Update the other callers (the language picker, the conversation mark, the card
buttons) to pass a single row through the new signature.

The tests assert on a flat list of buttons, which still works; check that FeedTest, DayCardTest and
LanguageTest stay green without being weakened.
```

### B3 — A persistent summary button (backlog 3.1, S)

```
Getting a summary means typing /summary or "итог". The thing the diary exists for should be one tap
away.

Attach a persistent reply keyboard — ReplyKeyboardMarkup with isPersistent and resizeKeyboard —
with the buttons Итог, Лента and Закрыть день, plus an inputFieldPlaceholder inviting the owner to
write. A reply-keyboard button sends its label as ordinary text, and the exact-match trigger words
already turn "итог" and "лента" into their commands, so extend that mechanism to cover the third
label rather than adding a new path.

The keyboard has to be attached once after onboarding finishes and re-attached when the language
changes, because the labels are localized. Button labels go into both .properties files.

Test that the keyboard is attached after onboarding, that it is re-attached on a language change,
and that tapping each label reaches the same handler as the command.
```

### B4 — Confirm `/undo` with a button (backlog 3.5, S)

```
Every confirmation in the bot is an inline button except /undo, which asks the owner to type
"/undo yes". It is the one command where a mistake deletes something.

Replace the typed confirmation with two inline buttons on the offer message, Удалить and Отменить,
with a callback prefix of their own. Keep the two-minute window and keep deleting by the remembered
id rather than by whatever is last at the time — a restart must still cancel the offer. Edit the
offer message in place when it is resolved, the way the card now does.

Leave "/undo yes" working as an undocumented alias or remove it, your call, but say which in the
commit message. Update CommandsTest, which currently drives the typed form, and keep its assertion
that asking does not delete.
```

### B5 — A button that clears the chat (backlog 3.2, M)

```
The chat grows without bound. The Bot API allows a bot to delete both its own and the owner's
messages in a private chat, in batches of up to 100 via deleteMessages, with one hard limit:
nothing older than 48 hours.

Add a command and a button that clears what can be cleared and says exactly that — "за последние
двое суток" — and points at Telegram's own Clear history for anything older. Ask for confirmation
with an inline button before deleting.

To do it the bot must remember message ids: incoming ones arrive with the update, outgoing ones come
back from the send methods (B1 made them return the Message). Store them with their timestamps,
prune anything past 48 hours, and delete in batches of 100.

Do not start this before the backup task (A1) is merged. Clearing the chat deletes the owner's typed
diary text from Telegram, and without backups that chat is the only copy of the diary outside one
unbacked-up SQLite file. State that dependency in the commit message.
```

---

## Wave C — model quality

### C1 — An evaluation set for the summary prompt (backlog 5.1, M)

```
Prompt changes are currently judged by reading one answer and forming an impression. Stage 4 of
instruction.md needs something comparable.

Commit a fixture of 8-10 synthetic diary days — no real personal data — with text, a feeling and a
conversation mark each, as a resource the tests and a manual script both read.
SummaryWithModelTest.withWeek already contains a usable week; make that the fixture instead of
inline code.

Then add a small runner, a main class or a test tagged so it does not run by default, that builds a
summary for the fixture and prints the answer with its length, the wait, and simple checks: does it
end with the diary's question, is it one paragraph, does it stay in the entries' language, does it
avoid impersonal labels for the person, does it avoid naming anything the entries do not contain.

The point is repeatability: running it before and after a prompt change should make the difference
visible. Do not tune the prompt in this task.
```
