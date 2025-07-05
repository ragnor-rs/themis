import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.*
import com.openai.models.chat.completions.ChatCompletionContentPart.File.FileObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Base64

object ThemisBot {
    @Throws(Exception::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val prdBase64 = convertFileToBase64("assets/SMS messaging PRD at Fini.pdf")
        val output = promptWithPdfBase64(
            prdBase64,
            "give a list of product features. plain text. one level list.\neach item must have a name and a detailed description.",
            "SMS messaging PRD at Fini.pdf"
        )
        println(output)
    }

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
        return Base64.getEncoder().encodeToString(fileBytes)
    }

    fun promptWithPdfBase64(base64Content: String?, prompt: String, filename: String): String {
        // Create PDF attachment with data URI
        val fileObject: FileObject = FileObject.builder().fileData("data:application/pdf;base64,$base64Content").filename(filename).build()
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

        val client = OpenAIOkHttpClient.fromEnv()

        val completion = client.chat().completions().create(params)
        return completion.choices()[0].message().content().orElse("")
    }
}
