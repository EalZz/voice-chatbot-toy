from fastapi import FastAPI, Request, Form
from fastapi.responses import HTMLResponse, StreamingResponse, FileResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
from datetime import datetime
import requests
import os
import whisper
import logging
from urllib.parse import quote
import time
import json
import pytz
from sqlalchemy import create_engine, Column, String, Integer, DateTime, ForeignKey, desc
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker

app = FastAPI()
WEATHER_API_KEY = "ef6d58373d3f034f0122ba1e477074d4"

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://user:password@db:5432/voice_db")
engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

templates = Jinja2Templates(directory="templates")
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("VoiceAI-Server")

# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 메모리 절약을 위해 tiny 모델 
stt_model = whisper.load_model("tiny")
MODEL_NAME = "llama3:8b"
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "localhost")
OLLAMA_URL = f"http://{OLLAMA_HOST}:11434/api/generate"

class TextRequest(BaseModel):
    text: str


# [User 클래스]
# 데이터베이스의 'users' 테이블과 매핑됩니다.
# 사용자의 기기 고유 ID(uid)를 저장하여 개별 사용자를 식별합니다.
class User(Base):
    __tablename__ = "users"
    uid = Column(String, primary_key=True, index=True)

# [ChatHistory 클래스]
# 데이터베이스의 'chat_history' 테이블과 매핑됩니다.
# 어떤 유저(uid)가 어떤 질문(user_text)을 했고 AI가 뭐라고(ai_response) 답했는지 저장합니다.
class ChatHistory(Base):
    __tablename__ = "chat_history"
    id = Column(Integer, primary_key=True, index=True) # 대화별 고유 번호
    uid = Column(String, ForeignKey("users.uid"))     # 사용자 식별용 외래키
    user_text = Column(String)                        # 사용자 질문 내용
    ai_response = Column(String)                      # AI 답변 내용
    timestamp = Column(DateTime, default=lambda: datetime.now(pytz.timezone('Asia/Seoul'))) # 한국 시간 저장

# 서버가 시작될 때 정의된 테이블이 DB에 없으면 자동으로 생성합니다.
Base.metadata.create_all(bind=engine)

# [generate_ai_stream 함수]
# 사용자의 질문을 받아 DB에서 과거 대화 기록을 불러오고, AI 답변을 실시간으로 생성(Streaming)합니다.
async def generate_ai_stream(uid: str, user_text: str, weather_info: str, current_time: str, client_type: str = "app"):
    db = SessionLocal() # DB 연결 세션 시작
    try:
        # 1. 사용자 관리: DB에 해당 uid가 없으면 새로 등록합니다.
        user = db.query(User).filter(User.uid == uid).first()
        if not user:
            user = User(uid=uid)
            db.add(user)
            db.commit()

        # 2. 기억 불러오기: 해당 유저의 최근 대화 4개를 시간 역순으로 가져옵니다.
        past_chats = db.query(ChatHistory).filter(ChatHistory.uid == uid)\
                       .order_by(desc(ChatHistory.timestamp)).limit(4).all()
        past_chats.reverse() # 시간 순서대로 다시 정렬 (프롬프트 구성용)

        # 3. 프롬프트 조립: 시스템 지시문 + 현재 상황(날씨/시간) + 과거 대화 + 현재 질문
        system_prompt = (
            "너는 유능하고 다정한 한국어 AI 비서야. "
            "사용자의 질문에 친절하게 대답하되, 아래 [현재 상황] 정보는 사용자가 물어보거나 "
            "답변에 꼭 필요할 때만 자연스럽게 언급해줘. 굳이 매번 말할 필요는 없어. "
            "답변은 2~3문장 이내로 짧고 간결하게 '해요체'로 해줘.\n\n"
            f"[현재 상황]\n"
            f"- 시간: {current_time}\n"
            f"- 날씨: {weather_info}"
        )
        full_prompt = f"<|system|>\n{system_prompt}<|end|>\n"

        # 과거 대화를 프롬프트에 끼워 넣어 AI가 맥락을 이해하게 합니다.
        for chat in past_chats:
            full_prompt += f"<|user|>\n{chat.user_text}<|end|>\n<|assistant|>\n{chat.ai_response}<|end|>\n"

        full_prompt += f"<|user|>\n{user_text}<|end|>\n<|assistant|>\n"

        # 4. Ollama 요청: 조립된 프롬프트를 AI 모델에게 전달합니다.
        full_response_text = ""
        response = requests.post(OLLAMA_URL, json={
            "model": MODEL_NAME, "prompt": full_prompt, "stream": True
        }, stream=True, timeout=120)

        # 5. 스트리밍 응답: AI가 한 단어씩 생성할 때마다 안드로이드로 즉시 쏴줍니다.
        for line in response.iter_lines():
            if line:
                chunk = json.loads(line)
                token = chunk.get("response", "")
                full_response_text += token
                yield f"data: {json.dumps({'token': token, 'done': False})}\n\n"

                # 6. 대화 완료: AI 답변이 끝나면 이 대화 세트를 DB에 저장합니다.
                if chunk.get("done"):
                    try:
                        new_chat = ChatHistory(uid=uid, user_text=user_text, ai_response=full_response_text)
                        db.add(new_chat)
                        db.commit() # 성공 시 저장
                    except Exception as e:
                        db.rollback() # 에러 발생 시 진행 중인 작업 취소
                        logger.error(f"DB Commit Error: {e}")

                    # 최종 결과(TTS URL 포함)를 전송하고 루프를 종료합니다.
                    audio_url = f"/get-audio/{os.path.basename(generate_tts(full_response_text))}" if client_type == "web" else None
                    yield f"data: {json.dumps({'token': '', 'done': True, 'audio_url': audio_url, 'full_text': full_response_text})}\n\n"
                    break
    finally:
        db.close() # 통신이 끝나면 DB 연결을 안전하게 닫습니다.

