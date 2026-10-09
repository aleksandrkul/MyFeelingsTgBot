# MyFeelings Diary Bot

A personal Telegram diary bot for a single user. Entries are stored locally in SQLite, and period
summaries are produced by a local LLM through [Ollama](https://ollama.com) — nothing is sent to
external APIs.

## Requirements

- Java 21
- Ollama running locally with a model pulled, e.g. `ollama pull qwen2.5:7b`
- A bot token from [@BotFather](https://t.me/BotFather) and your own Telegram user ID

## Run

```sh
cp .env.example .env        # fill in TELEGRAM_BOT_TOKEN and ALLOWED_USER_ID
./mvnw package
set -a; . ./.env; set +a
java -jar target/diary-bot.jar
```

All configuration comes from environment variables; see `.env.example`.

## Tests

```sh
./mvnw test
```

Tests that need a model are skipped when Ollama is not running.

## Design

See [instruction.md](instruction.md) for the business logic, architecture and stage plan.
