# Backlog

What is left to do, grouped by what it is and ordered by what it costs to leave undone.
Stage numbers refer to `instruction.md`. Sizes are rough: **S** under an hour, **M** a few hours,
**L** a day or more.

Current state: stages 1-3 and 7-11 are done, `./mvnw test` runs 118 tests, the bot is usable daily.

**This is a training project.** The data is disposable, so durability work — backups, running as a
service, proving the logs stay clean — buys nothing yet and is parked in section 7. What is worth
doing is what makes the thing better to use and more interesting to build: the interactions, the one
real bug in section 1, and the model's answers.

---

## 1. Correctness

### 1.1 Shared state is read and written from two threads — **S**
`DiaryBot.remind()` runs on the `reminder` thread and calls `day.isActive()` and `day.start()`,
while the polling thread calls `day.handleText()` for the same object. `DayFlow.closing`
(`DayFlow.java:35`) is a plain field with no synchronization, so the reminder can observe a stale
value and ask the evening question in the middle of a closing that is already open — the exact case
`remind()` tries to avoid.

`Messages.lang` (`Messages.java:22`) has the same shape: written by the polling thread when the
owner taps a language, read by the model thread while a summary is being built.

The simplest fix that matches the rest of the design: route the reminder's wake-up through the same
single-threaded executor that handles updates, so all conversation state stays on one thread.
Making the two fields `volatile` would paper over it without making the invariant true.

### 1.2 A truncated summary is not reported to the owner — **S**
`OllamaClient.java:80` logs a warning when the model stops on the context limit, and sends the
cut-off text to the owner as if it were complete. The owner reads a summary that ends mid-thought
and has no way to know why. Return that flag with the reply and add a line to the message.

### 1.3 ~~No README~~ — **done**
`README.md` and `.env.example` cover building, the environment variables, the token and owner id,
and the tests. Restoring from a backup is the one thing still missing, and it cannot be written
until 1.1 exists.

---

## 2. Product gaps

### 2.1 Only the most recent entry can be removed — **M**
`/undo` deletes the last entry; anything older is permanent. A typo from three days ago, or a line
written to the wrong day, cannot be fixed. The data model already supports it — entries have ids and
`updateText` was removed when the interview went away.

Suggested shape: `/day 2026-10-05` shows that day's card with its entries numbered, and
`/undo 3` removes the third. Keeps one mental model: you address a day, then a line in it.

### 2.2 Editing a message in Telegram does nothing — **S**
Deliberate, and documented, but invisible: an edited message arrives as `editedMessage`, the bot
ignores it, and the owner believes the diary changed. Reply once, explaining that the diary keeps
what was written and offering `/undo`.

### 2.3 No way to look at one day — **S**
`/last` shows one entry and `лента` shows everything. There is no "show me yesterday". `DayFlow`
already renders a card for an arbitrary date (`renderCard(LocalDate)`); it needs a command.

### 2.4 ~~The timezone is a compile-time constant~~ — **done**
`Config.ZONE` now reads `DIARY_TIMEZONE`, falling back to the system zone, and the env table in
`instruction.md` documents it. It is read once at class load, which is right for a single-user bot;
a command to change it at runtime would mean giving up the constant and is not worth it.

### 2.5 The feed has no upper bound — **S**
`лента` renders everything; `/feed N` exists but is not what anyone types. After a year that is
dozens of messages on every invocation. Default the bare trigger to a window — the last 30 days —
and end with a line offering `/feed all`.

### 2.6 The owner cannot decline to name a feeling — **S**
The closing flow requires tapping one of five buttons; `/cancel` abandons the whole card. Some days
do not have a feeling. Add a sixth button that skips it, which the card and the model input already
handle (both treat the feeling as nullable).

### 2.7 `chat_id` is overwritten by whichever chat the owner last used — **S**
`rememberChat` stores the chat of every message. If the owner ever adds the bot to a group and
writes there, the evening question starts going to the group. Store it once and ignore later chats,
or prefer a private chat.

---

## 3. User interaction

The first three were asked for directly and share one cause: the chat fills up with the bot's own
messages until the diary is hard to read back.

### 3.1 The summary needs a button, not a command — **S**
Typing `/summary` or `итог` to get the thing the diary exists for is the wrong amount of friction.
A persistent reply keyboard — `ReplyKeyboardMarkup` with `isPersistent` and `resizeKeyboard`, two or
three buttons: *Итог*, *Лента*, *Закрыть день* — sits under the input field and is always one tap
away.

