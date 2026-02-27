from fastapi import FastAPI
from prometheus_fastapi_instrumentator import Instrumentator
from routers import router as chat_router
import pandas as pd
from langchain_community.retrievers import BM25Retriever
from langchain_core.documents import Document

app = FastAPI()

# --- [RAG 추가] 서버 시작 시 검색 엔진 로드 ---
print("🚀 법률 데이터 검색기를 로드하는 중...")
df = pd.read_parquet("corpus_chunked.parquet")
documents = [
    Document(
        page_content=row['contents'],
        metadata={'parent_id': row['parent_id']}
    ) for _, row in df.iterrows()
]
# 다른 파일에서도 쓸 수 있도록 app.state에 저장합니다.
app.state.legal_retriever = BM25Retriever.from_documents(documents)
app.state.legal_retriever.k = 3 
print("✅ 검색기 로드 완료!")
# ------------------------------------------

# 프로메테우스 설정
Instrumentator().instrument(app).expose(app)

# 라우터 등록
app.include_router(chat_router)

@app.get("/")
async def root():
    return {"status": "ok", "message": "Voice Chatbot API is running"}

@app.get("/ping")
async def ping():
    return {"status": "alive"}
