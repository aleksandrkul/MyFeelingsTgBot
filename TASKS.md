# Tasks ready to hand to a session

Each block below is self-contained: paste it as the whole prompt. Project conventions live in
`CLAUDE.md` and are picked up automatically; rationale for each task is in `BACKLOG.md` under the
number given.

**Every task ends the same way:** `./mvnw test` green, new behaviour covered by a test, no
user-facing string added to only one of the two `.properties` files.

## What can run at once

With a codebase this small almost everything passes through `DiaryBot`, `Telegram` or `DayFlow`, so
there is less parallelism here than the number of tasks suggests. Three are genuinely isolated and
can go out together; the rest is a queue.

**Parallel:** P1 (`OllamaClient`, `SummaryService`, both `.properties`), P2 (`DiaryRepository`
only), P3 (a new test fixture only).

**Queue, in order:** Q1 fixes a real bug and simplifies the threading everything else sits on. Q2
changes a signature the rest of the queue depends on. Q3 and Q4 are the two you asked for. Q7 is a
three-line cleanup that can be slipped in anywhere.

---

## Parallel

### P1 — Tell the owner when a summary was cut off (backlog 1.2, S)

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

### P2 — Decide what to do about the unused `summaries` table (backlog 4.3, S)

```
DiaryRepository creates a `summaries` table that nothing ever reads or writes. It belongs to
stage 5 of instruction.md (weekly chunking and the summary cache), which is deliberately deferred.

An empty table invites the assumption that caching already works. Either delete the CREATE TABLE
until the stage happens, or keep it with a comment saying which stage owns it and that it is unused
on purpose. Pick one, do it, and say in the commit message which and why.
```

### P3 — An evaluation set for the summary prompt (backlog 5.1, M)

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

---

## Queue

### Q1 — Fix the cross-thread race (backlog 1.1, S)

```
The bot has a real data race. DiaryBot.remind() runs on the reminder's own thread and calls
day.isActive() and day.start(), while the polling thread calls day.handleText() on the same object.
DayFlow.closing is a plain field with no synchronization, so the reminder can read a stale value and
ask the evening question in the middle of a closing that is already open — the exact case remind()
is trying to avoid. Messages.lang has the same shape: written by the polling thread when the owner
taps a language, read by the model thread while a summary is being built.

Fix it by making the invariant true rather than papering over it: all conversation state should be
touched by one thread only. Route the reminder's wake-up through the same single-threaded executor
that handles updates instead of calling into the bot from the scheduler thread. Making the two
fields volatile would hide the symptom and leave the design wrong.

Keep Reminder.decide a pure function taking the moment as a parameter — that is what makes the
schedule testable — and keep BotHarness.fireReminder working, since ReminderTest drives it. Add a
test that a reminder arriving while a closing is open does not start a second one.
```

### Q2 — Edit one message in place instead of sending five (backlog 3.3, M)

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

### Q3 — A persistent summary button (backlog 3.1, S)

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

### Q4 — A button that clears the chat (backlog 3.2, M)

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

On a real diary this would wait for backups, since it deletes the owner's typed text from Telegram
and leaves one unbacked-up SQLite file as the only copy. This is a training project, so go ahead —
but note it in the commit message, because the constraint returns the day it stops being one.
```

### Q5 — Confirm `/undo` with a button (backlog 3.5, S)

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

### Q6 — Feeling buttons on more than one row (backlog 3.6, S)

```
DayFlow.askFeeling puts all five feeling buttons in a single InlineKeyboardRow. On a phone Telegram
shrinks the labels until they truncate.

Let Telegram.sendWithButtons take several rows instead of one, and lay the five feelings out as
three plus two. Update the other callers (the language picker, the conversation mark, the card
buttons) to pass a single row through the new signature.

The tests assert on a flat list of buttons, which still works; check that FeedTest, DayCardTest and
LanguageTest stay green without being weakened.
```

### Q7 — One time formatter instead of three (backlog 3.11, S)

```
DateTimeFormatter.ofPattern("HH:mm") is defined three times: Entry.java, DayFlow.java and inside
Feed.today(). Same format, three copies.

Put it in one place and use it from all three. Pure cleanup: ./mvnw test must stay green with no
test changed.
```

---

## Not queued

**Backups, autostart and the log-hygiene test** live in section 7 of `BACKLOG.md`. They protect data
and uptime, and the data here is disposable, so they buy nothing yet. Start with backups if this ever
turns into a diary you actually keep.