Almost free to build: a reply-keyboard button sends its label as an ordinary text message, and the
exact-match trigger words from stages 10 and 11 already turn "итог" and "лента" into their commands.
The keyboard has to be attached once, after onboarding, and re-attached when the language changes,
because the labels are localized.

The tradeoff worth deciding before building: a persistent keyboard takes vertical space and puts the
typing keyboard one tap further away, in a bot whose main action is typing. `inputFieldPlaceholder`
("напиши, что помнишь") softens that. If it proves to be in the way, the fallback is an inline
*Итог* button attached to the day's first acknowledgement, which costs no space but is only there
while that message is on screen.

### 3.2 A button to clear the chat — **M**
The chat grows without bound and becomes a wall of acknowledgements. The Bot API allows more than
expected here: in a private chat a bot may delete **both its own and the owner's** messages, in
batches of up to 100 (`deleteMessages`), with one hard limit — **nothing older than 48 hours**.

So the button can honestly offer "clear the last two days" and should say exactly that, pointing at
Telegram's own *Clear history* for anything older. To do it the bot has to remember message ids:
incoming ones arrive with the update, outgoing ones come back from `execute(SendMessage)`, whose
result `Telegram.attempt` currently discards.

On a real diary this would wait for backups: clearing the chat deletes the owner's typed text from
Telegram and leaves one unbacked-up SQLite file as the only copy. On a training project there is
nothing precious to lose, so it is unblocked — worth remembering if that ever changes.

### 3.3 Stop announcing "Сохранил карточку за …" — **S**
Tapping *Сохранить* is its own feedback; the confirmation adds a line to a chat that is already too
long. But removing it outright leaves the tap with no visible result at all.

The better move is the one Telegram is built for: **edit the card message in place** instead of
sending a new one. `EditMessageText` drops the buttons and leaves the finished card where it was, so
the save is visible without costing a message.

The same applies to the whole closing conversation, which currently sends five separate messages —
the question, the feeling buttons, the conversation buttons, the card, the confirmation. Edited in
place it is **one** message that changes as the owner answers. That is the single biggest reduction
in chat noise available, and it makes 3.2 a convenience rather than a necessity.

It needs the message id of what was sent, so it depends on the same change as 3.2: `Telegram` must
return the `Message` that `execute` already gives back, and the test harness' recording client must
return one instead of `null`.

### 3.4 Register the command menu with Telegram — **S**
`setMyCommands` is never called, so the commands only exist in `/help`. Registering them gives the
owner the native command list and descriptions, in the chosen language, with no typing. Highest
ratio of effect to work in this list.

### 3.5 Confirm `/undo` with a button, not by typing `/undo yes` — **S**
Every other confirmation in the bot is an inline button. `/undo yes` is the only place that asks the
owner to type a magic word, and it is the one command where a mistake deletes something.

### 3.6 Five feeling buttons in one row are cramped on a phone — **S**
`DayFlow.askFeeling` builds a single `InlineKeyboardRow`. Telegram shrinks the labels until they
truncate. Two rows of two or three read much better. `Telegram.sendWithButtons` takes one row and
would need to take a list.

### 3.7 Dates are shown two different ways — **S**
The feed says "7 октября" (localized, `Feed.today`), the day card says "2026-10-07"
(`day.card.header`). Same diary, two formats. `SummaryService.format` already localizes a period;
the card should use the same helper.

### 3.8 Every message gets a "Записал за …" reply — **S**
On a day with eight thoughts that is eight near-identical bot messages in a chat the owner reads
later as a diary. A Telegram reaction on the message, or acknowledging only the first entry of a
day, would keep the chat readable. Same cause as 3.1-3.3, and worth deciding together with them.

### 3.9 The failure message mixes languages — **S**
`summary.failed` wraps the technical cause verbatim, so the owner sees "Не получилось построить
сводку: could not reach http://localhost:11434 (Connection refused)". Map the two common causes —
unreachable, timed out — to clean sentences and keep the raw text for the log.

### 3.10 The first run ends abruptly — **S**
After the two names the bot says "/help shows what I can do" and stops. Nothing explains that it
will ask about the day at 21:00, which is the one behaviour the owner cannot discover by trying.
Three lines after onboarding: write whenever, I will ask in the evening, `итог` when you want a
summary.

### 3.11 Three copies of the same time format — **S**
`"HH:mm"` is defined in `Entry.java:15`, `DayFlow.java:27` and `Feed.java:73`. Harmless until one of
them changes.

