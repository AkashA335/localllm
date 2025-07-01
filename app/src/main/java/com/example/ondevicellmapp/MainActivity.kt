package com.example.ondevicellmapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.ondevicellmapp.ui.theme.OnDeviceLLMAppTheme
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private var llmInference: LlmInference? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Load model from /data/local/tmp/llm/model_version.task
        val options = LlmInferenceOptions.builder()
            .setModelPath("/data/local/tmp/llm/model_version.task")
            .setMaxTokens(200)
            .setMaxTopK(40)
            .build()

        llmInference = LlmInference.createFromOptions(this, options)

        setContent {
            OnDeviceLLMAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LLMChatUI { prompt, onResponse ->
                        // Launch async inference call
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    llmInference?.generateResponse(prompt)
                                        ?: "LLM not initialized"
                                }
                            }.getOrElse { e ->
                                Log.e("LLM", "Error: ${e.message}")
                                "Error generating response"
                            }
                            onResponse(result)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()  // cancel any running coroutines when activity destroyed
        llmInference?.close() // if available to close
    }
}

@Composable
fun LLMChatUI(onGenerate: (String, (String) -> Unit) -> Unit) {
    var prompt by remember { mutableStateOf(TextFieldValue("")) }
    var response by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Text(text = "Enter your prompt:", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            TextField(
                value = prompt,
                onValueChange = { prompt = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ask me anything...") }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    isLoading = true
                    onGenerate(prompt.text) { result ->
                        response = result
                        isLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && prompt.text.isNotBlank()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Generate")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Response:",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = response,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
