import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.document
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.*
import com.openai.models.chat.completions.ChatCompletionContentPart.File.FileObject
import com.openai.models.embeddings.EmbeddingCreateParams
import com.openai.models.embeddings.EmbeddingModel
import io.pinecone.clients.Pinecone
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.*

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

enum class UserStage {
    Initial,
    Questions,
}

enum class VectorDbIndex(
    val indexName: String,
    val description: String,
) {
    Dsa(System.getenv("PINECONE_INDEX_DSA_NAME"), "EU Digital Services Act"),
    Gdpr(System.getenv("PINECONE_INDEX_GDPR_NAME"), "EU General Data Protection Regulation"),
}

data class Question(
    val question: String,
    val index: VectorDbIndex,
)

data class Reply(
    val question: Question,
    val reply: String,
)

data class UserState(
    val stage: UserStage,
    val questions: List<Question>,
    val step: Int,
    val replies: List<Reply>,
    val clauseMap: Map<VectorDbIndex, List<Clause>>,
    val prdBase64: String,
) {
    companion object {
        fun createInitial(): UserState {
            return UserState(UserStage.Initial, emptyList(), -1, emptyList(), emptyMap(), "")
        }
    }
}

fun main() {
    val userStateMap = mutableMapOf<Long, UserState>()

    fun getUserState(message: Message): UserState = userStateMap.getOrPut(
        message.chat.id,
        { UserState.createInitial() },
    )

    fun summarize(message: Message, bot: Bot, state: UserState) {
        val summaryMap = mutableMapOf<VectorDbIndex, String>()

        for (index in VectorDbIndex.entries) {
            val indexSummary = generateIndexSummary(
                state.prdBase64,
                state.clauseMap,
                state.replies,
                index,
            )
            summaryMap.put(index, indexSummary)
        }

        val finalSummary = unifySummaries(summaryMap)

        bot.sendMessage(ChatId.fromId(message.chat.id), text = finalSummary)
    }

    fun askNextQuestion(message: Message, bot: Bot) {
        val state = getUserState(message)
        val nextState = state.copy(state.stage, state.questions, state.step + 1)
        if (nextState.questions.size >= nextState.step) {
            summarize(message, bot, nextState)
            userStateMap.put(message.chat.id, UserState.createInitial())
        } else {
            bot.sendMessage(ChatId.fromId(message.chat.id), text = nextState.questions[nextState.step].question)
            userStateMap.put(message.chat.id, nextState)
        }
    }

    val bot = bot {
        token = System.getenv("TELEGRAM_BOT_TOKEN")
        dispatch {
            text {
                val state = getUserState(message)

                if (state.stage == UserStage.Questions) {
                    // 6) additional data
                    val nextState = state.copy(
                        state.stage,
                        state.questions,
                        state.step,
                        state.replies + Reply(state.questions[state.step], message.text!!),
                    )
                    userStateMap.put(message.chat.id, nextState)

                    askNextQuestion(message, bot)
                }
            }
            document {
                val state = getUserState(message)

                if (state.stage == UserStage.Initial) {

                    // 1) PRD doc
                    val prdFile = bot.downloadFileBytes(media.fileId)
                    val prdBase64 = convertByteArrayToBase64(prdFile!!)

                    // 2) product features
                    val output = promptWithPdfBase64(
                        prdBase64,
                        "Give a list of product features. Plain text. One level list.\nEach item must have a name and a detailed description.",
                        media.fileName!!
                    )
                    val items = toNonEmptyItems(output)

                    val questions = mutableListOf<Question>()

                    for (index in VectorDbIndex.entries) {
                        val clauses = mutableListOf<Clause>()
                        for (productFeature in items) {
                            // 3) embeddings
                            val results = queryVectorDb(productFeature, index.indexName)

                            // 4) relevant clauses
                            for (result in results) {
                                result.metadata.let {
                                    val clause = Clause(
                                        it.getFieldsOrThrow("chapter_number").stringValue,
                                        it.getFieldsOrThrow("chapter_title").stringValue,
                                        it.getFieldsOrThrow("section_number").stringValue,
                                        it.getFieldsOrThrow("section_title").stringValue,
                                        it.getFieldsOrThrow("article_number").stringValue,
                                        it.getFieldsOrThrow("article_title").stringValue,
                                        it.getFieldsOrThrow("clause_number").stringValue,
                                        it.getFieldsOrThrow("description").stringValue,
                                    )
                                    clauses.add(clause)
                                }
                            }

                            // 5) questions
                            val indexQuestions = generateQuestions(prdBase64, clauses).map { Question(it, index) }
                            questions.addAll(indexQuestions)
                        }
                    }

                    userStateMap.put(message.chat.id, state.copy(UserStage.Questions, questions))

                    askNextQuestion(message, bot)
                }
            }
        }
    }

    bot.startPolling()
}

