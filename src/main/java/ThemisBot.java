import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;

public class ThemisBot {
    public static void main(String[] args) throws Exception {
        String prdBase64 = convertFileToBase64("assets/SMS messaging PRD at Fini.pdf");
        String output = promptWithPdfBase64(prdBase64, "give a list of product features. plain text. one level list.\neach item must have a name and a detailed description.", "SMS messaging PRD at Fini.pdf");
        System.out.println(output);
    }

    /**
     * Converts a file to Base64 string
     * @param filePath Path to the file to convert
     * @return Base64 encoded string representation of the file
     * @throws IOException if file cannot be read
     */
    public static String convertFileToBase64(String filePath) throws IOException {
        File file = new File(filePath);

        // Check if file exists
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        // Read file bytes
        byte[] fileBytes = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            //noinspection ResultOfMethodCallIgnored
            fis.read(fileBytes);
        }

        // Convert to Base64
        return Base64.getEncoder().encodeToString(fileBytes);
    }

    public static String promptWithPdfBase64(String base64Content, String prompt, String filename) {
        // Create PDF attachment with data URI
        ChatCompletionContentPart.File.FileObject fileObject = ChatCompletionContentPart.File.FileObject.builder().fileData("data:application/pdf;base64," + base64Content).filename(filename).build();
        ChatCompletionContentPart.File file = ChatCompletionContentPart.File.builder().file(fileObject).build();
        ChatCompletionContentPart filePart = ChatCompletionContentPart.ofFile(file);

        ChatCompletionContentPartText text = ChatCompletionContentPartText.builder().text(prompt).build();
        ChatCompletionContentPart textPart = ChatCompletionContentPart.ofText(text);

        // Create user message with text prompt and PDF attachment
        ChatCompletionMessageParam userMessage = ChatCompletionMessageParam.ofUser(ChatCompletionUserMessageParam.builder().contentOfArrayOfContentParts(List.of(filePart, textPart)).build());

        // Create chat completion request
        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O)
                .messages(List.of(userMessage))
                .build();

        OpenAIClient client = OpenAIOkHttpClient.fromEnv();

        ChatCompletion completion = client.chat().completions().create(params);
        return completion.choices().getFirst().message().content().orElse("");
    }
}