# [generate_tts 함수]
# 텍스트를 mp3 파일로 변환합니다 (주로 웹 테스트용).
def generate_tts(text):
    # 파일 이름을 타임스탬프 기반으로 생성하여 중복을 방지합니다.
    audio_filename = f"res_{int(time.time())}.mp3"
    audio_path = os.path.join("/tmp", audio_filename) if os.path.exists("/tmp") else audio_filename
    tts_url = f"https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=ko&q={quote(text[:200])}"
    try:
        audio_res = requests.get(tts_url)
        with open(audio_path, "wb") as f:
            f.write(audio_res.content)
    except Exception as e:
        logger.error(f"TTS Error: {e}")
    return audio_path

# [get_weather_info 함수]
# 위도(lat)와 경도(lon)를 받아 해당 지역의 현재 날씨 정보를 텍스트로 반환합니다.
def get_weather_info(lat, lon):
    if lat is None or lon is None: return "현재 위치 정보를 가져올 수 없습니다."
    try:
        url = f"https://api.openweathermap.org/data/2.5/weather?lat={lat}&lon={lon}&appid={WEATHER_API_KEY}&units=metric&lang=kr"
        res = requests.get(url, timeout=3)
        data = res.json()
        return f"현재 위치는 {data['name']}이며, {data['weather'][0]['description']}, 온도는 {data['main']['temp']}도입니다."
    except: return "날씨 정보를 불러오는 중 오류가 발생했습니다."

@app.get("/")
async def root():
    return {"message": "Server is running", "ollama_endpoint": OLLAMA_URL}

# [chat_stream 엔드포인트]
# 안드로이드 앱에서 가장 먼저 호출하는 문(Gate)입니다.
# 전달받은 파라미터(text, uid, 좌표)를 정리하여 AI 스트리밍 함수로 넘겨줍니다.
@app.get("/chat-stream")
async def chat_stream(text: str, uid: str, lat: float = None, lon: float = None, client_type: str = "app"):
    # 현재 위치의 날씨 정보를 가져옵니다.
    weather_context = get_weather_info(lat, lon)
    # 현재 한국 시간을 구합니다.
    tz_korea = pytz.timezone('Asia/Seoul')
    current_time = datetime.now(tz_korea).strftime("%Y년 %m월 %d일 %H시 %M분")

    # 모든 준비된 정보를 담아 AI 답변 생성을 시작합니다.
    return StreamingResponse(
        generate_ai_stream(uid, text, weather_context, current_time, client_type),
        media_type="text/event-stream"
    )

@app.get("/get-audio/{filename}")
async def get_audio(filename: str):
    file_path = os.path.join("/tmp", filename) if os.path.exists("/tmp") else filename
    return FileResponse(file_path)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
