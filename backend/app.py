from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from datetime import datetime
import os
import logging
import json
import pytz
import httpx
import sys
from sqlalchemy import desc

# RAG 및 데이터베이스 모듈 임포트
from rag import search_relevant_context, build_rag_context, get_first_referenced_id, get_model
from database import (
    SessionUser, get_or_create_profile, create_chat_session, 
    save_chat_message, ChatSession, ChatMessage, get_user_sessions, get_session_history
)

# 로깅
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("VoiceAI-Server")

app = FastAPI()

# CORS 설정
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# AI 설정
MODEL_NAME = "gemma2"
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "ollama-server")
OLLAMA_CHAT_URL = f"http://{OLLAMA_HOST}:11434/api/chat"

@app.on_event("startup")
async def startup_event():
    logger.info("--- [STARTUP] 가동 준비 완료 ---")
    get_model()

# ------------------------------------------------------------
# [세션 및 히스토리 API]
# ------------------------------------------------------------

@app.get("/sessions/{uid}")
async def list_user_sessions_api(uid: str):
    db = SessionUser()
    try:
        sessions = get_user_sessions(db, uid)
        return [{"id": str(s.session_id), "title": s.title if s.title else "새 대화"} for s in sessions]
    finally:
        db.close()

@app.get("/sessions/{session_id}/history")
async def get_history_api(session_id: str):
    db = SessionUser()
    try:
        history = get_session_history(db, session_id)
        return [{"content": m.content, "isUser": (m.role == "user")} for m in history]
    finally:
        db.close()

# ------------------------------------------------------------
# [AI 스트리밍 엔진 - 법적 근거 강화]
# ------------------------------------------------------------

async def generate_ai_stream(request: Request, uid: str, user_text: str, current_time: str, session_id: str = None):
    db = SessionUser()
    try:
        profile = get_or_create_profile(db, uid)
        session = None
        
        if session_id and session_id not in ["null", "undefined", ""]:
            session = db.query(ChatSession).filter(ChatSession.session_id == session_id).first()
        
        if not session:
            session = db.query(ChatSession).filter(ChatSession.profile_id == profile.profile_id).order_by(ChatSession.updated_at.desc()).first()
        
        if not session:
            session = create_chat_session(db, profile.profile_id, first_query=user_text)

        past_msgs = db.query(ChatMessage)\
                      .filter(ChatMessage.session_id == session.session_id)\
                      .order_by(ChatMessage.created_at.desc())\
                      .limit(6).all()
        past_msgs.reverse()

        # RAG 검색
        rag_results = search_relevant_context(user_text)
        rag_context = build_rag_context(rag_results)
        ref_id, ref_type = get_first_referenced_id(rag_results)

        # [핵심] 법적 근거 섹션 미리 구성
        legal_basis_content = ""
        if rag_results["statutes"]:
            legal_basis_content = "\n\n---\n**[법적 근거 및 참고 문헌]**\n" + "\n".join([f"- {s['law_name']} {s['article']}" for s in rag_results["statutes"]])
        elif rag_results["qa"]:
            legal_basis_content = "\n\n---\n**[참고 자료]**\n- 국가 법령 정보 및 생활법률 상담 가이드라인"

        # 프롬프트 구성 (AI에게 명시적으로 요청)
        system_msg = (
            "당신은 대한민국의 법률 전문 비서입니다. 제공된 [법령 정보]를 바탕으로 정중하고 명확하게 답변하세요. "
            "답변 끝에는 반드시 사용된 법령 정보를 요약하여 명시해야 합니다.\n\n"
            f"{rag_context}\n\n"
            f"현재 시각: {current_time}"
        )
        
        messages = [{"role": "system", "content": system_msg}]
        for msg in past_msgs:
            messages.append({"role": "user" if msg.role == "user" else "assistant", "content": msg.content})
        messages.append({"role": "user", "content": user_text})

        save_chat_message(db, session.session_id, role="user", content=user_text)
        logger.info(f"--- [AI 요청] SID: {session.session_id} ---")

        full_resp = ""
        async with httpx.AsyncClient(timeout=180.0) as client:
            async with client.stream("POST", OLLAMA_CHAT_URL, json={
                "model": MODEL_NAME, "messages": messages, "stream": True,
                "options": {"temperature": 0.3}
            }) as response:
                
                async for line in response.aiter_lines():
                    if await request.is_disconnected():
                        break

                    if line:
                        try:
                            chunk = json.loads(line)
                            token = chunk.get("message", {}).get("content", "")
                            
                            if token:
                                full_resp += token
                                sys.stdout.write(token)
                                sys.stdout.flush()
                                yield f"data: {json.dumps({'message': token, 'done': False}, ensure_ascii=False)}\n\n"

                            if chunk.get("done"):
                                # [핵심] 만약 AI가 답변 중에 법적 근거를 빼먹었다면 서버에서 강제로 추가
                                if legal_basis_content and "[법적 근거]" not in full_resp:
                                    yield f"data: {json.dumps({'message': legal_basis_content, 'done': False}, ensure_ascii=False)}\n\n"
                                    full_resp += legal_basis_content
                                    print(legal_basis_content)

                                save_chat_message(db, session.session_id, role="ai", content=full_resp, ref_type=ref_type, ref_id=ref_id)
                                logger.info("\n--- [완성] 답변 저장 완료 ---")
                                yield f"data: {json.dumps({'message': '', 'done': True}, ensure_ascii=False)}\n\n"
                                break
                        except:
                            continue
    finally:
        db.close()

@app.get("/chat-stream")
async def chat_stream(request: Request, text: str, uid: str, session_id: str = None):
    return StreamingResponse(
        generate_ai_stream(request, uid, text, datetime.now(pytz.timezone('Asia/Seoul')).isoformat(), session_id),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no"
        }
    )

@app.get("/metrics")
@app.get("/metrics/")
@app.get("/api/metrics")
async def metrics():
    return {"status": "ok"}

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
