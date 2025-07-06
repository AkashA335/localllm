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
    private var modelLoaded = false

    private val accessSummary = """
        Access summary for past 10 days:

        Date: 2025-08-01 to 2025-08-10  
        Pattern observed:

        - Morning entry at Main Gate around 09:00 AM daily.
        - 9th Floor Office entered at ~09:03 AM, exited at ~01:00 PM.
        - Mid-day exit from Main Gate around ~01:02 PM.
        - 10th Floor Office entered at ~01:08 PM, exited at ~02:00 PM.
        - Afternoon re-entry via Main Gate ~02:06 PM.
        - 9th Floor Office re-entered ~02:08 PM, exited at ~05:02 PM.
        - Evening exit from Main Gate ~05:05 PM.
        - No deviations observed in sequence or timing.
        - Access follows a consistent routine: 9th → exit → 10th → re-entry → 9th → exit.

        Use this as reference to decide future access attempts.
    """.trimIndent()

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
                        binding.textView.text = "📊 Model ready. No need to upload logs.\n🎯 Ready for analysis.\n"
                        binding.buttonAnalyze.isEnabled = true
                        binding.editTextInput.isEnabled = true
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ Model load failed: ${e.message}", Toast.LENGTH_LONG).show()
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

        binding.buttonAnalyze.isEnabled = false
        binding.editTextInput.isEnabled = false

        binding.buttonLoadModel.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            modelPickerLauncher.launch(intent)
        }

        binding.buttonAnalyze.setOnClickListener {
            val inputText = binding.editTextInput.text.toString().trim()

            when {
                !modelLoaded -> Toast.makeText(this, "Please load the model first.", Toast.LENGTH_SHORT).show()
                inputText.isEmpty() -> Toast.makeText(this, "Enter timestamp and location", Toast.LENGTH_SHORT).show()
                else -> {
                    val prompt = """
                        You are an AI assistant for secure facility access.

                        $accessSummary

                        New access attempt:
                        $inputText

                        Based on previous behavior, Reply strictly with only one of the following words: Access Granted or Access Denied. Do not add any other words.

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
                                    android.util.Log.d("AI_RESPONSE", "Final Response: $responseBuffer")
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
}
