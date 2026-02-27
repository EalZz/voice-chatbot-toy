from fastapi import APIRouter, Depends, Query, Request  # Request 추가
from fastapi.responses import StreamingResponse
from sqlalchemy.orm import Session
import json
from datetime import datetime, timedelta
from database import get_db, save_chat_with_limit, get_recent_chats, SessionLocal
from ollama_service import call_ollama_stream

router = APIRouter()

@router.get("/chat-stream")
async def chat(
    request: Request,    # app.state에 접근하기 위해 추가
    text: str = Query(...),
    uid: str = Query(...),
    lat: float = None,
    lon: float = None,
    db: Session = Depends(get_db)
):
    # 1. 한국 시간(KST) 계산
    now_kst = datetime.utcnow() + timedelta(hours=9)
    current_time_str = now_kst.strftime("%Y-%m-%d %H:%M:%S")

    # 2. 이전 대화 내역 조회 및 Llama 3 포맷팅
    history = get_recent_chats(db, uid)
    history_context = ""
    for h in reversed(history):
        history_context += f"<|start_header_id|>user<|end_header_id|>\n\n{h.user_message}<|eot_id|>\n"
        history_context += f"<|start_header_id|>assistant<|end_header_id|>\n\n{h.ai_message}<|eot_id|>\n"

    # --- [RAG] 3. 법률 데이터 검색 수행 ---
    legal_context = ""
    # main.py에서 등록한 retriever 가져오기
    retriever = getattr(request.app.state, "legal_retriever", None)
    
    if retriever:
        print(f"🔍 [RAG] 법률 데이터 검색 중: {text[:20]}...", flush=True)
        try:
            # 사용자 질문으로 관련 조각 3개 추출
            search_results = retriever.invoke(text)
            for i, doc in enumerate(search_results):
                legal_context += f"법률 참고 자료 {i+1}: {doc.page_content}\n\n"
        except Exception as e:
            print(f"❌ 검색 중 오류 발생: {e}", flush=True)
    # --------------------------------------

    async def event_generator():
        full_ai_response = ""

        formatted_history = f"--- [이전 대화 참고용 시작] ---\n{history_context}\n--- [이전 대화 참고용 끝] ---"

        # 4. 시스템 지침 및 검색된 법률 정보 결합
        system_instruction = (
            "당신은 유능한 법률 비서입니다. 규칙:\n"
            "1. 아래 제공된 '법률 참고 자료'를 최우선으로 참고하여 질문에 답변하세요.\n"
            "2. 참고 자료에 없는 내용은 일반적인 법률 지식으로 간략히 답변하세요.\n"
            "3. 제공된 '이전 대화 참고용'은 문맥 파악에만 사용하세요.\n"
            "4. 답변만 즉시 출력하고, '분석 결과'나 '참고 자료에 따르면' 같은 서술은 생략하세요.\n"
            "5. 답변 마지막에 짧게 단 1번만 '정확한 사실관계 파악은 변호사와 상담을 권장합니다'라고 덧붙이세요. 절대 변호사 선임비 등 불필요한 비용 정보를 임의로 생성하거나 반복 출력하지 마세요."
        )

        # 법률 참고 자료 섹션 구성
        legal_info_section = f"\n\n--- [법률 참고 자료 시작] ---\n{legal_context}--- [법률 참고 자료 끝] ---\n" if legal_context else ""

        # Llama 3 최종 프롬프트 구성
        full_prompt = (
            f"<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n{system_instruction}{legal_info_section}<|eot_id|>"
            f"{formatted_history}"
            f"<|start_header_id|>user<|end_header_id|>\n\n{text}<|eot_id|>"
            f"<|start_header_id|>assistant<|end_header_id|>\n\n"
        )
        
        print(f"--- [UID: {uid}] RAG 포함 스트리밍 시작 ---", flush=True)

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
            "X-Accel-Buffering": "no",
        }
    )
