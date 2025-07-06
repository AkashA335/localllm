package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val llmw = LLMW()
    private var uploadedLogs: String = ""
    private var modelLoaded = false
    private var logContextLoaded = false

    private val modelPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val modelPath = copyUriToInternalStorage(uri)
                    llmw.load(modelPath)
                    withContext(Dispatchers.Main) {
                        modelLoaded = true
                        Toast.makeText(this@MainActivity, "✅ Model loaded successfully!", Toast.LENGTH_SHORT).show()
                        binding.buttonUploadLog.isEnabled = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ Model load failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val logPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    uploadedLogs = readTextFromUri(uri)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "📄 Log uploaded!", Toast.LENGTH_SHORT).show()
                        binding.textView.text = "📊 Feeding logs to model...\n"
                    }

                    val logsByDay = uploadedLogs.lines().filter { it.isNotBlank() }
                        .groupBy { it.substringBefore(" ") }  // group by date
                        .toSortedMap()
                        .entries.take(10)

                    for ((index, entry) in logsByDay.withIndex()) {
                        val dailyLog = entry.value.joinToString("\n")
                        val prompt = """
                            <|user|>
                            Day ${index + 1} Logs:
                            $dailyLog
                            </s>
                            <|assistant|>
                            Noted.
                            </s>
                        """.trimIndent()

                        llmw.send(prompt, object : LLMW.MessageHandler {
                            override fun h(msg: String) {
                                // optional logging
                            }
                        })

                        withContext(Dispatchers.Main) {
                            binding.textView.append("✅ Day ${index + 1} sent\n")
                        }
                        delay(500)
                    }

                    withContext(Dispatchers.Main) {
                        logContextLoaded = true
                        binding.textView.append("🎯 Ready for analysis\n")
                        binding.buttonAnalyze.isEnabled = true
                        binding.editTextInput.isEnabled = true
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ Failed to read log: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.buttonUploadLog.isEnabled = false
        binding.buttonAnalyze.isEnabled = false
        binding.editTextInput.isEnabled = false

        binding.buttonLoadModel.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            modelPickerLauncher.launch(intent)
        }

        binding.buttonUploadLog.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
            }
            logPickerLauncher.launch(intent)
        }

        binding.buttonAnalyze.setOnClickListener {
            val inputText = binding.editTextInput.text.toString().trim()

            when {
                !modelLoaded -> Toast.makeText(this, "Please load the model first.", Toast.LENGTH_SHORT).show()
                uploadedLogs.isEmpty() -> Toast.makeText(this, "Please upload the log file first.", Toast.LENGTH_SHORT).show()
                !logContextLoaded -> Toast.makeText(this, "Log context not yet processed. Please wait.", Toast.LENGTH_SHORT).show()
                inputText.isEmpty() -> Toast.makeText(this, "Enter timestamp and location", Toast.LENGTH_SHORT).show()
                else -> {
                    val prompt = """
                <|system|>
                You are an anomaly detection assistant for secure access control.
                Analyze access attempts based on prior patterns, especially:
                - Time of day (normal hours: 08:00 AM to 06:00 PM)
                - Entry/exit sequences
                - Location transition frequency
                - Any abnormal behavior

                If the attempt deviates significantly from known patterns, deny access.
                </s>
                <|user|>
                New access attempt:
                $inputText

                Based on previous behavior, reply with only:
                Access Granted
                Access Denied
                </s>
                <|assistant|>
            """.trimIndent()

                    binding.textView.append("\n🔎 Analyzing new event...\n")

                    val responseBuffer = StringBuilder()
                    var isFinished = false

                    llmw.send(prompt, object : LLMW.MessageHandler {
                        override fun h(msg: String) {
                            if (isFinished) return

                            runOnUiThread {
                                responseBuffer.append(msg)
                                binding.textView.append(msg)

                                val lower = responseBuffer.toString().lowercase()
                                if (lower.contains("access granted") || lower.contains("access denied")) {
                                    isFinished = true
                                    android.util.Log.d("AI_RESPONSE", "Final Response: ${responseBuffer.toString()}")
                                }
                            }
                        }
                    })
                }
            }
        }


        binding.editTextInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.buttonAnalyze.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun copyUriToInternalStorage(uri: Uri): String {
        val fileName = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        } ?: "model.gguf"

        val destFile = File(filesDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile.absolutePath
    }

    private fun readTextFromUri(uri: Uri): String {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            return BufferedReader(InputStreamReader(inputStream)).readText()
        } ?: throw IOException("Unable to open file")
    }
}
