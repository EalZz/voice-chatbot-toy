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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voice_chatbot_ct.databinding.ActivityMainBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ChatAdapter
    private val chatMessages = mutableListOf<ChatMessage>()

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private val streamManager = ChatStreamManager(this)

    private var loadingJob: Job? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLat: Double? = null
    private var currentLon: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 1. 바인딩 초기화
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        tts = TextToSpeech(this, this)

        // 2. 리사이클러뷰 설정
        adapter = ChatAdapter(chatMessages)
        binding.recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.recyclerView.adapter = adapter

        setupSpeechRecognizer()

        // 3. 전송 버튼
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString()
            if (text.isNotBlank()) {
                sendMessage(text)
                binding.etMessage.text.clear()
            }
        }

        // 4. 음성 버튼
        binding.btnVoice.setOnClickListener {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
            }
            speechRecognizer.startListening(intent)
        }

        requestLocationPermission()
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { binding.btnVoice.text = "듣는 중..." }
            override fun onResults(results: Bundle?) {
                binding.btnVoice.text = "음성"
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) sendMessage(matches[0])
            }
            override fun onError(error: Int) { binding.btnVoice.text = "음성" }
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
                    // 1. 첫 토큰이 오면 즉시 로딩 애니메이션 중지
                    if (loadingJob != null) {
                        stopLoadingAnimation()
                    }

                    val lastIndex = chatMessages.size - 1
                    if (lastIndex < 0) return@collect

                    if (response.token.isNotEmpty()) {
                        val currentMessage = chatMessages[lastIndex]

                        // 2. 현재 내용이 "응답 중"으로 시작하면 새 토큰으로 완전히 교체, 아니면 뒤에 추가
                        val newContent = if (currentMessage.content.contains("응답 중")) {
                            response.token
                        } else {
                            currentMessage.content + response.token
                        }

                        chatMessages[lastIndex] = ChatMessage(newContent, false)
                        fullResponse += response.token

                        // UI 업데이트는 메인 스레드에서 확실하게 보장
                        withContext(Dispatchers.Main) {
                            adapter.notifyItemChanged(lastIndex)
                            binding.recyclerView.scrollToPosition(lastIndex)
                        }
                    }

                    if (response.isDone) {
                        Log.d("TTS_DEBUG", "완료 신호 수신됨! 지금까지 쌓인 답변: $fullResponse")
                        if (fullResponse.isNotBlank()) {
                            Log.d("TTS_DEBUG", "speak 함수 호출 직전")
                            speak(fullResponse)
                        } else {
                            Log.e("TTS_DEBUG", "답변 내용이 비어있어서 소리를 낼 수 없음")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    stopLoadingAnimation()
                    val lastIndex = chatMessages.size - 1
                    if (lastIndex >= 0) {
                        chatMessages[lastIndex] = ChatMessage("에러가 발생했습니다: ${e.message}", false)
                        adapter.notifyItemChanged(lastIndex)
                    }
                }
                Log.e("ERROR", "통신 실패: ${e.message}")
            }
        }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        chatMessages.add(ChatMessage(text, isUser))
        adapter.notifyItemInserted(chatMessages.size - 1)
        binding.recyclerView.scrollToPosition(chatMessages.size - 1)
    }

    private fun startLoadingAnimation() {
        loadingJob = lifecycleScope.launch {
            var dotCount = 1
            while (isActive) {
                val dots = ".".repeat(dotCount)
                val lastIndex = chatMessages.size - 1
                // 현재 메시지가 사용자가 아니고, 내용에 "응답 중"이 포함될 때만 업데이트
                if (lastIndex >= 0 && !chatMessages[lastIndex].isUser &&
                    chatMessages[lastIndex].content.contains("응답 중")) {

                    chatMessages[lastIndex] = ChatMessage("응답 중$dots", false)
                    adapter.notifyItemChanged(lastIndex)
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
                // 권한 허용됨 -> 위치 가져오기
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
                    Log.d("GPS", "현재 위치: $currentLat, $currentLon")
                }
            }
        } catch (e: SecurityException) {
            Log.e("GPS", "위치 권한이 없습니다.")
        }
    }
}
