import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.pinecone.commons.IndexInterface.buildUpsertVectorWithUnsignedIndices
import org.openapitools.db_control.client.model.DeletionProtection
import kotlin.system.exitProcess

/**
 * Populates the Pinecone indexes the bot reads from.
 *
 * Run once before the first `./gradlew run`:
 *
 * ```sh
 * ./gradlew ingest
 * ```
 *
 * For each regulation it downloads the text from the EU Publications Office, splits it into clauses,
 * creates the index if it does not exist, then embeds and upserts the clauses in batches. Vector ids are
 * derived from a clause's position in the regulation, so re-running overwrites rather than duplicating,
 * and an interrupted run can be repeated without creating a second copy of anything.
 *
 * `--dry-run` downloads and parses but contacts neither OpenAI nor Pinecone, so it needs no credentials
 * and costs nothing. Use it to check the parser still matches the published documents.
 */

/** Pinecone serverless placement. The defaults are the region offered on the free tier. */
private val cloud get() = optionalEnv("PINECONE_CLOUD", "aws")
private val region get() = optionalEnv("PINECONE_REGION", "us-east-1")

/**
 * Clauses embedded and upserted per round trip.
 *
 * At 3072 floats a vector is ~12 KB, so 50 stays well inside Pinecone's 2 MB request limit, and the
 * embeddings endpoint takes the whole batch in one call.
 */
private const val BATCH_SIZE = 50

private const val INDEX_READY_TIMEOUT_SECONDS = 300L

/**
 * Pinecone's default namespace, which is what the bot's queries read.
 *
 * It has to be the empty string rather than null: the batch upsert overload documents the namespace as
 * optional but passes it straight to the protobuf builder, which rejects null.
 */
private const val DEFAULT_NAMESPACE = ""

fun main(args: Array<String>) {
    val dryRun = args.contains("--dry-run")
    val verifyOnly = args.contains("--verify")

    if (!dryRun) {
        checkRequiredEnvVars(
            listOf("OPENAI_API_KEY", "PINECONE_API_KEY") + VectorDbIndex.entries.map { it.indexNameEnvVar }
        )
    } else {
        log("Dry run: downloading and parsing only, no OpenAI or Pinecone calls")
    }

    try {
        if (verifyOnly) {
            VectorDbIndex.entries.forEach { verify(it) }
            log("Both indexes answer queries and carry every metadata field the bot requires")
            return
        }

        var total = 0
        for (regulation in VectorDbIndex.entries) {
            total += ingest(regulation, dryRun)
        }
        log("Done: $total clauses across ${VectorDbIndex.entries.size} regulations")
        if (dryRun) {
            log("Dry run only — nothing was written. Re-run without --dry-run to populate Pinecone.")
        } else {
            VectorDbIndex.entries.forEach { verify(it) }
        }
    } catch (e: Exception) {
        System.err.println("Ingest failed: ${e.message}")
        e.printStackTrace()
        exitProcess(1)
    } finally {
        closeIndexConnections()
    }
}

private fun ingest(regulation: VectorDbIndex, dryRun: Boolean): Int {
    log("${regulation.description} — downloading CELEX ${regulation.celexId}")
    val xhtml = fetchRegulationXhtml(regulation.celexId)
    log("${regulation.description} — downloaded ${xhtml.length} characters")

    val clauses = parseClauses(xhtml)
    check(clauses.isNotEmpty()) {
        "Parsed no clauses out of CELEX ${regulation.celexId}. The published document's markup may have " +
            "changed; see parseClauses in EurLex.kt."
    }
    reportParse(regulation, clauses)

    if (dryRun) {
        printSamples(clauses)
        return clauses.size
    }

    val indexName = regulation.indexName
    ensureIndex(indexName)

    val connection = indexConnection(indexName)
    var upserted = 0
    clauses.chunked(BATCH_SIZE).forEach { batch ->
        val vectors = embedAll(batch.map { it.embeddingText() })
        check(vectors.size == batch.size) {
            "Embedded ${vectors.size} vectors for ${batch.size} clauses"
        }
        vectors.forEach { vector ->
            check(vector.size == EMBEDDING_DIMENSION) {
                "Embedding dimension is ${vector.size}, but the index expects $EMBEDDING_DIMENSION"
            }
        }

        connection.upsert(
            batch.zip(vectors).map { (clause, vector) ->
                buildUpsertVectorWithUnsignedIndices(clause.vectorId(), vector, null, null, clause.toMetadata())
            },
            DEFAULT_NAMESPACE,
        )

        upserted += batch.size
        log("${regulation.description} — upserted $upserted/${clauses.size} into '$indexName'")
    }

    return clauses.size
}

private fun reportParse(regulation: VectorDbIndex, clauses: List<Clause>) {
    val recitals = clauses.count { it.chapterNumber == "0" }
    val articleClauses = clauses.size - recitals
    val articles = clauses.filter { it.chapterNumber != "0" }.map { it.articleNumber }.distinct().size
    val characters = clauses.sumOf { it.embeddingText().length }
    log(
        "${regulation.description} — parsed ${clauses.size} clauses: " +
            "$recitals recitals, $articleClauses clauses across $articles articles, " +
            "$characters characters to embed"
    )

    // A blank body or a missing article number would poison a vector silently, so fail the run instead.
    val malformed = clauses.filter { it.description.isEmpty() || it.articleNumber.isEmpty() }
    check(malformed.isEmpty()) {
        "${malformed.size} parsed clauses are missing an article number or body, e.g. ${malformed.first()}"
    }

    val duplicates = clauses.groupBy { it.vectorId() }.filterValues { it.size > 1 }
    check(duplicates.isEmpty()) {
        "Clause ids are not unique, so clauses would overwrite each other: " +
            duplicates.keys.take(5).joinToString() + " (${duplicates.size} in total)"
    }
}

