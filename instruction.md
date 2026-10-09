# Telegram Diary Bot with a Local LLM

## Goal

A personal Telegram bot for a single user. Every day the user writes entries about their feelings and their interactions with one particular person (what they discussed, what conclusions they reached). The bot stores entries with a date. On request, the bot produces a summary of a period using a local LLM (Ollama) and ends it with the diary's one question: **"Что сейчас важно мне — независимо от реакции другого человека?"**

The question is the point of the diary, so it is asked in the owner's words and in their language, not translated.

Constraints:
- The data is personal: nothing is sent to external APIs, the model is local only.
- The bot serves exactly one user; every other Telegram account is ignored.
- Start with a simple MVP and defer everything non-essential.

## Business logic

The shape the bot takes once the MVP works. Stages 7-11 build it.

**Once, at the start.** The owner opens the bot and presses Start. The bot asks for a reply language, then how to address the owner, then who the diary is about — a name or an initial, for example "К.". From then on the bot knows what it needs: one person, one private feed, one question.

**The day.** During the day the owner writes whatever they remember, in one message or several. Those pieces are not separate records: they accumulate into the draft of a single card for that day. In the evening the bot asks "Что осталось от сегодняшнего дня?" and the answer joins the same draft. Then the bot asks two short things — the feeling of the day, chosen from five (спокойно / тревожно / тепло / усталость / радостно), and whether there was a conversation with that person. It shows the assembled card — date, text, feeling, the conversation mark — and the owner confirms it or goes back to editing. The date is never typed by hand.

**The feed.** Cards pile up in one column: today's at the top, everything earlier below it, grouped by month. Nothing is tagged or sorted by the owner; they only write.

**The summary.** At any moment the owner writes `итог`. The bot collects the cards, hands them to the local model, and sends back a short answer — one paragraph, not a wall — ending with the diary's question to the owner themselves.

This supersedes the four-question `/new` interview built in stage 3: "with whom" is answered once at the start, and "what we concluded" belongs in the free text.

## Language and conventions

- **The whole project is in English**: code, identifiers, comments, log messages, commit messages, README, documentation and tests.
- **Bot replies are not**: the owner picks the reply language on first contact, from two buttons (🇺🇸 English, 🇷🇺 Русский), and `/language` changes it later. The choice lives in the `settings` table, so it survives a restart. Both texts live in `messages_en.properties` and `messages_ru.properties`; `Messages` refuses to start if the two files have different keys. The reply language is independent of the language entries are written in — a diary in Russian with an English interface is a valid combination.
- Diary entries are stored exactly as written (the user writes in Russian). Do not translate or alter them.
- Summaries are written in the same language as the entries. The summary prompt itself is in English and explicitly instructs the model to reply in the language of the entries.
- Timezone for deciding the date of an entry: `DIARY_TIMEZONE` (an IANA zone such as `Europe/Berlin`), the system zone when unset.
- **The diary day starts at 04:00 local time.** An entry written between 00:00 and 03:59 is stored under the previous calendar date: the diary is usually written late at night about the evening that just ended. Concretely, `entry_date` = (local timestamp minus 4 hours), date part only.

## Classes

One package, split by what a class does rather than by what kind of thing it is.

| | |
|---|---|
| `Main` | configuration, startup checks, graceful shutdown |
| `DiaryBot` | the owner filter, routing, trigger words, and the `/summary` request |
| `Onboarding` | the one-time setup of the two names, and `/who` |
| `DayFlow` | closing a day: the question, the feeling, the mark, the card |
| `UndoRequest` | the two steps of `/undo` |
| `Feed` | the diary as one column, today first |
| `Reminder` | when to ask about the day |
| `SummaryService` | the model input and the summary |
| `OllamaClient` | HTTP to the local model |
| `Telegram` | everything the bot sends, and the 4096 split |
| `Messages`, `Lang` | every string the owner sees, in their language |
| `DiaryRepository`, `Settings` | SQLite, and the typed view over its settings table |
| `Entry`, `DayCard`, `DayClosing`, `Feeling`, `DiaryDay`, `Config` | the small types |

