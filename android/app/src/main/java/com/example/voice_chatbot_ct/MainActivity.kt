package com.example.voice_chatbot_ct

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import java.util.Locale
import androidx.core.view.WindowCompat
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

data class ChatMessage(val content: String, val isUser: Boolean, val id: String = java.util.UUID.randomUUID().toString())

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val chatMessages = mutableStateListOf<ChatMessage>()
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val streamManager = ChatStreamManager(this)

    private var loadingJob: Job? = null
    private var isListening by mutableStateOf(false)

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
        requestLocationPermission()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFF3F51B5),
                    secondary = Color(0xFFE91E63)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(
                        messages = chatMessages,
                        isListening = isListening,
                        onSendMessage = { text ->
                            if (text.isNotBlank()) {
                                sendMessage(text)
                            }
                        },
                        onVoiceClick = {
                            if (isListening) {
                                speechRecognizer.stopListening()
                            } else {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
                                }
                                speechRecognizer.startListening(intent)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) sendMessage(matches[0])
            }
            override fun onError(error: Int) { isListening = false }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun sendMessage(text: String) {
        addMessage(text, isUser = true)
        addMessage("응답 중", isUser = false)
        startLoadingAnimation()

        var fullResponse = ""

        lifecycleScope.launch {
            try {
                streamManager.fetchChatStream(text, currentLat, currentLon).collect { response ->
                    if (loadingJob != null) {
                        stopLoadingAnimation()
                    }

                    val lastIndex = chatMessages.size - 1
                    if (lastIndex < 0) return@collect

                    if (response.token.isNotEmpty()) {
                        val currentMessage = chatMessages[lastIndex]
                        val newContent = if (currentMessage.content.contains("응답 중")) {
                            response.token
                        } else {
                            currentMessage.content + response.token
                        }
                        chatMessages[lastIndex] = currentMessage.copy(content = newContent)
                        fullResponse += response.token
                    }

                    if (response.isDone) {
                        if (fullResponse.isNotBlank()) {
                            speak(fullResponse)
                        }
                    }
                }
            } catch (e: Exception) {
                stopLoadingAnimation()
                val lastIndex = chatMessages.size - 1
                if (lastIndex >= 0) {
                    chatMessages[lastIndex] = chatMessages[lastIndex].copy(content = "에러가 발생했습니다: ${e.message}")
                }
            }
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        chatMessages.add(ChatMessage(content = text, isUser = isUser))
    }

    private fun startLoadingAnimation() {
        loadingJob = lifecycleScope.launch {
            var dotCount = 1
            while (isActive) {
                val dots = ".".repeat(dotCount)
                val lastIndex = chatMessages.size - 1
                if (lastIndex >= 0 && !chatMessages[lastIndex].isUser && chatMessages[lastIndex].content.contains("응답 중")) {
                    chatMessages[lastIndex] = chatMessages[lastIndex].copy(content = "응답 중$dots")
                }
                dotCount = if (dotCount >= 3) 1 else dotCount + 1
                delay(500)
            }
        }
    }

    private fun stopLoadingAnimation() {
        loadingJob?.cancel()
        loadingJob = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts.language = Locale.KOREAN
    }

    private fun speak(text: String) {
        val cleanText = text.replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
        tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        super.onDestroy()
    }

    private fun requestLocationPermission() {
        val locationPermissionRequest = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
                getLastLocation()
            }
        }
        locationPermissionRequest.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun getLastLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    currentLat = location.latitude
                    currentLon = location.longitude
                }
            }
        } catch (e: SecurityException) {
            Log.e("GPS", "위치 권한이 없습니다.")
        }
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isListening: Boolean,
    onSendMessage: (String) -> Unit,
    onVoiceClick: () -> Unit
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .systemBarsPadding()
            .imePadding()
    ) {
        if (messages.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "무엇을 도와드릴까요?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "본 챗봇의 내용은 참고용이며, 정확한 판단은 법률 전문가와의 상담을 권장합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message)
                }
            }
        }

        Surface(
            color = Color(0xFF121212),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color(0xFF2A2A2A), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onVoiceClick,
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (isListening) Color.Red else Color.Transparent, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic, 
                        contentDescription = "Voice", 
                        tint = if (isListening) Color.White else Color.Gray
                    )
                }
                
                BasicTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    decorationBox = { innerTextField ->
                        if (textState.text.isEmpty()) {
                            Text("메시지를 입력하세요...", color = Color.Gray, fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                )
                
                val hasText = textState.text.trim().isNotEmpty()
                IconButton(
                    onClick = {
                        val trimmed = textState.text.trim()
                        if (trimmed.isNotEmpty()) {
                            onSendMessage(trimmed)
                            textState = TextFieldValue("")
                        }
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(if (hasText) Color.White else Color.DarkGray, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send, 
                        contentDescription = "Send", 
                        tint = if (hasText) Color.Black else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser
    val bubbleColor = if (isUser) Color(0xFF3F51B5) else Color(0xFF2A2A2A)
    val textColor = Color.White
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
                .background(bubbleColor)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
