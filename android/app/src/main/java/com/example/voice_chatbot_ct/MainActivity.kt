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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime

data class ChatMessage(val content: String, val isUser: Boolean, val id: String = java.util.UUID.randomUUID().toString())

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private val chatMessages = mutableStateListOf<ChatMessage>()
    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val streamManager = ChatStreamManager(this)

    private var loadingJob: Job? = null
    private var isListening by mutableStateOf(false)
    private var isAutoVoiceEnabled by mutableStateOf(true)

    // private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        // fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        tts = TextToSpeech(this, this)
        setupSpeechRecognizer()
        // requestLocationPermission()

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
                        autoVoiceEnabled = isAutoVoiceEnabled,
                        onAutoVoiceToggle = { isAutoVoiceEnabled = !isAutoVoiceEnabled },
                        onPlayVoice = { text -> speak(text) },
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
        addMessage("Thinking", isUser = false)
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
                        val newContent = if (currentMessage.content.contains("Thinking")) {
                            response.token
                        } else {
                            currentMessage.content + response.token
                        }
                        chatMessages[lastIndex] = currentMessage.copy(content = newContent)
                        fullResponse += response.token
                    }

                    if (response.isDone) {
                        if (fullResponse.isNotBlank() && isAutoVoiceEnabled) {
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
                if (lastIndex >= 0 && !chatMessages[lastIndex].isUser && chatMessages[lastIndex].content.contains("Thinking")) {
                    chatMessages[lastIndex] = chatMessages[lastIndex].copy(content = "Thinking$dots")
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
        /*
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
        */
    }

    private fun getLastLocation() {
        /*
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
        */
    }
}

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isListening: Boolean,
    autoVoiceEnabled: Boolean,
    onAutoVoiceToggle: () -> Unit,
    onPlayVoice: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onVoiceClick: () -> Unit
) {
    var textState by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    
    val density = LocalDensity.current
    // val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0 // Removed as per instruction

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    // LaunchedEffect(isKeyboardVisible) { // Removed as per instruction
    //     if (isKeyboardVisible && messages.isNotEmpty()) {
    //         listState.animateScrollToItem(messages.size - 1)
    //     }
    // }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF121212),
        bottomBar = {
            // Precise keyboard handling
            Surface(
                color = Color(0xFF121212), // Match background to prevent gaps
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
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
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Gavel,
                        contentDescription = "Logo",
                        modifier = Modifier.size(64.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(16.dp))
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 100.dp,
                        bottom = innerPadding.calculateBottomPadding() + 8.dp,
                        start = 16.dp,
                        end = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatBubble(message, onPlayVoice)
                    }
                }
            }

            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color(0xFF121212), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "New Chat",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )

                IconButton(onClick = onAutoVoiceToggle) {
                    Icon(
                        imageVector = if (autoVoiceEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Toggle Auto Voice",
                        tint = if (autoVoiceEnabled) Color.White else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage, onPlayVoice: (String) -> Unit) {
    val isUser = message.isUser
    val isThinking = !isUser && message.content.startsWith("Thinking")
    
    val bubbleColor = if (isUser) Color(0xFF2F2F2F) else Color.Transparent
    val textColor = if (isThinking) Color.Gray else Color.White
    
    val shape = if (isUser) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(0.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // AI Avatar: Thinking일 때만 노출
        if (!isUser && isThinking) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Color(0xFF2A2A2A), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Gavel,
                    contentDescription = "AI",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = if (isUser) 280.dp else 1000.dp)
                    .clip(shape)
                    .background(bubbleColor)
                    .padding(
                        horizontal = if (isUser) 16.dp else 0.dp, // AI 답변은 여백 제거
                        vertical = if (isUser) 12.dp else 4.dp
                    )
            ) {
                Text(
                    text = message.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            if (!isUser && !isThinking) {
                IconButton(
                    onClick = { onPlayVoice(message.content) },
                    modifier = Modifier
                        .size(32.dp)
                        .padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Voice",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
