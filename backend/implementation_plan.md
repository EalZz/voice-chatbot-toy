# 📋 Chatbot Backend Implementation Plan (Phase 2 - Updated)

## **1. 현재 목표**
- [x] RAG 파이프라인 구현 (`pgvector` + `ko-sroberta-multitask`)
- [x] 세션 기반 대화 관리 시스템 구축 (`Profile` -> `Session` -> `Message`)
- [x] LLM(Ollama) 스트리밍 응답 연동 (`Llama3:8b`)

## **2. 진행 현황**
- [x] **RAG 통합 완료**: 법령 및 Q&A를 지식 베이스(KB)로 사용하는 검색 엔진 가동.
- [x] **DB 아키텍처 전환 완료**: `user_db`와 `knowledge_db`를 분리하여 운영. `referenced_id` 추적 기능 포함.
- [x] **app.py 리팩토링 및 정리**: 복잡했던 레거시 파일들을 정리하여 서비스 신뢰도 확보.

## **3. 식별된 위험 요소 (Current Risks)**
1. **세션 로직의 불일치**: DB는 멀티 세션 구조이나 API는 단일 세션으로 동작함 (상태 모순).
2. **앱 수명 주기 제어 부재**: 앱이 백그라운드로 가도 스트리밍과 음성 생성이 멈추지 않아 자원 낭비.
3. **DB 저장 파라미터 오류**: `app.py`와 `database.py` 간의 인자명 불일치로 인한 저장 실패 가능성.
4. **로그 가독성 저하**: 주기적인 `/metrics` 404 에러로 정작 중요한 에러 로그 확인이 어려움.

## **4. 향후 예정 작업**
- [ ] 정식 세션 초기화(Flush) API 구현
- [ ] STT 라이브러리 재설치 및 모델 로딩 최적화
- [ ] 안드로이드 클라이언트와의 최종 연동 테스트
- [ ] 유사도 기반 품질 평가 및 Threshold 보정