// TODO generateQuestions
fun generateQuestions(
    prdBase64: String,
    clauses: List<Clause>
): List<String> {
    return listOf("What's up?")
}

// TODO generateIndexSummary
fun generateIndexSummary(
    prdBase64: String,
    clauseMap: Map<VectorDbIndex, List<Clause>>,
    replies: List<Reply>,
    index: VectorDbIndex,
): String {
    val indexReplies = replies.filter { it.question.index == index }
    val clauses = clauseMap.getOrElse(index, { emptyList() })
    return "No summary for ${index.description}"
}

// TODO unifySummaries
fun unifySummaries(summaryMap: MutableMap<VectorDbIndex, String>): String {
    return "No summary, sorry!"
}

val client = OpenAIOkHttpClient.fromEnv()

/**
 * Converts a file to Base64 string
 * @param filePath Path to the file to convert
 * @return Base64 encoded string representation of the file
 * @throws IOException if file cannot be read
 */
@Throws(IOException::class)
fun convertFileToBase64(filePath: String): String? {
    val file = File(filePath)

    // Check if file exists
    if (!file.exists()) {
        throw IOException("File not found: $filePath")
    }

    // Read file bytes
    val fileBytes = ByteArray(file.length().toInt())
    FileInputStream(file).use { fis ->
        fis.read(fileBytes)
    }

    // Convert to Base64
    return convertByteArrayToBase64(fileBytes)
}

private fun convertByteArrayToBase64(fileBytes: ByteArray): String = Base64.getEncoder().encodeToString(fileBytes)

fun promptWithPdfBase64(base64Content: String?, prompt: String, filename: String): String {
    // Create PDF attachment with data URI
    val fileObject: FileObject =
        FileObject.builder().fileData("data:application/pdf;base64,$base64Content").filename(filename).build()
    val file: ChatCompletionContentPart.File = ChatCompletionContentPart.File.builder().file(fileObject).build()
    val filePart = ChatCompletionContentPart.ofFile(file)

    val text = ChatCompletionContentPartText.builder().text(prompt).build()
    val textPart = ChatCompletionContentPart.ofText(text)

    // Create user message with text prompt and PDF attachment
    val userMessage = ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(listOf(filePart, textPart)).build()
    )

    // Create chat completion request
    val params = ChatCompletionCreateParams.builder()
        .model(ChatModel.Companion.GPT_4O)
        .messages(listOf(userMessage))
        .build()

    val completion = client.chat().completions().create(params)
    return completion.choices()[0].message().content().orElse("")
}

fun toNonEmptyItems(text: String): List<String> {
    return text.split("\n").map { it -> it.trim() }.filter { it -> it.isNotEmpty() }
}

fun generateEmbeddings(text: String): List<Float> {
    val createParams = EmbeddingCreateParams.builder()
        .input(text)
        .model(EmbeddingModel.Companion.TEXT_EMBEDDING_3_LARGE)
        .build()

    return client.embeddings().create(createParams).data()[0].embedding()
}

fun queryVectorDb(text: String, indexName: String): List<ScoredVectorWithUnsignedIndices> {
    val embeddings = generateEmbeddings(text)
    val pinecone = Pinecone.Builder(System.getenv("PINECONE_API_KEY")).build()
    val index = pinecone.getIndexConnection(indexName)
    val response = index.queryByVector(5, embeddings, true, true)
    return response.matchesList
}