Each conversation owns its own state and its own constants. `DiaryBot` was 807 lines holding three
state machines at once — the setup, the pending `/undo` and the day being closed, six mutable fields
between them; splitting by conversation brought it to 430 and left every piece of state next to the
behaviour that reads it. Constants were deliberately **not** gathered into a class of their own: the
undo window, the name limit and the callback prefixes are each used by exactly one conversation, and
a shared constants class would separate them from the only code that cares.

## Tests

`./mvnw test` is the safety net. JUnit 5, 98 tests, no network and no Telegram: `BotHarness` drives a real `DiaryBot` against a temporary database with a recording client in place of the Telegram API, so a whole conversation — messages, button taps, restarts — runs in milliseconds.

- The schedule of the evening reminder and the Russian plural forms are tested as pure functions, because that is what makes them testable at all without waiting for 21:00.
- The three tests that need the model live in `SummaryWithModelTest` and are **skipped, not failed**, when no Ollama answers: the build has to pass on a machine that is not running a model. Everything the summary does before calling the model — refusing an empty period, refusing a period too long for the window, the token estimate, the localized period — is tested without it.
- A few tests still reach into private state by reflection (the reminder's tick). Those are the places the planned refactor should give a proper seam.

## Stack and decisions

- Java 21, Maven. JUnit 5 and surefire for tests; `./mvnw test` must be green before anything is called done.
- Telegram: TelegramBots library (current version), long polling; no webhooks or open ports needed.
- Storage: SQLite (`sqlite-jdbc`), a single `diary.db` file; no separate database server.
- Model: Ollama over HTTP (`/api/chat`, `stream: false`), starting with `qwen2.5:7b`, later compare with a 14B model or Gemma 3 12B on real entries.
- Context window: `num_ctx` 16384 (env `OLLAMA_NUM_CTX`). `qwen2.5:7b` supports 32k, so raising the window is the cheap alternative to chunking — it only costs RAM for the KV cache. At 16384 tokens roughly four months of entries fit into a single request, which is why weekly chunking (stage 5) is conditional and not part of the MVP.
- HTTP and JSON: `java.net.http.HttpClient` + Jackson.
- Configuration through environment variables only.
- Runs on the user's PC. If the PC sleeps, Telegram keeps pending messages for up to 24 hours and the bot processes them after wake-up.

## Configuration (env)

| Variable | Purpose |
|---|---|
| `TELEGRAM_BOT_TOKEN` | token from @BotFather |
| `ALLOWED_USER_ID` | the owner's Telegram ID; all other users are ignored |
| `OLLAMA_URL` | default `http://localhost:11434` |
| `OLLAMA_MODEL` | default `qwen2.5:7b` |
| `OLLAMA_NUM_CTX` | context window, default `16384` |
| `DB_PATH` | default `./data/diary.db` |
| `BACKUP_DIR` | where the daily copies of the database go (`diary-YYYY-MM-DD.db`, last 14 kept); default: `backups/` next to the database |
| `DIARY_TIMEZONE` | IANA zone for the diary day, e.g. `Europe/Berlin`; default: the system zone |

The token and the database never go into git (`.gitignore`: `data/`, `*.db`, `.env`).

## Data

```sql
CREATE TABLE entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  entry_date TEXT NOT NULL,      -- diary date of the entry (04:00 rule), YYYY-MM-DD
  created_at TEXT NOT NULL,      -- ISO timestamp
  text TEXT NOT NULL
);

CREATE INDEX idx_entries_date ON entries(entry_date);

CREATE TABLE settings (           -- owner preferences: language, names, reminder time, chat id
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE day_cards (          -- the per-day card around that day's entries
  entry_date TEXT PRIMARY KEY,    -- same diary date as entries.entry_date
  feeling TEXT,                   -- one of the five, NULL until asked
  talked INTEGER,                 -- 1, 0 or NULL: was there a conversation that day
  confirmed_at TEXT               -- NULL while the card is still a draft
);

CREATE TABLE summaries (          -- cache of weekly summaries
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  period_from TEXT NOT NULL,
  period_to TEXT NOT NULL,
  model TEXT NOT NULL,           -- the model that produced this text
  cache_key TEXT NOT NULL,       -- entries + model + prompt, see below
  text TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(period_from, period_to, model, cache_key)
);
```

Entries stay the append log: one row per message, with its own timestamp, which is what lets the feed show when during the day something was written and lets `/undo` remove just the last piece. A day's card is the day's entries read together plus the row in `day_cards`. Keeping them apart means no existing entry has to be rewritten to introduce cards.

Settings keys in use: `language`, `owner_name`, `person_name`, `remind_at` (HH:MM local, default 21:00, or `off`), `reminded_on` (the diary day the reminder has dealt with) and `chat_id` (where to send the evening reminder, learned from the first message).

Those keys are strings in exactly one place, `Settings`, a typed view over the table: `language()` returns a `Lang`, `remindAt()` a `LocalTime` or empty when switched off, `chatId()` a `Long`. The raw `setting`/`putSetting` on the repository are package-private behind it, so a new key cannot quietly appear inside some handler. Before this the keys sat in whichever class needed them first, which had `SummaryService` reading a key off `DiaryBot` to learn the person's name.

`cache_key` is the SHA-256 of, in this exact order:

1. every entry of the period as `id|created_at|text`, ordered by `id`, joined with `\n`;
2. `OLLAMA_MODEL`;
3. the contents of the system prompt resource file.

The model and the prompt are part of the key on purpose: stage 4 consists of swapping models and rewriting the prompt, and a key built from the entries alone would keep serving a cached summary produced by the old model with no sign that it is stale. A changed model or prompt simply misses the cache and recomputes.

## Bot behavior

- On first contact the bot asks for a reply language with two flag buttons and waits for the answer. A plain message sent before that is still saved, and acknowledged in both languages: a settings screen must not cost the owner their first thought.
- A plain text message is saved as an entry for the current diary day (04:00 rule); the bot replies with a short acknowledgement ("Saved").
- `/new` runs a guided intake: who the conversation was with, what happened, what was concluded, how the author feels. Each answer is written into the database as it arrives, not held until the end, so an interview abandoned halfway keeps what was already said. `/skip` leaves a question out, `/cancel` drops the entry and deletes the draft. Other commands are held back while it runs. An interview untouched for 30 minutes is dropped, and the next message starts a new entry rather than becoming a stale answer.
- The answers are stored as one entry, one labelled line per answer, so a summary sees the structure.
- `/language` reopens the language buttons.
- `/summary` — summary of the last 7 days.
- `/summary N` — summary of the last N days.
- `/summary all` — summary of everything.
- `/date YYYY-MM-DD <text>` — save an entry under an explicit date, for catching up on a missed day or fixing a mis-dated one.
- `/last` — show the most recent entry.
- `/undo` — delete the most recent entry (with confirmation).
- `/help` — list of commands.
- Messages from any other user are ignored silently, with no reply.
- Long replies are split into chunks of at most 4096 characters (Telegram limit).
- While the model is working, show the "typing…" indicator.
- Only one model request runs at a time (queue or semaphore).
- If Ollama is unavailable or the request times out (5 minutes), reply with a clear message and keep running; never crash.

## Summary prompt

System prompt (keep it in a separate resource file so it is easy to edit):

> You help keep a personal diary about the author's relationship with one person. You are given diary entries grouped by date. Write a summary that covers: 1) what happened; 2) recurring themes and agreements, and whether the agreements were kept; 3) how the author's state changed over time; 4) an answer to the author's main question, "How do I preserve myself?", based only on the entries: name concrete observations and ask 2-3 questions for reflection. Do not diagnose anyone, do not pass judgment on the other person, and do not invent facts that are not in the entries. If there is too little data, say so. Write the summary in the same language as the entries.

