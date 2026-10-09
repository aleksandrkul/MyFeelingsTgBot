# Backlog

What is left to do, grouped by what it is and ordered by what it costs to leave undone.
Stage numbers refer to `instruction.md`. Sizes are rough: **S** under an hour, **M** a few hours,
**L** a day or more.

Current state: stages 1-3 and 7-11 are done, `./mvnw test` runs 118 tests, the bot is usable daily.

---

## 1. Correctness and durability — do these first

### 1.1 There is no backup (stage 6) — **M**
The diary is meant to be kept for years and a single `data/diary.db` is the only copy. A disk
failure or a bad `DELETE` ends the project.

Copying the file while the bot runs can produce a corrupt copy, so the backup must use
`VACUUM INTO 'diary-YYYY-MM-DD.db'` or the SQLite backup API. Add a scheduled task inside the bot
(the `Reminder` scheduler is already there) or a shell script plus `launchd`, keep the last N copies,
and **test restoring from one** — the spec's definition of done requires that, and an untested
backup is not a backup. Keep the copies off the working disk; the privacy section asks for an
encrypted location.

### 1.2 Shared state is read and written from two threads — **S**
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

### 1.3 A truncated summary is not reported to the owner — **S**
`OllamaClient.java:80` logs a warning when the model stops on the context limit, and sends the
cut-off text to the owner as if it were complete. The owner reads a summary that ends mid-thought
and has no way to know why. Return that flag with the reply and add a line to the message.

### 1.4 No autostart (stage 6) — **M**
The bot only runs while the terminal is open, so the evening reminder — the thing that makes the
diary a habit — silently stops whenever the machine restarts. On macOS this is a `launchd` plist
with `KeepAlive`; the spec also mentions Windows and Linux equivalents. Needs a log file, because
stdout goes nowhere under a service.

### 1.5 No README (stage 6) — **S**
How to build, which environment variables exist, how to get the bot token and the owner id, how to
restore from a backup. `instruction.md` is a design document, not instructions for running the thing.

### 1.6 Verify that logs never contain entry text (stage 6) — **S**
The rule is stated and followed by hand. It needs a test: run a message through the bot with a
capturing logback appender and assert the text does not appear in any event. Otherwise one careless
`log.info("saving {}", text)` in a year breaks a privacy promise with nothing to catch it.

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

### 2.4 The timezone is a compile-time constant — **S**
`Config.ZONE` is `Europe/Amsterdam` in the source. The owner travelling two timezones east writes an
entry at 02:00 local and it lands on the wrong diary day. Now that `Settings` exists this belongs
there, with the current value as the default.

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

### 3.1 Register the command menu with Telegram — **S**
`setMyCommands` is never called, so the commands only exist in `/help`. Registering them gives the
owner the native command list and descriptions, in the chosen language, with no typing. Highest
ratio of effect to work in this list.

### 3.2 Confirm `/undo` with a button, not by typing `/undo yes` — **S**
Every other confirmation in the bot is an inline button. `/undo yes` is the only place that asks the
owner to type a magic word, and it is the one command where a mistake deletes something.

### 3.3 Five feeling buttons in one row are cramped on a phone — **S**
`DayFlow.askFeeling` builds a single `InlineKeyboardRow`. Telegram shrinks the labels until they
truncate. Two rows of two or three read much better. `Telegram.sendWithButtons` takes one row and
would need to take a list.

### 3.4 Dates are shown two different ways — **S**
The feed says "7 октября" (localized, `Feed.today`), the day card says "2026-10-07"
(`day.card.header`). Same diary, two formats. `SummaryService.format` already localizes a period;
the card should use the same helper.

### 3.5 Every message gets a "Записал за …" reply — **S**
On a day with eight thoughts that is eight near-identical bot messages in a chat the owner reads
later as a diary. A Telegram reaction on the message, or acknowledging only the first entry of a
day, would keep the chat readable.

### 3.6 The failure message mixes languages — **S**
`summary.failed` wraps the technical cause verbatim, so the owner sees "Не получилось построить
сводку: could not reach http://localhost:11434 (Connection refused)". Map the two common causes —
unreachable, timed out — to clean sentences and keep the raw text for the log.

### 3.7 The first run ends abruptly — **S**
After the two names the bot says "/help shows what I can do" and stops. Nothing explains that it
will ask about the day at 21:00, which is the one behaviour the owner cannot discover by trying.
Three lines after onboarding: write whenever, I will ask in the evening, `итог` when you want a
summary.

### 3.8 Three copies of the same time format — **S**
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
