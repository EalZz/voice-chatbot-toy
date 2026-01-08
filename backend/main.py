from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator
from routers import router as chat_router

app = FastAPI()

# 프로메테우스 설정 
Instrumentator().instrument(app).expose(app)

# 라우터 등록
app.include_router(chat_router)

@app.get("/")
async def root():
    return {"status": "ok", "message": "Voice Chatbot API is running"}
