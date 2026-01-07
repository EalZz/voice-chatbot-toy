from fastapi import APIRouter, Depends, Header, HTTPException
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
import json
from database import get_db, save_chat_with_limit, get_recent_chats
from ollama_service import ai_semaphore, call_ollama_stream

router = APIRouter()

@router.post("/chat")
async def chat(user_text: str, uid: str = Header(None), db: Session = Depends(get_db)):
    if not uid:
        raise HTTPException(status_code=400, detail="UID is missing")

    async def event_generator():
        # 세마포어 적용: 20명 초과 시 여기서 대기
        async with ai_semaphore:
            # 장기 기억 (최근 4개) 가져오기
            history = get_recent_chats(db, uid)
            history_context = "\n".join([f"User: {h.user_message}\nAI: {h.ai_message}" for h in reversed(history)])
            
            full_prompt = f"{history_context}\nUser: {user_text}\nAI:"
            full_ai_response = ""

            async for chunk in call_ollama_stream(full_prompt):
                full_ai_response += chunk
                yield f"data: {json.dumps({'message': chunk})}\n\n"
            
            # 응답 종료 후 DB에 저장 (100개 제한 적용)
            save_chat_with_limit(db, uid, user_text, full_ai_response)

    return StreamingResponse(event_generator(), media_type="text/event-stream")
