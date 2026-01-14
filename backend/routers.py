from fastapi import APIRouter, Depends, Query
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
import json
from datetime import datetime, timedelta
from database import get_db, save_chat_with_limit, get_recent_chats, SessionLocal
#from ollama_service import ai_semaphore, call_ollama_stream
from ollama_service import call_ollama_stream

router = APIRouter()

@router.get("/chat-stream")
async def chat(
    text: str = Query(...),
    uid: str = Query(...),
    lat: float = None,
    lon: float = None,
    db: Session = Depends(get_db)
):
    # 1. 한국 시간(KST) 계산
    now_kst = datetime.utcnow() + timedelta(hours=9)
    current_time_str = now_kst.strftime("%Y-%m-%d %H:%M:%S")

    # 2. 이전 대화 내역 조회 및 Llama 3 포맷팅 (개행 \n 추가)
    history = get_recent_chats(db, uid)
    history_context = ""
    for h in reversed(history):
        history_context += f"<|start_header_id|>user<|end_header_id|>\n\n{h.user_message}<|eot_id|>\n"
        history_context += f"<|start_header_id|>assistant<|end_header_id|>\n\n{h.ai_message}<|eot_id|>\n"

    async def event_generator():
        full_ai_response = ""

        #async with ai_semaphore:
        formatted_history = f"--- [이전 대화 참고용 시작] ---\n{history_context}\n--- [이전 대화 참고용 끝] ---"

        system_instruction = (
            "당신은 유능한 법률 비서입니다. 규칙:\n"
            "1. 제공된 '이전 대화 참고용'은 현재 대화의 문맥 파악에만 사용하세요.\n"
            "2. 현재 질문에 대한 답변만 즉시 출력하고, 과거 답변 내용을 반복하지 마세요.\n"
            "3. 법률 비서 지침이나 분석 과정은 절대 언급하지 마세요."
        )

        full_prompt = (
            f"<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n{system_instruction}<|eot_id|>"
            f"{formatted_history}" # 참고용임을 명시한 내역
            f"<|start_header_id|>user<|end_header_id|>\n\n{text}<|eot_id|>"
            f"<|start_header_id|>assistant<|end_header_id|>\n\n"
        )
        print(f"--- [UID: {uid}] 스트리밍 시작 ---", flush=True)

        async for chunk in call_ollama_stream(full_prompt):
            full_ai_response += chunk
            yield f"data: {json.dumps({'message': chunk, 'done': False}, ensure_ascii=False)}\n\n"

        # 종료 신호 전송
        yield f"data: {json.dumps({'message': '', 'done': True}, ensure_ascii=False)}\n\n"

        # 5. DB 저장
        if full_ai_response:
            new_db = SessionLocal()
            try:
                save_chat_with_limit(new_db, uid, text, full_ai_response)
                print(f"--- [UID: {uid}] 대화 저장 완료 ---", flush=True)
            except Exception as e:
                print(f"DB 저장 오류: {e}", flush=True)
            finally:
                new_db.close()

    return StreamingResponse(
        event_generator(), 
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",  # ngrok/proxy 버퍼링 방지 핵심
        }
    )
