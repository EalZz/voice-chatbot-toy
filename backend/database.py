from sqlalchemy import Column, Integer, String, DateTime, create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker, Session
import os
from datetime import datetime
import pytz

DATABASE_URL = os.getenv("DATABASE_URL", "postgresql://user:password@db:5432/voice_db")

engine = create_engine(DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

class ChatLog(Base):
    __tablename__ = "chat_logs"
    id = Column(Integer, primary_key=True, index=True)
    uid = Column(String, index=True)
    user_message = Column(String)
    ai_message = Column(String)
    timestamp = Column(DateTime, default=lambda: datetime.now(pytz.timezone('Asia/Seoul')))

Base.metadata.create_all(bind=engine)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

def save_chat_with_limit(db: Session, uid: str, user_msg: str, ai_msg: str):
    try:
        # 1. 현재 개수 확인
        count = db.query(ChatLog).filter(ChatLog.uid == uid).count()

        # 2. 100개 초과 시 가장 오래된 것 삭제
        if count >= 100:
            oldest = db.query(ChatLog).filter(ChatLog.uid == uid).order_by(ChatLog.id.asc()).first()
            if oldest:
                db.delete(oldest)
                # 여기서 commit() 하지 않고 대기

        # 3. 새 로그 저장
        new_log = ChatLog(uid=uid, user_message=user_msg, ai_message=ai_msg)
        db.add(new_log)
        
        # 4. 삭제와 저장을 한 번에 반영
        db.commit()
    except Exception as e:
        db.rollback() # 에러 발생 시 롤백
        print(f"Database Error: {e}")
        raise e

def get_recent_chats(db: Session, uid: str, limit: int = 4):
    return db.query(ChatLog).filter(ChatLog.uid == uid).order_by(ChatLog.id.desc()).limit(limit).all()
