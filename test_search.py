import pandas as pd
from langchain_community.retrievers import BM25Retriever
from langchain_core.documents import Document

def run_test():
    print("📂 데이터 로드 중...")
    df = pd.read_parquet("corpus_chunked.parquet")
    
    # LangChain 형식으로 변환
    documents = [
        Document(
            page_content=row['contents'],
            metadata={'parent_id': row['parent_id']}
        ) for _, row in df.iterrows()
    ]
    
    # BM25 검색기 생성
    print(f"🔍 {len(documents)}개 조각으로 검색기 초기화 중...")
    retriever = BM25Retriever.from_documents(documents)
    retriever.k = 3  # 상위 3개 결과 출력
    
    # 테스트 질문
    query = "상고 기각 사유가 무엇인가요?"
    print(f"\n❓ 테스트 질문: {query}")
    
    # 검색 실행
    results = retriever.invoke(query)
    
    print("\n🎯 검색 결과:")
    for i, doc in enumerate(results):
        print(f"[{i+1}] {doc.page_content[:100]}...")
        print(f"    (원본 ID: {doc.metadata['parent_id']})\n")

if __name__ == "__main__":
    run_test()
