#  Voice Chatbot Toy Project

안드로이드 단말기의 **ANDROID_ID**를 식별자로 사용하여 사용자별 대화 흐름을 기억하고, 음성으로 소통하는 개인용 AI 비서 프로젝트입니다.

##  주요 특징 (Key Features)

- **음성 인터페이스:** gTTS 및 OpenAI Whisper(예정)를 활용한 자연스러운 음성 대화.
- **사용자 식별:** 별도의 로그인 없이 `ANDROID_ID`를 `uid`로 사용하여 사용자별 데이터 매칭.
- **장기 기억 (Long-term Memory):** PostgreSQL DB와 연동하여 과거 대화 기록 중 최신 4개를 맥락으로 활용하는 로직 구현.
- **컨테이너 기반 서버:** Docker Compose를 사용하여 FastAPI, Ollama, PostgreSQL을 원클릭으로 구동.
- **실시간 정보 반영:** 현재 시간 및 날씨 정보를 프롬프트에 포함하여 지능적인 답변 제공.

##  기술 스택 (Tech Stack)

### Backend
- **Framework:** FastAPI (Python)
- **AI Engine:** Ollama (LLM 실행)
- **Database:** PostgreSQL (Docker)
- **Communication:** OkHttp Streaming (Android와 통신)

### Android
- **Language:** Kotlin
- **Network:** OkHttp
- **Device ID:** ANDROID_ID 기반 사용자 식별 (회원가입 추가 예정)

### Infrastructure
- **Environment:** WSL2 (Ubuntu)
- **Container:** Docker, Docker Compose
- **Tunneling:** ngrok (우회 헤더 적용)

## 프로젝트 구조 (Project Structure)

```text
.
├── android/          # Android Kotlin 프로젝트 소스
├── backend/          # FastAPI 서버 및 Dockerfile
├── docker-compose.yml # 인프라 통합 설정 파일
└── .gitignore        # 모델 및 데이터 보안 설정
