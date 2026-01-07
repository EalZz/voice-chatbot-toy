from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator
from routers import router

app = FastAPI(title="Voice Chatbot Toy Project")

# 중요: Instrumentator를 startup 외부로 빼서 '연결 안됨' 에러 해결
Instrumentator().instrument(app).expose(app)

# 라우터 등록
app.include_router(router)

@app.get("/")
def health_check():
    return {"status": "ok", "message": "Voice Chatbot Server is running"}
