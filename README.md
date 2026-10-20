# Themis

A Telegram bot that reviews a product requirements document against EU regulation and returns a compliance report. Send it a PDF; it finds the relevant clauses of the Digital Services Act and the GDPR, asks you for the details the document leaves out, and replies with an HTML report.

> Themis is an experiment, not legal advice. The output is LLM-generated and should be treated as a starting point for review by someone qualified.

## How it works

1. You send a PDF to the bot.
2. It asks GPT-4o for the list of product features in the document.
3. For each feature, it creates an embedding and queries two Pinecone indexes — one for the DSA, one for the GDPR — for the five most relevant clauses. Those indexes are built by `./gradlew ingest`, described below.
4. From those clauses it generates questions covering what the document does not say, then drops the ones already answered by the document.
5. It asks you the remaining questions one at a time in the chat.
6. It writes a compliance assessment per regulation, merges them into one report, and sends it back as `report.html`.

State is held in memory per chat, so a restart loses in-progress conversations.

## Requirements

- JDK 21 — the Gradle build downloads one if your default JDK is older
- A Telegram bot token from [@BotFather](https://t.me/BotFather)
- An OpenAI API key
- A Pinecone API key

Nothing needs to exist in Pinecone beforehand; `./gradlew ingest` creates and fills the indexes.

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
| `PINECONE_INDEX_DSA_NAME` | Index to hold the DSA clauses. Created if missing. |
| `PINECONE_INDEX_GDPR_NAME` | Index to hold the GDPR clauses. Created if missing. |
| `PINECONE_CLOUD` | Optional. Serverless cloud, `aws` by default. |
| `PINECONE_REGION` | Optional. Serverless region, `us-east-1` by default. |

## Loading the regulations

The bot only reads from Pinecone, so the indexes have to be filled once before it can answer anything:

```sh
./gradlew ingest
```

For each regulation this downloads the English text from the EU Publications Office, splits it into clauses, creates the index if it does not already exist, then embeds and upserts every clause. It takes a few minutes and costs a few cents in embeddings — about 1,100 clauses and 210,000 tokens in total.

To check the parser without spending anything, or without any credentials at all:

```sh
./gradlew ingest --args="--dry-run"
```

That downloads and parses the two documents, prints what it found and a sample clause of each shape, and stops before calling OpenAI or Pinecone.

Re-running is safe. A vector's id comes from the clause's position in the regulation, so a second run overwrites the same clauses instead of duplicating them, and an interrupted run can simply be repeated.

### What ends up in the index

One vector per clause, embedded from its headings plus its text, at **dimension 3072** (OpenAI `text-embedding-3-large`) with the **cosine** metric. Each carries eight string metadata fields, all of which the bot requires:

| Field | Example |
| --- | --- |
| `chapter_number` | `III` |
| `chapter_title` | `DUE DILIGENCE OBLIGATIONS FOR A TRANSPARENT AND SAFE ONLINE ENVIRONMENT` |
| `section_number` | `1` |
| `section_title` | `Provisions applicable to all providers of intermediary services` |
| `article_number` | `11` |
| `article_title` | `Points of contact for Member States’ authorities, the Commission and the Board` |
| `clause_number` | `1` |
| `description` | The clause text itself. |

The preamble recitals are loaded too. They state what the articles are for, which is what makes a retrieved article intelligible to the model. A recital has no chapter or section of its own, so it is filed under `chapter_number` `0` with `article_number` set to the recital number and `article_title` to `Recital (N)`.

Articles that sit directly under a chapter rather than inside a section get `section_number` `1` and an empty `section_title`.

## Running

```sh
./gradlew run
```

The bot confirms the token, starts long-polling and logs each step to stdout. Message it on Telegram with a PDF to begin. `/reset` abandons a conversation and starts over.

## Costs

Ingestion is cheap and happens once. Reports are not.

A single document fans out to a lot of API calls: one feature-extraction call, then an embedding, a Pinecone query and a question-generation call per feature per regulation, then a summary call per regulation. A document with ten features means roughly forty OpenAI calls, several of which resend the whole PDF. Watch your usage on the first few runs.

## Contributing

Issues and pull requests are welcome. Please open an issue first for anything substantial so we can agree on the approach before you write code.

## License

[MIT](LICENSE)
