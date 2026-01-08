from fastapi import APIRouter, Depends, Query
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
import json
from datetime import datetime, timedelta  # timedelta가 반드시 있어야 합니다.
from database import get_db, save_chat_with_limit, get_recent_chats, SessionLocal
from ollama_service import ai_semaphore, call_ollama_stream

router = APIRouter()

@router.get("/chat-stream")
async def chat(
    text: str = Query(...),
    uid: str = Query(...),
    lat: float = None,
    lon: float = None,
    db: Session = Depends(get_db)
):
    # 1. 한국 시간(KST) 계산 (UTC+9)
    now_kst = datetime.utcnow() + timedelta(hours=9)
    current_time_str = now_kst.strftime("%Y-%m-%d %H:%M:%S")

    # 2. 이전 대화 내역 조회
    history = get_recent_chats(db, uid)
    history_context = "\n".join([f"User: {h.user_message}\nAI: {h.ai_message}" for h in reversed(history)])

    async def event_generator():
        full_ai_response = ""
        
        async with ai_semaphore:
            # 3. 강화된 지시 사항 (날씨/시간 남발 방지)
            system_instruction = (
                "당신은 유능한 AI 비서입니다. 아래 [참고 정보]는 사용자가 직접적으로 질문할 때만 답변에 활용하세요.\n"
                "1. 사용자가 '몇 시야?' 혹은 '날씨 어때?'라고 묻지 않는다면 절대 [참고 정보]를 언급하지 마세요.\n"
                "2. 단순 인사나 일반적인 질문에는 친구처럼 자연스럽게 대화만 하세요.\n"
                "3. 답변에 'User:', 'AI:' 라벨을 붙이지 말고 한국어로 짧게 대답하세요."
            )

            # 4. 프롬프트 구조화 (정보 격리)
            full_prompt = (
                f"### [시스템 지시]\n{system_instruction}\n\n"
                f"### [현재 상황 데이터]\n"
                f"- 현재 시각: {current_time_str}\n"
                f"- 사용자 위치 좌표: 위도 {lat}, 경도 {lon}\n"
                f"- 현재 날씨 상태: 구름 조금, 산책하기 좋은 날씨\n\n" # 일단 테스트용으로 추가
                f"### [이전 대화]\n{history_context}\n\n"
                f"### [현재 질문]\nUser: {text}\nAI:"
            )   
            print(f"--- [UID: {uid}] 스트리밍 시작 ---", flush=True)

            async for chunk in call_ollama_stream(full_prompt):
                full_ai_response += chunk
                # 앱의 ChatStreamManager가 읽는 키인 'message'로 전달
                yield f"data: {json.dumps({'message': chunk, 'done': False}, ensure_ascii=False)}\n\n"
        # 안드로이드가 '응답 완료'를 인지하고 speak()를 호출할 수 있게 합니다.
        yield f"data: {json.dumps({'message': '', 'done': True}, ensure_ascii=False)}\n\n"
        # 5. 종료 후 DB 저장 및 완료 신호 전송
        if full_ai_response:
            new_db = SessionLocal()
            try:
                save_chat_with_limit(new_db, uid, text, full_ai_response)
                # 완료 신호 전송 (done: True)
                print(f"--- [UID: {uid}] 저장 및 완료 ---", flush=True)
            except Exception as e:
                print(f"DB 저장 오류: {e}", flush=True)
            finally:
                new_db.close()

    return StreamingResponse(event_generator(), media_type="text/event-stream")