Call parameters go inside the `options` object of the request body — `num_ctx` and `temperature` at the top level are **not** an error, they are silently ignored, and the request then runs with the model's default window (2048-4096) and silently truncates long input:

```json
{
  "model": "qwen2.5:7b",
  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "..."}],
  "stream": false,
  "keep_alive": "30m",
  "options": {"num_ctx": 16384, "temperature": 0.3}
}
```

The generic instructions of that prompt are not enough for a 7B model: on real entries `qwen2.5:7b` answered in English although the entries were Russian, pasted the entries back instead of summarizing them, and wrapped everything in Markdown headings that show up as literal asterisks because replies are sent without a parse mode. So the code appends a closing directive to the input, right before generation, naming the language detected from the entries (Cyrillic against Latin letters), forbidding any copying of the entries, and asking for plain text in four labelled parts. The prompt file keeps the wording above; the directive is what makes it hold.

Estimating whether a period fits: entries are in Russian, which `qwen2.5` tokenizes at roughly **2.2 characters per token** (English is about 4 — calibrating on English underestimates the input by nearly half). So `tokens ≈ chars / 2.2`, counting the entries, the system prompt and the date headers, and **reserve 1500 tokens for the reply**: `num_ctx` is the budget for input plus generation, not for input alone.

## Long periods (only if needed)

