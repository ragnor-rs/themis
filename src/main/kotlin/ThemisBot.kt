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
import kotlin.collections.plus

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
    val prdName: String,
) {
    fun nextStep(): UserState {
        return copy(step = step + 1)
    }

    fun addReply(text: String): UserState {
        return copy(replies = replies + Reply(questions[step], text))
    }

    fun switchToQuestions(questions: List<Question>, prdBase64: String, clauseMap: Map<VectorDbIndex, List<Clause>>, prdName: String): UserState {
        return copy(stage = UserStage.Questions, questions = questions, prdBase64 = prdBase64, clauseMap = clauseMap, prdName = prdName)
    }

    companion object {
        fun createInitial(): UserState {
            return UserState(UserStage.Initial, emptyList(), -1, emptyList(), emptyMap(), "", "")
        }
    }
}

fun log(s: String) {
    println(s)
}

fun main() {
    val userStateMap = mutableMapOf<Long, UserState>()

    fun getUserState(message: Message): UserState = userStateMap.getOrPut(
        message.chat.id,
    ) { UserState.createInitial() }

    fun summarize(message: Message, bot: Bot, state: UserState) {
        val summaryMap = mutableMapOf<VectorDbIndex, String>()

        VectorDbIndex.entries.forEachIndexed { i, index ->
            val indexReplies = state.replies.filter { it.question.index == index }
            val clauses = state.clauseMap.getOrElse(index) { emptyList() }

            // TODO embeddings

            // TODO relevant clauses

            log("6.${i+1}.3) summary - begin")
            val prompt = buildGenerateSummaryPrompt(clauses, index, indexReplies)
            val indexSummary = promptWithPdfBase64(state.prdBase64, prompt, state.prdName)
            log("6.${i+1}.3) summary - end")

            summaryMap.put(index, indexSummary)
        }

        log("7) join summaries - begin")
        val prompt = buildJoinSummariesPrompt(summaryMap)
        val finalSummary = promptWithPdfBase64(null, prompt, null)
        log("7) join summaries - end")

        bot.sendMessage(ChatId.fromId(message.chat.id), text = finalSummary)
    }

    fun askNextQuestion(message: Message, bot: Bot) {
        val state = getUserState(message)
        if (state.step == -1) {
            log("5) additional data - begin")
        }
        val nextState = state.nextStep()
        if (nextState.questions.size <= nextState.step) {
            log("5) additional data - end")
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
                    val nextState = state.addReply(message.text!!)
                    userStateMap.put(message.chat.id, nextState)

                    askNextQuestion(message, bot)
                }
            }
            document {
                val state = getUserState(message)

                if (state.stage == UserStage.Initial) {

                    log("1) PRD doc - begin")
                    val prdFile = bot.downloadFileBytes(media.fileId)
                    val prdBase64 = convertByteArrayToBase64(prdFile!!)
                    log("1) PRD doc - end")

                    log("2) product features - begin")
                    val output = promptWithPdfBase64(
                        prdBase64,
                        "Give a list of product features. Plain text. One level list.\nEach item must have a name and a detailed description.",
                        media.fileName!!
                    )
                    val items = toNonEmptyItems(output)
                    log("2) product features - end")

                    val questions = mutableListOf<Question>()
                    val clauseMap = mutableMapOf<VectorDbIndex, List<Clause>>()

                    VectorDbIndex.entries.forEachIndexed { i, index ->
                        val clauses = mutableListOf<Clause>()
                        items.forEachIndexed { j, productFeature ->
                            log("3.${i+1}.${j+1}.1) embeddings - begin")
                            val results = queryVectorDb(productFeature, index.indexName)
                            log("3.${i+1}.${j+1}.1) embeddings - end")

                            log("3.${i+1}.${j+1}.2) relevant clauses - begin")
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
                            log("3.${i+1}.${j+1}.2) relevant clauses - end")

                            log("3.${i+1}.${j+1}.3) question gen - begin")
                            val indexQuestions = generateQuestions(prdBase64, clauses).map { Question(it, index) }
                            questions.addAll(indexQuestions)
                            log("3.${i+1}.${j+1}.3) question gen - end")
                        }
                        clauseMap.put(index, clauses)
                    }

                    log("4) filter questions - begin")
                    val filteredQuestions = filterQuestions(prdBase64, questions)
                    log("4) filter questions - end")

                    userStateMap.put(message.chat.id, state.switchToQuestions(filteredQuestions, prdBase64, clauseMap, media.fileName!!))

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
    return emptyList() //listOf("What's up?")
}

// TODO filterQuestions
fun filterQuestions(
    prdBase64: String,
    questions: List<Question>
): List<Question> {
    return questions
}

private fun buildGenerateSummaryPrompt(
    clauses: List<Clause>,
    index: VectorDbIndex,
    indexReplies: List<Reply>
): String {
    val promptBuilder = StringBuilder()

    promptBuilder.appendLine("This is a product requirements description file. Evaluate if it meets legal requirements specified by the list of relevant clauses below.")

    if (clauses.isNotEmpty()) {
        promptBuilder.appendLine()
        promptBuilder.appendLine()
        promptBuilder.appendLine("--- Start of relevant clauses ---")
        for (clause in clauses) {
            promptBuilder.appendLine("From ${index.description}")
            promptBuilder.appendLine("Chapter ${clause.chapterNumber}. ${clause.chapterTitle}")
            promptBuilder.appendLine("Section ${clause.sectionNumber}. ${clause.sectionTitle}")
            promptBuilder.appendLine("Article ${clause.articleNumber}. ${clause.articleTitle}")
            promptBuilder.appendLine(clause.description)
            promptBuilder.appendLine()
        }
        promptBuilder.appendLine("--- End of relevant clauses ---")
    }

    if (indexReplies.isNotEmpty()) {
        promptBuilder.appendLine()
        promptBuilder.appendLine()
        promptBuilder.appendLine("--- Start of Q&A about the product ---")
        for (reply in indexReplies) {
            promptBuilder.appendLine(reply.question.question)
            promptBuilder.appendLine(reply.reply)
            promptBuilder.appendLine()
        }
        promptBuilder.appendLine("--- End of Q&A about the product ---")
    }

    val prompt = promptBuilder.toString()
    return prompt
}

private fun buildJoinSummariesPrompt(summaryMap: MutableMap<VectorDbIndex, String>): String {
    val promptBuilder = StringBuilder()

    promptBuilder.appendLine("Unify the following reports into one.")

    if (summaryMap.isNotEmpty()) {
        promptBuilder.appendLine()
        promptBuilder.appendLine()
        for (e in summaryMap.entries) {
            promptBuilder.appendLine("--- Start of legal compliance report for ${e.key.description} ---")
            promptBuilder.appendLine(e.value)
            promptBuilder.appendLine("--- End of legal compliance report for ${e.key.description} ---")
            promptBuilder.appendLine()
        }
    }

    val prompt = promptBuilder.toString()
    return prompt
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

fun promptWithPdfBase64(base64Content: String?, prompt: String, filename: String?): String {
    val parts = mutableListOf<ChatCompletionContentPart>()

    if (base64Content != null) {
        val fileObject: FileObject =
            FileObject.builder().fileData("data:application/pdf;base64,$base64Content").filename(filename!!).build()
        val file: ChatCompletionContentPart.File = ChatCompletionContentPart.File.builder().file(fileObject).build()
        val filePart = ChatCompletionContentPart.ofFile(file)
        parts.add(filePart)
    }

    val text = ChatCompletionContentPartText.builder().text(prompt).build()
    val textPart = ChatCompletionContentPart.ofText(text)
    parts.add(textPart)

    // Create user message with text prompt and PDF attachment
    val userMessage = ChatCompletionMessageParam.ofUser(
        ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(parts).build()
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