import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.document
import com.github.kotlintelegrambot.dispatcher.telegramError
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.logging.LogLevel
import com.github.kotlintelegrambot.types.TelegramBotResult
import com.openai.models.ChatModel
import com.openai.models.chat.completions.*
import com.openai.models.chat.completions.ChatCompletionContentPart.File.FileObject
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.util.*
import kotlin.system.exitProcess

enum class UserStage {
    Initial,
    Questions,
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

private val requiredEnvVars = listOf(
    "TELEGRAM_BOT_TOKEN",
    "OPENAI_API_KEY",
    "PINECONE_API_KEY",
) + VectorDbIndex.entries.map { it.indexNameEnvVar }

/**
 * Upper bound on the questions raised for one regulation.
 *
 * The questions are asked one at a time in a chat, so the ceiling is what a person will actually sit
 * through, not what the clauses could justify asking.
 */
private const val MAX_QUESTIONS_PER_REGULATION = 10

private const val WELCOME = "Send me a product requirements document as a PDF and I will check it against " +
    "the EU Digital Services Act and the GDPR.\n\n" +
    "I will ask you a few questions about what the document leaves out, then send back an HTML report.\n\n" +
    "This is an experiment, not legal advice."

fun main() {
    checkRequiredEnvVars(requiredEnvVars)

    val userStateMap = mutableMapOf<Long, UserState>()

    fun getUserState(message: Message): UserState = userStateMap.getOrPut(
        message.chat.id,
    ) { UserState.createInitial() }

    fun summarize(message: Message, bot: Bot, state: UserState) {
        // Writing the report is several more minutes of API calls, so say so rather than going quiet.
        bot.sendMessage(
            ChatId.fromId(message.chat.id),
            text = "Thanks — writing the compliance report now. This takes a few minutes.",
        )

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

        val flavour = CommonMarkFlavourDescriptor()
        val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(finalSummary)
        val html = HtmlGenerator(finalSummary, parsedTree, flavour).generateHtml()
        val file = TelegramFile.ByByteArray(html.toByteArray(), "report.html")
        bot.sendDocument(ChatId.fromId(message.chat.id), file)
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

    /**
     * Reports a failure to the user and resets the conversation.
     *
     * Without this the library swallows the exception, the chat stays stuck mid-questionnaire and
     * nothing is written anywhere — the bot simply looks dead.
     */
    fun handleFailure(message: Message, bot: Bot, step: String, e: Throwable) {
        log("Failed during $step for chat ${message.chat.id}: $e")
        e.printStackTrace()
        userStateMap.put(message.chat.id, UserState.createInitial())
        runCatching {
            bot.sendMessage(
                ChatId.fromId(message.chat.id),
                text = "Something went wrong while $step. The conversation has been reset — " +
                    "send the PDF again to retry.",
            )
        }
    }

    val bot = bot {
        token = requireEnv("TELEGRAM_BOT_TOKEN")
        // Without this the dispatcher discards exceptions thrown in the handlers below without a trace.
        logLevel = LogLevel.Error
        dispatch {
            telegramError {
                // Polling failures land here: an invalid or revoked token would otherwise leave the bot
                // sitting silently in its loop, looking like it had started correctly.
                log("Telegram error: ${error.getErrorMessage()}")
            }
            text {
                if (text.trim() == "/reset") {
                    userStateMap.put(message.chat.id, UserState.createInitial())
                    bot.sendMessage(ChatId.fromId(message.chat.id), text = "Starting over. Send me a PDF.")
                    return@text
                }

                val state = getUserState(message)

                if (state.stage == UserStage.Questions) {
                    try {
                        val nextState = state.addReply(text)
                        userStateMap.put(message.chat.id, nextState)

                        askNextQuestion(message, bot)
                    } catch (e: Throwable) {
                        handleFailure(message, bot, "building your report", e)
                    }
                } else if (text.isNotBlank()) {
                    // The conversation only starts with a document, so without this a first-time user
                    // sending /start gets no reply at all.
                    bot.sendMessage(ChatId.fromId(message.chat.id), text = WELCOME)
                }
            }
            document {
                val state = getUserState(message)

                if (state.stage != UserStage.Initial) {
                    bot.sendMessage(
                        ChatId.fromId(message.chat.id),
                        text = "I am still working through the questions for your previous document. " +
                            "Answer those first, or send /reset to start over.",
                    )
                    return@document
                }

                if (media.mimeType != "application/pdf") {
                    bot.sendMessage(
                        ChatId.fromId(message.chat.id),
                        text = "That is a ${media.mimeType ?: "file of unknown type"}. Please send a PDF.",
                    )
                    return@document
                }

                // Telegram does not guarantee a file name, and the OpenAI file part requires one.
                val prdName = media.fileName ?: "document.pdf"

                try {
                    bot.sendMessage(
                        ChatId.fromId(message.chat.id),
                        text = "Reading your document. Finding the relevant clauses takes a few minutes — " +
                            "I will come back with questions.",
                    )

                    log("1) PRD doc - begin")
                    val prdFile = bot.downloadFileBytes(media.fileId)
                        ?: error("Telegram did not return the contents of $prdName")
                    val prdBase64 = convertByteArrayToBase64(prdFile)
                    log("1) PRD doc - end")

                    log("2) product features - begin")
                    val output = promptWithPdfBase64(
                        prdBase64,
                        "Give a list of product features. Plain text. One level list.\nEach item must have a name and a detailed description.",
                        prdName
                    )
                    val items = toNonEmptyItems(output)
                    log("2) product features - end: extracted ${items.size} features")

                    val questions = mutableListOf<Question>()
                    val clauseMap = mutableMapOf<VectorDbIndex, List<Clause>>()

                    VectorDbIndex.entries.forEachIndexed { i, index ->
                        val regulationClauses = mutableListOf<Clause>()
                        items.forEachIndexed { j, productFeature ->
                            log("3.${i+1}.${j+1}.1) embeddings - begin")
                            val embeddings = generateEmbeddings(productFeature)
                            log("3.${i+1}.${j+1}.1) embeddings - end")

                            log("3.${i+1}.${j+1}.2) relevant clauses - begin")
                            val results = queryVectorDb(embeddings, index.indexName)
                            val featureClauses = results.map { it.toClause() }
                            regulationClauses.addAll(featureClauses)
                            log("3.${i+1}.${j+1}.2) relevant clauses - end: fetched ${featureClauses.size} clauses")
                        }

                        // Features overlap heavily, so the same clause comes back for several of them.
                        val distinctClauses = regulationClauses.distinct()
                        log(
                            "3.${i+1}) ${index.description}: ${distinctClauses.size} distinct clauses " +
                                "from ${regulationClauses.size} matches"
                        )
                        clauseMap.put(index, distinctClauses)

                        // Once per regulation over the whole deduplicated clause set, rather than once per
                        // feature. Per feature, each call re-asked what the others had already covered —
                        // thirteen features produced hundreds of near-identical questions, far more than
                        // anyone can answer in a chat, and the filter below could not thin them out.
                        log("3.${i+1}.q) question gen - begin")
                        val indexQuestions = generateQuestions(prdBase64, distinctClauses, prdName, index)
                            .map { Question(it, index) }
                        questions.addAll(indexQuestions)
                        log("3.${i+1}.q) question gen - end: generated ${indexQuestions.size} questions")
                    }

                    // Cheap pass before spending a call on the filter: the two regulations overlap on
                    // consent and transparency, so they independently ask for the same thing verbatim.
                    val uniqueQuestions = questions.distinctBy { it.question.trim().lowercase() }
                    if (uniqueQuestions.size < questions.size) {
                        log("4) dedupe questions: dropped ${questions.size - uniqueQuestions.size} repeats")
                    }

                    log("4) filter questions - begin")
                    val filteredQuestions = filterQuestions(prdBase64, uniqueQuestions, prdName)
                    log("4) filter questions - end: ${filteredQuestions.size} of ${uniqueQuestions.size} questions kept")

                    userStateMap.put(message.chat.id, state.switchToQuestions(filteredQuestions, prdBase64, clauseMap, prdName))

                    if (filteredQuestions.isEmpty()) {
                        bot.sendMessage(
                            ChatId.fromId(message.chat.id),
                            text = "The document already answers everything I would have asked. " +
                                "Writing the report now.",
                        )
                    } else {
                        bot.sendMessage(
                            ChatId.fromId(message.chat.id),
                            text = "I have ${filteredQuestions.size} question(s) for you.",
                        )
                    }

                    askNextQuestion(message, bot)
                } catch (e: Throwable) {
                    handleFailure(message, bot, "reading your document", e)
                }
            }
        }
    }

    // Confirm the token before polling. getUpdates failures are reported asynchronously, so a bad token
    // otherwise shows up as nothing at all: the bot logs that it started and then waits forever.
    bot.getMe().fold(
        ifSuccess = { log("Connected to Telegram as @${it.username}") },
        ifError = {
            System.err.println("Telegram rejected the bot token: ${it.describe()}")
            System.err.println("Check TELEGRAM_BOT_TOKEN against the token @BotFather gave you.")
            exitProcess(1)
        },
    )

    Runtime.getRuntime().addShutdownHook(Thread { closeIndexConnections() })

    log("Listening for documents")
    bot.startPolling()
}

/** Flattens the error variants into one line, so a failed call can be logged without a `when` at each site. */
private fun TelegramBotResult.Error.describe(): String = when (this) {
    is TelegramBotResult.Error.HttpError -> "HTTP $httpCode $description"
    is TelegramBotResult.Error.TelegramApi -> "Telegram API error $errorCode: $description"
    is TelegramBotResult.Error.InvalidResponse -> "invalid response: $httpCode $httpStatusMessage"
    is TelegramBotResult.Error.Unknown -> "unexpected failure: ${exception.message}"
}

fun generateQuestions(
    prdBase64: String,
    clauses: List<Clause>,
    filename: String,
    index: VectorDbIndex,
): List<String> {
    val prompt = buildGenerateQuestionsPrompt(clauses, index)
    val output = promptWithPdfBase64(
        prdBase64,
        prompt,
        filename,
    )
    val items = toNonEmptyItems(output)
    return items
}

private fun buildGenerateQuestionsPrompt(
    clauses: List<Clause>,
    index: VectorDbIndex,
): String {
    val promptBuilder = StringBuilder()

    promptBuilder.appendLine("Generate questions to gather data missing from the provided product specification. Questions will be used to assess compliance with the clauses below. Always mention the name of law along with an article number. Plain text. One level list. Don't use bullets. Don't start with question index. One question per line.")
    // Unbounded, this produced a question per clause, which is more than anyone will answer in a chat.
    promptBuilder.appendLine("Ask at most $MAX_QUESTIONS_PER_REGULATION questions: the ones whose answers matter most for assessing compliance. Merge questions that would be answered by the same piece of information, and never ask the same thing twice.")

    promptBuilder.appendClauses(clauses, index)

    val prompt = promptBuilder.toString()
    return prompt
}

/**
 * Renders the retrieved clauses as the citation context for a prompt.
 *
 * Headings with no title are left out: recitals carry no chapter or section, and a good number of
 * articles sit outside any section, so printing the lines unconditionally produced "Chapter 0." and
 * "Section 1." with nothing after them.
 */
private fun StringBuilder.appendClauses(clauses: List<Clause>, index: VectorDbIndex) {
    if (clauses.isEmpty()) {
        return
    }

    appendLine()
    appendLine()
    appendLine("--- Start of relevant clauses ---")
    for (clause in clauses) {
        appendLine("From ${index.description}")
        if (clause.chapterTitle.isNotBlank()) {
            appendLine("Chapter ${clause.chapterNumber}. ${clause.chapterTitle}")
        }
        if (clause.sectionTitle.isNotBlank()) {
            appendLine("Section ${clause.sectionNumber}. ${clause.sectionTitle}")
        }
        appendLine("Article ${clause.articleNumber}. ${clause.articleTitle}")
        appendLine("Clause ${clause.clauseNumber}")
        appendLine(clause.description)
        appendLine()
    }
    appendLine("--- End of relevant clauses ---")
}

fun filterQuestions(
    prdBase64: String,
    questions: List<Question>,
    filename: String,
): List<Question> {
    val prompt = buildFilterQuestionsPrompt(questions)
    val output = promptWithPdfBase64(
        prdBase64,
        prompt,
        filename,
    )
    val items = toNonEmptyItems(output)
    return questions.filterIndexed { i, q -> items.contains(i.toString()) }
}

private fun buildFilterQuestionsPrompt(
    questions: List<Question>,
): String {
    val promptBuilder = StringBuilder()

    promptBuilder.appendLine("Here's a numbered list of questions to gather data missing from the provided product specification. Remove questions that are not relevant. Drop a question if it's about what's contained in the product specification. Filter out questions which can be answered after studying the product specification. Print just indices of the questions left. Plain text. One level list.\nAn index number per line.")

    if (questions.isNotEmpty()) {
        promptBuilder.appendLine()
        promptBuilder.appendLine()
        questions.forEachIndexed { i, q ->
            promptBuilder.appendLine(i.toString() + ") " + q.question)
            promptBuilder.appendLine()
        }
    }

    val prompt = promptBuilder.toString()
    return prompt
}

private fun buildGenerateSummaryPrompt(
    clauses: List<Clause>,
    index: VectorDbIndex,
    indexReplies: List<Reply>
): String {
    val promptBuilder = StringBuilder()

    promptBuilder.appendLine("This is a product requirements description file. Evaluate if it meets legal requirements specified by the list of relevant clauses below.")

    promptBuilder.appendClauses(clauses, index)

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

    promptBuilder.appendLine("Merge the following reports into one.")

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

    val completion = openAi.chat().completions().create(params)
    return completion.choices()[0].message().content().orElse("")
}

fun toNonEmptyItems(text: String): List<String> {
    return text.split("\n").map { it -> it.trim() }.filter { it -> it.isNotEmpty() }
}

fun queryVectorDb(embeddings: List<Float>, indexName: String): List<ScoredVectorWithUnsignedIndices> {
    val response = indexConnection(indexName).queryByVector(5, embeddings, true, true)
    return response.matchesList
}