With `num_ctx` 16384 and the estimate above, a single request holds on the order of 7000 characters per month for about four months of entries, so for the first months of use `/summary all` is one plain call. Raising `num_ctx` further is the first thing to try; chunking is the fallback once even that stops fitting.

When the period genuinely does not fit:
1. Split the period into weeks, summarize each week, and store the result in `summaries`.
2. Build the final summary from the weekly summaries.
3. Recompute a weekly summary only when its `cache_key` changes (edited entries, a different model, or an edited prompt).
4. If the weekly summaries themselves stop fitting, group them into months and repeat — otherwise `/summary all` breaks again after a year or two.

## Stages

### Stage 1. Skeleton and environment check
- [x] Maven project, Java 21, dependencies, env-based configuration.
- [x] Ollama and the model are installed; a call from code works.
- [x] Done when: the app starts and answers `/help` in Telegram, only to the owner.

### Stage 2. Saving entries
- [x] Create the database and tables on startup; repository class.
- [x] Save plain messages; `/last`, `/undo`, `/date`.
- [x] Done when: entries survive a bot restart and messages from other users are ignored.

### Stage 3. Summaries (MVP)
- [x] Ollama client, prompt loaded from a file, `/summary [N|all]` for periods that fit into the context.
- [x] Splitting of long replies, "typing" indicator, error messages.
- [x] Done when: `/summary 7` on test entries returns a coherent answer that addresses the main question.

### Stage 4. Prompt and model tuning

Open findings, on synthetic entries with `qwen2.5:7b`:
- the model once called the other person "партнёр", which the entries never say, so the ban on invented facts needs watching; the stage 11 prompt no longer invites it, and the current tests assert that no impersonal label appears;
- the one-paragraph answer comes out shorter than the four to seven sentences asked for — two dense sentences for a week.
- The four-part findings from stage 3 are obsolete: that prompt was replaced in stage 11.

- [ ] A set of 5-10 test entries (no real personal data) for evaluation.
- [ ] Compare a 7B model with a 12-14B model and adjust the prompt to avoid invented facts and diagnoses.
- [ ] Done when: results on the test entries are consistently acceptable.

### Stage 5. Long periods and caching (conditional — only once a real period stops fitting)
- [ ] Check first whether raising `num_ctx` is enough; implement chunking only if it is not.
- [ ] Weekly summaries, cache keyed as described above, final summary built from them.
- [ ] Done when: `/summary all` works on 60+ test days without context truncation.

### Stage 6. Polish
- [ ] Daily backup of `diary.db` (script or built-in task).
- [ ] Autostart as a service (Windows: Task Scheduler / NSSM; Linux: systemd) with automatic restart on failure.
- [ ] Logs must not contain entry text (only IDs and lengths).
- [~] README: how to run is done; how to restore from a backup waits on the backup itself.

### Stage 7. Onboarding and names
- [x] After the language, ask how to address the owner and who the diary is about; store both in `settings`.
- [x] `/who` shows the two names and asks them again; the summary prompt names the person instead of saying "the other person". The reminder and the card pick up the owner's name in stages 8-9.
- [x] Done when: a fresh database walks through language, owner name and person name once, and never asks again.

### Stage 8. The day card
- [x] `day_cards` table; a day's messages accumulate into one draft card instead of standing alone.
- [x] Feeling asked with five buttons, the conversation mark with two; both stored on the card.
- [x] The assembled card is shown for confirmation, with a way back to editing; `/new` starts the flow at once instead of waiting for the evening.
- [x] The stage-3 `/new` interview is removed, with its four message keys.
- [x] Done when: three messages during one day plus a feeling and a mark end up as one confirmed card.
- A message written after the card is confirmed joins the day but leaves the card confirmed, so the evening reminder does not come back for a day already closed.

