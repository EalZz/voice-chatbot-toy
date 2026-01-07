import httpx
import json
import asyncio
import os

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://ollama-server:11434/api/generate")
# 동시 사용자 20명 제한 세마포어
ai_semaphore = asyncio.Semaphore(20)

async def call_ollama_stream(prompt: str):
    payload = {
        "model": "llama3", # 사용하시는 모델명에 맞게 수정
        "prompt": prompt,
        "stream": True
    }
    
    async with httpx.AsyncClient(timeout=None) as client:
        async with client.stream("POST", OLLAMA_URL, json=payload) as response:
            async for line in response.aiter_lines():
                if line:
                    data = json.loads(line)
                    yield data.get("response", "")
                    if data.get("done"):
                        break
