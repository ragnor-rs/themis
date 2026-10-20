import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.models.embeddings.EmbeddingModel
import io.pinecone.clients.Index
import io.pinecone.clients.Pinecone
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/** One clause of a regulation, with the structural context needed to cite it in a report. */
data class Clause(
    val chapterNumber: String,
    val chapterTitle: String,
    val sectionNumber: String,
    val sectionTitle: String,
    val articleNumber: String,
    val articleTitle: String,
    val clauseNumber: String,
    val description: String,
)

/**
 * A regulation, the Pinecone index holding its clauses, and the EUR-Lex document it is ingested from.
 *
 * `celexId` is the CELEX identifier the ingest tool resolves against the EU Publications Office.
 */
enum class VectorDbIndex(
    val indexNameEnvVar: String,
    val celexId: String,
    val description: String,
) {
    Dsa("PINECONE_INDEX_DSA_NAME", "32022R2065", "EU Digital Services Act"),
    Gdpr("PINECONE_INDEX_GDPR_NAME", "32016R0679", "EU General Data Protection Regulation"),
    ;

    /**
     * Read on access rather than at class initialisation, so that tools which do not touch Pinecone
     * (`ingest --dry-run`) run without it configured, and so that a missing variable is reported by
     * [checkRequiredEnvVars] rather than by an enum initialiser failure.
     */
    val indexName: String get() = requireEnv(indexNameEnvVar)
}

/** Dimension of OpenAI `text-embedding-3-large`; the Pinecone indexes must match it. */
const val EMBEDDING_DIMENSION = 3072

/**
 * Rebuilds a [Clause] from a search result's metadata.
 *
 * Every field is required: a clause missing one would be cited without the context that makes it
 * traceable, so this fails loudly rather than reporting against a half-identified clause.
 */
fun ScoredVectorWithUnsignedIndices.toClause(): Clause = metadata.let {
    Clause(
        chapterNumber = it.getFieldsOrThrow("chapter_number").stringValue,
        chapterTitle = it.getFieldsOrThrow("chapter_title").stringValue,
        sectionNumber = it.getFieldsOrThrow("section_number").stringValue,
        sectionTitle = it.getFieldsOrThrow("section_title").stringValue,
        articleNumber = it.getFieldsOrThrow("article_number").stringValue,
        articleTitle = it.getFieldsOrThrow("article_title").stringValue,
        clauseNumber = it.getFieldsOrThrow("clause_number").stringValue,
        description = it.getFieldsOrThrow("description").stringValue,
    )
}

private val logDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

fun log(s: String) {
    println(logDateFormatter.format(Date()) + " - " + s)
}

fun requireEnv(name: String): String =
    System.getenv(name) ?: error("Missing required environment variable: $name")

fun optionalEnv(name: String, default: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: default

/** Reports every missing variable at once, so a misconfigured run does not need five restarts to fix. */
fun checkRequiredEnvVars(required: List<String>) {
    val missing = required.filter { System.getenv(it).isNullOrBlank() }
    if (missing.isEmpty()) {
        return
    }
    System.err.println("Missing required environment variable(s): " + missing.joinToString(", "))
    System.err.println("Copy .env.example to .env, fill it in, then: set -a && . ./.env && set +a")
    System.err.println("See the Configuration section of README.md.")
    exitProcess(1)
}

// Lazy so that checkRequiredEnvVars() reports a missing OPENAI_API_KEY before the SDK does.
val openAi by lazy { OpenAIOkHttpClient.fromEnv() }

// Lazy for the same reason, and shared: a Pinecone client owns connection pools and is not free to build.
private val pinecone by lazy { Pinecone.Builder(requireEnv("PINECONE_API_KEY")).build() }

private val indexConnections = ConcurrentHashMap<String, Index>()

/**
 * Returns a cached gRPC connection to [indexName].
 *
 * Connections are reused because each one opens a channel that is never reclaimed on its own; a single
 * document fans out to dozens of queries, which previously leaked a connection apiece.
 */
fun indexConnection(indexName: String): Index =
    indexConnections.computeIfAbsent(indexName) { pinecone.getIndexConnection(it) }

fun closeIndexConnections() {
    indexConnections.values.forEach { runCatching { it.close() } }
    indexConnections.clear()
}

fun pineconeClient(): Pinecone = pinecone

/** Largest batch sent to the embeddings endpoint in one request. */
private const val EMBEDDING_BATCH_SIZE = 96

fun generateEmbeddings(text: String): List<Float> = embedAll(listOf(text)).single()

/** Embeds [texts] in order, batching requests to keep the clause count from becoming the request count. */
fun embedAll(texts: List<String>): List<List<Float>> =
    texts.chunked(EMBEDDING_BATCH_SIZE).flatMap { batch ->
        val params = EmbeddingCreateParams.builder()
            .input(EmbeddingCreateParams.Input.ofArrayOfStrings(batch))
            .model(EmbeddingModel.TEXT_EMBEDDING_3_LARGE)
            .build()

        // The API is documented to preserve input order, but it also returns an explicit index; sort by
        // it so a reordered response can never silently pair a clause with another clause's vector.
        openAi.embeddings().create(params).data()
            .sortedBy { it.index() }
            .map { it.embedding() }
    }
