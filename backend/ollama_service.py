import httpx
import json
import asyncio

# 동시 요청 제한
ai_semaphore = asyncio.Semaphore(1)

async def call_ollama_stream(prompt: str):
    url = "http://ollama-server:11434/api/generate"
    payload = {
        "model": "llama3:8b",
        "prompt": prompt,
        "stream": True
    }

    print(f"--- Ollama 요청 시작: {prompt[:20]}... ---", flush=True)

    async with httpx.AsyncClient(timeout=None) as client:
        try:
            async with client.stream("POST", url, json=payload) as response:
                if response.status_code != 200:
                    yield "서버 응답 오류가 발생했습니다."
                    return

                async for line in response.aiter_lines():
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        token = data.get("response", "")
                        if token:
                            # 로그가 즉시 터미널에 보이도록 flush=True 사용
                            print(token, end="", flush=True)
                            yield token
                        if data.get("done"):
                            print("\n--- 생성 완료 ---", flush=True)
                            break
                    except json.JSONDecodeError:
                        continue
        except Exception as e:
            print(f"\n[오류 발생]: {e}", flush=True)
            yield f"연결 오류가 발생했습니다: {str(e)}"
