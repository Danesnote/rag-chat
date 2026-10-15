# RAG Chat

사내 문서(규정·지침·매뉴얼 등)를 근거로 답변하는 **멀티턴 RAG(Retrieval-Augmented Generation) 챗봇**입니다.
검색부터 답변 스트리밍까지 전 과정을 Spring Boot 로 직접 구현했고, 외부 구성 요소는 모두 **오픈소스**
(PostgreSQL + pgvector, HuggingFace Text Embeddings Inference, vLLM/Ollama)와 **표준 API** 로만 연결합니다.

![Java](https://img.shields.io/badge/Java-21-007396) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F) ![pgvector](https://img.shields.io/badge/PostgreSQL-pgvector-336791) ![LLM](https://img.shields.io/badge/LLM-OpenAI%20compatible-412991)

---

## 주요 기능

| 영역 | 내용 |
|---|---|
| **하이브리드 검색** | pgvector 코사인 검색 + 키워드(`pg_trgm`) 검색을 **RRF(Reciprocal Rank Fusion)** 로 결합 |
| **리랭킹** | Cross-encoder(`bge-reranker-v2-m3`)로 상위 후보 재정렬. 장애 시 RRF 순위로 자동 대체 |
| **인접 청크 확장** | 검색된 청크의 **앞/뒤 청크**를 독립 근거로 함께 제공해 조문·단서가 잘리는 문제 보완 |
| **조문 인식 청킹** | `제N조(제목)` 단위로 자르고 장·절 경로를 붙여, 모든 청크가 자기 조문 표기로 시작 |
| **문서 적재 API** | `.txt` / `.md` 업로드 → 청킹 → 임베딩 → 저장을 한 트랜잭션으로 처리 |
| **스트리밍 답변** | LLM 토큰을 SSE 로 실시간 전달 |
| **멀티턴 대화** | 오래된 대화는 LLM 으로 **누적 요약**, 최근 대화는 **토큰 예산 안에서 원문 유지** |
| **후속 질문 재작성** | "그럼 예외는?" 같은 생략형 질문을 이전 대화로 **완결된 검색 질의**로 바꿔 검색 |
| **토큰 예산 배분** | 컨텍스트 한도 안에서 시스템 프롬프트 / 요약 / 이력 / 검색 문서 / 생성 토큰을 동적으로 배분 |
| **근거 기반 출처** | LLM 이 `[문서N]` 번호로 인용하게 하고, 답변을 파싱해 **실제로 인용된 문서만** 출처로 표시 |
| **환각 억제 프롬프트** | 질문 유형(근거 없음 / 불명확 / 범위 밖 / 정상)을 판정하고 근거 없는 생성 금지 |
| **보안** | DOMPurify 새니타이즈, 입력 마크업 차단, 세션 고정 방어, 토큰 URL 비노출, 비밀값 환경 변수 주입 |

---

## 아키텍처

```
┌─────────┐  POST /chat/api/stream (SSE)  ┌──────────────────────────────┐
│ Browser │ ────────────────────────────> │  RAG Chat (Spring Boot)      │
│         │ <───── 토큰 단위 스트리밍 ───── │                              │
└─────────┘                               └──┬──────────┬──────────┬─────┘
                                             │          │          │
                 ┌───────────────────────────┘          │          └───────────────┐
                 ▼                                      ▼                          ▼
   ┌───────────────────────────┐      ┌──────────────────────────┐   ┌──────────────────────┐
   │ PostgreSQL + pgvector     │      │ Text Embeddings Inference│   │ LLM (OpenAI 호환)     │
   │ · 문서 청크 + 벡터 (HNSW)  │      │ · bge-m3 임베딩          │   │ vLLM / Ollama 등      │
   │ · 키워드 (pg_trgm)         │      │ · bge-reranker-v2-m3     │   │                      │
   │ · 대화방 / 메시지 / 요약    │      └──────────────────────────┘   └──────────────────────┘
   └───────────────────────────┘
```

### 검색 파이프라인

```
질의 ─┬─ 임베딩 → pgvector 코사인 검색 (top 20) ─┐
      └─ 키워드 추출 → ILIKE 검색 (top 20) ──────┴─ RRF 결합 → 리랭커 (top 5) → [모드 2] 앞/뒤 청크 확장
```

### 질문 1건의 처리 흐름

1. **이력 조립** — 최신 요약 + 예산 안의 최근 대화 원문 (`ConversationContextService`)
2. **질의 재작성** — 후속 질문이면 독립적인 검색 질의로 변환 (`QueryRewriteService`)
3. **검색** — 하이브리드 검색 + 리랭킹 + 인접 청크 확장 (`HybridSearchService`, `RetrievalService`)
4. **프롬프트 조립** — 남은 토큰 예산만큼 문서를 `[CONTEXT]`에 담음 (`ChatService`)
5. **스트리밍 생성** — LLM 응답을 SSE 로 중계
6. **후처리** — `[출처]` 파싱으로 채택 문서만 표시, 비동기 저장, 필요 시 요약 갱신 (`SummaryService`)

설계 상세는 [`docs/PROJECT_GUIDE.md`](docs/PROJECT_GUIDE.md), 프론트엔드 구조는 [`docs/UI_GUIDE.md`](docs/UI_GUIDE.md)에 정리했습니다.

---

## 사용 기술 및 라이브러리

### Backend

| 기술 | 버전 | 용도 |
|---|---|---|
| Java | 21 | 언어. **Virtual Threads** 로 LLM 스트리밍·외부 API 호출을 블로킹 코드 그대로 처리 |
| Spring Boot | 3.5.16 | 애플리케이션 프레임워크 |
| Spring Web (MVC) | Boot 관리 | REST API, `SseEmitter` 기반 SSE 스트리밍 |
| Spring Data JPA (Hibernate) | Boot 관리 | 대화방·메시지·요약 엔티티 저장 |
| Spring JDBC (`JdbcTemplate`) | Boot 관리 | pgvector 연산자(`<=>`)·배열 파라미터가 필요한 검색 쿼리와 청크 적재 |
| Thymeleaf | Boot 관리 | 서버 사이드 화면 템플릿 |
| Spring Boot Actuator | Boot 관리 | 헬스 체크 |
| Jackson | Boot 관리 | LLM·임베딩·리랭커 API JSON 직렬화 |
| `java.net.http.HttpClient` | JDK 내장 | 외부 API 호출 및 LLM 스트리밍 응답 수신 (`HttpUtils` 래퍼) |
| Lombok | Boot 관리 | 보일러플레이트 제거 |
| PostgreSQL JDBC Driver | 42.7.13 | DB 드라이버 |

### 검색 · 데이터

| 기술 | 버전 | 용도 |
|---|---|---|
| PostgreSQL | 16 | 대화 이력 + 문서 청크 저장 |
| pgvector | (이미지 `pgvector/pgvector:pg16`) | 벡터 저장, **HNSW** 인덱스 코사인 유사도 검색 |
| pg_trgm | PostgreSQL 확장 | trigram GIN 인덱스로 키워드 부분일치(`ILIKE`) 검색 가속 |
| Reciprocal Rank Fusion | 직접 구현 | 벡터·키워드 검색 결과를 순위 기반으로 결합 |

### AI 모델 · 서빙

| 기술 | 버전 | 용도 |
|---|---|---|
| HuggingFace Text Embeddings Inference | `cpu-1.5` | 임베딩·리랭커 모델 서빙 (OpenAI 호환 `/v1/embeddings`, `/rerank`) |
| `BAAI/bge-m3` | - | 다국어 임베딩 모델 (1024차원) |
| `BAAI/bge-reranker-v2-m3` | - | Cross-encoder 리랭커 |
| vLLM | - | LLM 서빙 (OpenAI 호환 Chat Completions, `/tokenize`) |
| `openai/gpt-oss-120b` | - | 기본 LLM (추론 모델, `reasoning_effort` 지원). Ollama 등 다른 OpenAI 호환 서버·모델로 교체 가능 |

### Frontend

| 라이브러리 | 버전 | 용도 |
|---|---|---|
| jQuery | 3.7.1 | DOM 조작, AJAX |
| Tailwind CSS | 3.4.17 | 유틸리티 CSS (사전 빌드한 정적 파일로 서빙) |
| marked | 15.0.12 | LLM 답변 Markdown → HTML 변환 |
| DOMPurify | 3.2.4 | 변환된 HTML 새니타이즈 (XSS 방어) |
| SweetAlert2 | 11.26.25 | 알림·확인·입력 대화상자 |
| Lucide | 0.469.0 | 아이콘 |
| Pretendard | 1.3.9 | 웹 폰트 (SIL OFL 1.1) |
| Fetch API + `ReadableStream` | 브라우저 내장 | SSE 스트리밍 수신 |

모든 프론트엔드 라이브러리는 CDN 없이 `static/` 에서 직접 서빙합니다 (외부 인터넷이 막힌 환경 대응).

### Build · Test · Infra

| 기술 | 버전 | 용도 |
|---|---|---|
| Gradle | 8.14.5 (Wrapper) | 빌드. WAR 패키징 — 내장 Tomcat 실행과 외장 Tomcat 배포 모두 가능 |
| JUnit 5 · AssertJ · Mockito | Boot 관리 | 단위 테스트 |
| Docker Compose | - | PostgreSQL(pgvector) · 임베딩 · 리랭커 서버 기동 |

---

## 빠른 시작

### 1. 인프라 기동 (DB · 임베딩 · 리랭커)

```bash
docker compose up -d
```

`postgres`(5432), `embedding`(8081), `reranker`(8082)가 뜹니다. 첫 기동 시 모델을 내려받느라 몇 분 걸립니다.
테이블은 애플리케이션 기동 시 [`db/schema.sql`](src/main/resources/db/schema.sql)로 자동 생성됩니다.

### 2. LLM 서버 준비

OpenAI 호환 `/v1/chat/completions`를 제공하는 서버면 무엇이든 됩니다.

| 환경 | 예시 |
|---|---|
| GPU 서버 (vLLM) | `vllm serve openai/gpt-oss-120b --port 8000` → 기본 설정 그대로 사용 |
| 로컬 PC (Ollama) | `ollama pull qwen2.5:7b` 후 `LLM_URL=http://localhost:11434/v1/chat/completions`, `LLM_MODEL=qwen2.5:7b` |

> Ollama 처럼 `/tokenize`가 없는 서버도 동작합니다. 토큰 수는 응답의 `usage` 또는 로컬 추정치로 계산합니다.

### 3. 프롬프트 파일 준비

**프롬프트는 저장소에 포함하지 않았습니다.** 실행 전에 아래 세 파일을 `src/main/resources/prompts/`에 만들어야 합니다.
(이 디렉터리는 `.gitignore`에 등록되어 있어 커밋되지 않습니다. 파일이 없으면 기동 시 프롬프트 로드 오류가 납니다.)

| 파일 | 역할 | 입력 / 출력 계약 |
|---|---|---|
| `system-prompt.md` | 답변 생성 지시문 | 입력: 마지막 user 메시지가 `[CONTEXT][문서1][구분: ..][문서명: ..][문서번호: ..][버전: ..][시행일자: ..] 본문 ... [USER_QUESTION]질문` 형태로 들어온다. 출력: 답변 끝에 `**[출처]**` 영역을 두고 각 줄을 `- [문서N] 문서명, 제N조(제목)` 형식으로 적어야 화면에 채택 출처가 표시된다 |
| `query-rewrite-prompt.md` | 후속 질문 → 검색 질의 재작성 | 입력: `[이전 대화]`와 `[현재 질문]`. 출력: 검색 질의 한 문장만 |
| `summary-prompt.md` | 오래된 대화 누적 요약 | 입력: `[기존 요약]`(선택)과 `[추가 대화]`. 출력: 요약 본문만 |

답변 지시문은 `rag.prompt.system-path`로 외부 파일 경로를 지정할 수도 있으며, 이 경우 재시작 없이 수정이 반영됩니다.

### 4. 애플리케이션 실행

```bash
./gradlew bootRun
```

`http://localhost:8080` 접속. 프로파일을 지정하지 않으면 `local`(SSO 생략)로 동작합니다.

### 5. 예시 문서 적재

[`samples/`](samples)에 가상의 회사 규정 5종(휴가, 여비, 정보보안, 법인카드, 재택근무)이 있습니다.

```bash
./scripts/load-samples.sh
```

이제 화면에서 "연차휴가는 어떻게 산정되나요?" 같은 질문을 할 수 있습니다.

---

## 환경 변수

| 변수 | 설명 | 기본값 |
|---|---|---|
| `LLM_URL` / `LLM_MODEL` / `LLM_API_KEY` | Chat Completions 서버 | `http://localhost:8000/v1/chat/completions` / `openai/gpt-oss-120b` / (없음) |
| `LLM_TOKENIZE_URL` | 토큰 수 계산 (vLLM 전용, 없으면 추정) | `http://localhost:8000/tokenize` |
| `EMBEDDING_URL` / `EMBEDDING_MODEL` | OpenAI 호환 임베딩 | `http://localhost:8081/v1/embeddings` / `BAAI/bge-m3` |
| `RERANKER_URL` / `RERANKER_ENABLED` | TEI `/rerank` | `http://localhost:8082/rerank` / `true` |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL | `jdbc:postgresql://localhost:5432/ragchat` / `postgres` / `postgres`(local) |

| 프로파일 | 용도 |
|---|---|
| `local` | 개인 PC. SSO 생략, 검색 방식 전환 UI 노출 |
| `dev` | 개발 서버. SSO + 개발 전용 로그인(`/dev/login`) |
| `test` | 테스트 서버(QA) |
| `prod` | 운영. SQL 로그·에러 상세 비노출, 문서 관리 API 닫힘, 스키마 자동 실행 안 함 |

---

## API

### 문서 관리 (`rag.ingest.enabled=true`일 때)

```bash
# 파일 업로드 (.txt / .md)
curl -F "file=@samples/leave-policy.txt" -F "title=휴가 규정" -F "version=3" -F "effectiveDate=2025-01-01" \
     http://localhost:8080/api/documents/upload

# 목록 / 삭제
curl http://localhost:8080/api/documents
curl -X DELETE http://localhost:8080/api/documents/1
```

### 검색만 실행 (LLM 호출 없이 근거 확인)

```bash
curl -H "Content-Type: application/json" -d '{"query":"미사용 연차 보상","mode":2}' \
     http://localhost:8080/api/search
```

### 채팅

`POST /chat/api/stream` (SSE) — 이벤트 `chatInfo`(대화방 번호) → 본문 토큰 → `used_sources`(채택 출처).

---

## 테스트

```bash
./gradlew test
```

| 대상 | 내용 |
|---|---|
| `TextChunkerTests`, `SampleDocumentChunkingTests` | 조문·부칙·Markdown·긴 조문 분할, 예시 문서 청크가 모두 조문 표기로 시작하는지 |
| `KeywordQueryTests`, `RankFusionTests` | 조사 제거·불용어·패턴 생성, RRF 점수와 순서 |
| `RetrievalServiceTests` | 인접 청크 배치 순서·중복 제거·첫 청크 경계 (Mockito) |
| `RetrievalPayloadTests`, `LlmChatPayloadTests` 등 | 임베딩·리랭커·LLM API 페이로드 계약 |

스프링 컨텍스트 기동 테스트는 `DB_URL` 환경 변수가 있을 때만 실행됩니다.

---

## 화면 커스터마이징

```properties
rag.ui.assistant-name=AI 어시스턴트       # 브라우저 제목·사이드바·인사 문구
rag.ui.model-label=                      # 상단 모델명 (비우면 rag.llm.model)
rag.ui.source-view-url=                  # 출처 원문 바로가기 URL 접두사 (비우면 버튼 숨김)
```


### Tailwind CSS 빌드

런타임 JIT 대신 정적 CSS 를 미리 생성해 커밋합니다(폐쇄망 대응). HTML/JS 의 Tailwind 클래스를 바꿨다면 다시 빌드합니다.

```bash
npx tailwindcss@3.4.17 -c tailwind.config.js -i tailwind.input.css -o src/main/resources/static/css/tailwind.css --minify
```

---

## 디렉터리 구조

```
├─ docker-compose.yml            PostgreSQL(pgvector) + TEI 임베딩/리랭커
├─ samples/                      예시 문서 (가상의 회사 규정)
├─ scripts/load-samples.sh       예시 문서 적재
└─ src/main
   ├─ java/com/ragchat
   │  ├─ retrieval/              청킹, 임베딩·리랭커 클라이언트, pgvector 저장소, 키워드 추출, RRF, 하이브리드 검색
   │  ├─ service/                ChatService(오케스트레이션), RetrievalService, DocumentIngestService,
   │  │                          질의 재작성, 요약, 토큰 계산, 이력
   │  ├─ controller/             채팅 화면·API(SSE), 문서 관리·검색 API, SSO 진입
   │  ├─ config/                 토큰 예산, 비동기 실행기, HTTP 클라이언트
   │  ├─ prompt/                 시스템 프롬프트 로더
   │  ├─ dto/, entity/, repository/, util/
   └─ resources
      ├─ db/schema.sql           스키마 (기동 시 자동 실행, 멱등)
      ├─ prompts/                프롬프트 (저장소 미포함, 직접 작성)
      ├─ templates/, static/     화면
      └─ application*.properties
```
