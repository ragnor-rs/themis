import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.document
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
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

fun main() {
    val bot = bot {
        token = System.getenv("TELEGRAM_BOT_TOKEN")
        dispatch {
            text {
                bot.sendMessage(ChatId.fromId(message.chat.id), text = text)
            }
            document {
                val prdFile = bot.downloadFileBytes(media.fileId)
                val prdBase64 = convertByteArrayToBase64(prdFile!!)
                val output = promptWithPdfBase64(
                    prdBase64,
                    "Give a list of product features. Plain text. One level list.\nEach item must have a name and a detailed description.",
                    media.fileName!!
                )
                val items = toNonEmptyItems(output)
                val results = queryVectorDb(items[0])
                val description = results[0].metadata.getFieldsOrThrow("description").stringValue
                bot.sendMessage(ChatId.fromId(message.chat.id), text = description)
            }
        }
    }
    bot.startPolling()
}


//fun main() {
//    val prdBase64 = convertFileToBase64("assets/SMS messaging PRD at Fini.pdf")
//    val output = promptWithPdfBase64(
//        prdBase64,
//        "give a list of product features. plain text. one level list.\neach item must have a name and a detailed description.",
//        "SMS messaging PRD at Fini.pdf"
//    )
//    val items = toNonEmptyItems(output)
//    val results = queryVectorDb(items[0])
//    println(results[0].metadata.getFieldsOrThrow("description").stringValue)
//}

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

private fun convertByteArrayToBase64(fileBytes: ByteArray): String? = Base64.getEncoder().encodeToString(fileBytes)

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

fun queryVectorDb(text: String): List<ScoredVectorWithUnsignedIndices> {
    val embeddings = generateEmbeddings(text)
    val pinecone = Pinecone.Builder(System.getenv("PINECONE_API_KEY")).build()
    val index = pinecone.getIndexConnection(System.getenv("PINECONE_INDEX_NAME"))
    val response = index.queryByVector(5, embeddings, true, true)
    return response.matchesList
}