---

## 4. Refactoring

### 4.1 Extract the model request from `DiaryBot` — **S**
`modelExecutor`, `typingScheduler` and `modelSlot` are the last mutable state in `DiaryBot`, and
`close()` exists only for them. Moving `handleSummary` and the three fields into `SummaryRequests`
leaves the bot as pure routing (~375 lines) and is the same move that produced `DayFlow`.

### 4.2 Remove the reflection from the tests — **S**
`BotHarness.fireReminder` reaches into `DiaryBot`'s private `reminder` field, and
`SummaryWithModelTest.ollamaDown` replaces the harness' private `bot`. Both are leftovers from when
that state had no seam. Expose the reminder package-private, and let the harness build a bot against
a chosen `Config`, which is useful on its own.

### 4.3 The `summaries` table is created and never used — **S**
`DiaryRepository.java:70` creates it; nothing reads or writes it. It belongs to stage 5. Either leave
it with a comment saying so, or drop it until the stage happens — an empty table invites the
assumption that caching works.

### 4.4 `DiaryRepository` holds three unrelated groups of SQL — **M, optional**
278 lines covering entries, day cards and raw settings access. If it keeps growing, split the card
queries out the way `Settings` was split out. Not yet painful.

### 4.5 No package split — **deliberate**
21 files in one package. Considered and declined: at this size packages add ceremony, and the useful
boundaries (storage, model, conversations) are already visible from the class names. Revisit past
~35 files, and split by domain, not by layer.

---

## 5. Model quality (stage 4)

### 5.1 Build the evaluation set — **M**
5-10 synthetic days with no real personal data, committed to the repo, so prompt changes can be
compared instead of guessed at. The tests already contain a usable week
(`SummaryWithModelTest.withWeek`); it should become a fixture both the tests and a manual script read.

### 5.2 The paragraph comes out shorter than asked — **M**
The prompt asks for four to seven sentences; `qwen2.5:7b` answers in two. It covers what it should,
but a week of diary deserves more. Try naming a length in words, or asking for one sentence per
theme found.

### 5.3 Invented facts need watching — **M**
An earlier run called the other person "партнёр", which no entry said. The current prompt no longer
invites it and the tests assert that no impersonal label appears, but nothing checks for invention in
general. The evaluation set is what makes this measurable.

### 5.4 Compare with a 12-14B model — **M**
`qwen2.5:14b` or `gemma3:12b` against the same set, on the same hardware, measuring answer quality
and the wait. 7B currently answers in 1-2 seconds, which leaves room.

---

## 6. Deferred on purpose

- **Stage 5, weekly chunking and the summary cache.** With `num_ctx` 16384 a single request holds
  roughly four months. Raise the window first; implement chunking only when even that stops fitting.
  The `cache_key` design (entries plus model plus prompt) is already written down.
- **Multiple people in one diary.** The schema and the prompt assume one. Changing that is a new
  product, not a feature.
- **Search, voice messages, a web UI, Docker.** Out of scope in the spec and still out.

---

## 7. Parked until this stops being a training project

Each of these protects data or uptime, and the data is disposable, so they are written down rather
than queued. If that day comes: backup first, then the log test, then autostart.

### 7.1 There is no backup (stage 6) — **M**
The diary is meant to be kept for years and a single `data/diary.db` is the only copy. A disk
failure or a bad `DELETE` ends the project.

Copying the file while the bot runs can produce a corrupt copy, so the backup must use
`VACUUM INTO 'diary-YYYY-MM-DD.db'` or the SQLite backup API. Add a scheduled task inside the bot
(the `Reminder` scheduler is already there) or a shell script plus `launchd`, keep the last N copies,
and **test restoring from one** — the spec's definition of done requires that, and an untested
backup is not a backup. Keep the copies off the working disk; the privacy section asks for an
encrypted location.

### 7.2 Verify that logs never contain entry text (stage 6) — **S**
The rule is stated and followed by hand. It needs a test: run a message through the bot with a
capturing logback appender and assert the text does not appear in any event. Otherwise one careless
`log.info("saving {}", text)` in a year breaks a privacy promise with nothing to catch it.

### 7.3 No autostart (stage 6) — **M**
The bot only runs while the terminal is open, so the evening reminder — the thing that makes the
diary a habit — silently stops whenever the machine restarts. On macOS this is a `launchd` plist
with `KeepAlive`; the spec also mentions Windows and Linux equivalents. Needs a log file, because
stdout goes nowhere under a service.