### Stage 9. The evening reminder
- [x] `remind_at` setting, default 21:00 local, changed with a command.
- [x] A scheduler sends "Что осталось от сегодняшнего дня?" to the stored `chat_id`, and stays silent if the day's card is already confirmed.
- [x] Survives a restart and does not fire twice for the same day; a PC asleep at the reminder time means the reminder is skipped, not queued.
- [x] Done when: the reminder arrives once at the chosen time and the answer lands in the day's draft.
- `/remind` shows the time, `/remind 22:15` moves it, `/remind off` stops it. `chat_id` is learned from the first message the owner sends and rewritten only when it changes.
- Whether it is time is `Reminder.decide`, a pure function, and the current moment is a parameter of the tick rather than read inside it. The schedule is the part worth being certain about, and waiting until 21:00 is no way to test it.
- Late is decided by a one-hour window: woken at 21:30 the bot still asks, woken at 23:00 it marks the day handled and says nothing. `reminded_on` holds the diary date, so a restart does not re-ask.
- A reminder set before 04:00 belongs to the night that ends the diary day, not the one that starts it: `/remind 02:00` asks about the day just finishing.
- A closing now expires when its diary day is no longer the current one, instead of after a fixed 30 minutes. The reminder's question may be answered hours later and that is still an answer about that evening; the only protection that mattered — tomorrow's first thought being its own entry — is kept.

### Stage 10. The feed
- [x] `лента` (and `/feed`) prints today's card first, then earlier cards grouped by month, newest month first.
- [x] Split across messages at card boundaries, never mid-card.
- [x] Done when: a diary of 60+ test days reads as one column without a truncated card.
- A bare word that acts as a command (`лента`, later `итог`) is matched only on an exact, case-insensitive match of the trigger for the current language, and is rewritten to its slash form so it travels the same path — including being held back while a day is being closed. "лента вчера была другая" stays an entry.
- `/feed N` limits the feed to the last N days. A diary of years would otherwise arrive in full every time.
- Counts in replies go through `Messages.plural`, which picks the Russian form arithmetically (1 день, 2 дня, 5 дней). A key whose forms are not nominative says so in its name, as `within-minute` does for "в течение ...".

### Stage 11. The short summary
- [x] `итог` (and `/summary`) triggers it; the prompt is rewritten for one paragraph instead of four parts.
- [x] The answer ends with the diary's question, in the owner's language, and uses the person's name rather than "the other person".
- [x] Feeling and the conversation mark are part of the model input, not just decoration in the feed.
- [x] Done when: on test cards the answer is a paragraph a person actually reads, and it ends with the question.
- The question is appended by the code, not asked of the model: it has to be this exact sentence every time, and a 7B model rephrases whatever it is told to close with. The prompt instead forbids a closing question of its own.
- A period shown to the owner goes through `SummaryService.format`, which follows their language ("1 октября — 7 октября"). `Period.toString` stays ISO and belongs to the log.
- On test cards `qwen2.5:7b` answers in two dense sentences where the prompt asks for four to seven. It covers what it should, so this is a tuning item for stage 4 rather than a defect.

## Security and privacy

- Access only for `ALLOWED_USER_ID`.
- Telegram bots do not use end-to-end encryption; this is accepted knowingly. The database is stored locally, preferably on an encrypted disk.
- Never log entry text; never commit the token.
- Keep backups in an encrypted location.

## What is left

`CLAUDE.md` holds the conventions that are easy to get wrong. `TASKS.md` holds the same work as
ready-to-run prompts, with notes on which can run in parallel. `BACKLOG.md` holds the open work: the remaining stages, the refactoring still worth doing, the gaps
in the product's logic and the user interactions that need attention, ordered by what it costs to
leave them undone.

## Out of scope for the MVP

- Multiple users, a web UI, voice messages, search across the diary, Docker.
- Reminders to write were out of scope for the MVP and came back in stage 9, as the evening question is what makes the diary a habit.

## Time estimate

MVP (stages 1-3): about 10-16 hours in Java. The full version for daily use (stages 4-6): about the same again. The least predictable part is prompt and model tuning.

## Definition of done

- The user writes the diary daily and `/summary` gives useful output.
- The bot survives PC restarts and sleep.
- A working backup exists and restoring from it has been tested.