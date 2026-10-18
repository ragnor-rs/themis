# Themis

A Telegram bot that reviews a product requirements document against EU regulation and returns a compliance report. Send it a PDF; it finds the relevant clauses of the Digital Services Act and the GDPR, asks you for the details the document leaves out, and replies with an HTML report.

> Themis is an experiment, not legal advice. The output is LLM-generated and should be treated as a starting point for review by someone qualified.

## How it works

1. You send a PDF to the bot.
2. It asks GPT-4o for the list of product features in the document.
3. For each feature, it creates an embedding and queries two Pinecone indexes — one for the DSA, one for the GDPR — for the five most relevant clauses.
4. From those clauses it generates questions covering what the document does not say, then drops the ones already answered by the document.
5. It asks you the remaining questions one at a time in the chat.
6. It writes a compliance assessment per regulation, merges them into one report, and sends it back as `report.html`.

State is held in memory per chat, so a restart loses in-progress conversations.

## Requirements

- JDK 21
- A Telegram bot token from [@BotFather](https://t.me/BotFather)
- An OpenAI API key
- A Pinecone API key, plus two populated indexes (see below)

## Pinecone index contract

The bot only reads from Pinecone — it does not populate it. **You need to build the two indexes yourself before the bot will work.** Each must be created with:

- **Dimension 3072**, to match OpenAI `text-embedding-3-large`
- Metric: cosine

Every vector is one clause of the regulation, embedded from its text, and must carry all eight of these string metadata fields. A missing field is a hard error, not a skipped clause:

| Field | Example |
| --- | --- |
| `chapter_number` | `III` |
| `chapter_title` | `Due diligence obligations for a transparent and safe online environment` |
| `section_number` | `1` |
| `section_title` | `Provisions applicable to all providers of intermediary services` |
| `article_number` | `11` |
| `article_title` | `Points of contact for recipients of the service` |
| `clause_number` | `1` |
| `description` | The clause text itself. |

Point `PINECONE_INDEX_DSA_NAME` and `PINECONE_INDEX_GDPR_NAME` at the resulting indexes.

## Configuration

Copy `.env.example` to `.env` and fill it in, then export it into your shell:

```sh
cp .env.example .env
# edit .env
set -a && . ./.env && set +a
```

| Variable | Purpose |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token |
| `OPENAI_API_KEY` | OpenAI API key |
| `PINECONE_API_KEY` | Pinecone API key |
| `PINECONE_INDEX_DSA_NAME` | Index holding DSA clauses |
| `PINECONE_INDEX_GDPR_NAME` | Index holding GDPR clauses |

## Running

```sh
./gradlew run
```

The bot starts long-polling and logs each step to stdout. Message it on Telegram with a PDF to begin.

## Costs

A single document fans out to a lot of API calls: one feature-extraction call, then an embedding, a Pinecone query and a question-generation call per feature per regulation, then a summary call per regulation. A document with ten features means roughly forty OpenAI calls, several of which resend the whole PDF. Watch your usage on the first few runs.

## Contributing

Issues and pull requests are welcome. Please open an issue first for anything substantial so we can agree on the approach before you write code.

## License

[MIT](LICENSE)