/** A query that should retrieve something from either regulation, used only to prove retrieval works. */
private const val VERIFICATION_QUERY =
    "The service collects personal data from users and shows them recommended content."

/**
 * Queries an index the way the bot does and rebuilds the results into clauses.
 *
 * This is the one check that covers the whole round trip — embedding, retrieval, and every metadata field
 * being present, since [toClause] throws on a missing one. Running it here means a broken index is caught
 * now rather than minutes into someone's first report.
 */
private fun verify(regulation: VectorDbIndex) {
    val matches = queryVectorDb(generateEmbeddings(VERIFICATION_QUERY), regulation.indexName)
    check(matches.isNotEmpty()) {
        "Index '${regulation.indexName}' returned no matches. Run './gradlew ingest' to populate it."
    }

    val clauses = matches.map { it.toClause() }
    log("${regulation.description} — '${regulation.indexName}' returned ${clauses.size} clauses, e.g.:")
    clauses.take(2).forEach {
        log(
            "    Article ${it.articleNumber}(${it.clauseNumber}) '${it.articleTitle}' — " +
                it.description.take(140).replace("\n", " ⏎ ") + "…"
        )
    }
}

/**
 * Prints one clause of each shape so a dry run can be eyeballed: a recital, a numbered paragraph, and an
 * article taken whole because it has no numbered paragraphs.
 */
private fun printSamples(clauses: List<Clause>) {
    val samples = listOfNotNull(
        "recital" to clauses.firstOrNull { it.chapterNumber == "0" },
        "numbered paragraph" to clauses.firstOrNull { it.chapterNumber != "0" && it.clauseNumber != "1" },
        "whole article" to clauses.lastOrNull { it.chapterNumber != "0" && it.description.contains("(b)") },
        "inside a section" to clauses.firstOrNull { it.sectionTitle.isNotEmpty() },
    )
    for ((shape, clause) in samples) {
        if (clause == null) continue
        log(
            "  sample [$shape] id=${clause.vectorId()} " +
                "chapter='${clause.chapterNumber}. ${clause.chapterTitle}' " +
                "section='${clause.sectionNumber}. ${clause.sectionTitle}' " +
                "article='${clause.articleNumber}. ${clause.articleTitle}'"
        )
        log("    ${clause.description.take(300).replace("\n", " ⏎ ")}…")
    }
}

/** Creates the index at the dimension the bot's embeddings use, and waits for it to accept writes. */
private fun ensureIndex(indexName: String) {
    val pinecone = pineconeClient()
    val existing = pinecone.listIndexes().indexes.orEmpty().firstOrNull { it.name == indexName }

    if (existing != null) {
        val dimension = existing.dimension
        check(dimension == null || dimension == EMBEDDING_DIMENSION) {
            "Index '$indexName' has dimension $dimension, but $EMBEDDING_DIMENSION is required. Point the " +
                "environment variable at a different index, or delete this one and re-run."
        }
        log("Index '$indexName' already exists; clauses with the same ids will be overwritten")
        return
    }

    log("Creating index '$indexName' (dimension $EMBEDDING_DIMENSION, cosine, $cloud/$region)")
    pinecone.createServerlessIndex(
        indexName,
        "cosine",
        EMBEDDING_DIMENSION,
        cloud,
        region,
        DeletionProtection.DISABLED,
        emptyMap(),
    )

    val deadline = System.nanoTime() + INDEX_READY_TIMEOUT_SECONDS * 1_000_000_000L
    while (System.nanoTime() < deadline) {
        if (pinecone.describeIndex(indexName).status?.ready == true) {
            log("Index '$indexName' is ready")
            return
        }
        Thread.sleep(2_000)
    }
    error("Index '$indexName' was not ready within $INDEX_READY_TIMEOUT_SECONDS seconds")
}

/**
 * Text that gets embedded for a clause.
 *
 * The headings are included alongside the body because a clause routinely refers to its own article only
 * as "this Article", which would leave a query with nothing to match on.
 */
private fun Clause.embeddingText(): String =
    "$chapterTitle\n$sectionTitle\n$articleTitle\n$description"

/** Stable, so re-ingesting updates a clause in place instead of adding a second copy of it. */
private fun Clause.vectorId(): String =
    "${chapterNumber}_${sectionNumber}_${articleNumber}_$clauseNumber"

private fun Clause.toMetadata(): Struct = Struct.newBuilder()
    .putFields("chapter_number", chapterNumber.toValue())
    .putFields("chapter_title", chapterTitle.toValue())
    .putFields("section_number", sectionNumber.toValue())
    .putFields("section_title", sectionTitle.toValue())
    .putFields("article_number", articleNumber.toValue())
    .putFields("article_title", articleTitle.toValue())
    .putFields("clause_number", clauseNumber.toValue())
    .putFields("description", description.toValue())
    .build()

private fun String.toValue(): Value = Value.newBuilder().setStringValue(this).build()
