# RAG Chat — 프로젝트 가이드

사내 문서(규정·지침·매뉴얼 등)를 자연어로 질문하면, **PostgreSQL(pgvector)에 적재한 문서 청크**에서
근거를 찾아 그 근거만으로 LLM 이 답변하는 **멀티턴 RAG 챗봇**이다.

이 문서는 코드를 열기 전에 읽는 지도(map)를 목표로 한다. "어디를 고쳐야 하는가"를 빨리 찾을 수 있도록
구성 요소·흐름·핵심 로직·설정을 정리했다. 화면 쪽 내부 로직은 [UI_GUIDE.md](UI_GUIDE.md)에 있다.

---

## 목차

1. [한눈에 보기](#1-한눈에-보기)
2. [기술 스택](#2-기술-스택)
3. [디렉터리 구조](#3-디렉터리-구조)
4. [문서 적재 흐름](#4-문서-적재-흐름)
5. [질문 1건이 처리되는 흐름](#5-질문-1건이-처리되는-흐름)
6. [핵심 로직: 하이브리드 검색](#6-핵심-로직-하이브리드-검색)
7. [핵심 로직: 인접 청크 확장(모드 2)과 근거 채택](#7-핵심-로직-인접-청크-확장모드-2과-근거-채택)
8. [핵심 로직: 멀티턴 컨텍스트 관리](#8-핵심-로직-멀티턴-컨텍스트-관리)
9. [핵심 로직: 토큰 예산 배분](#9-핵심-로직-토큰-예산-배분)
10. [HTTP API](#10-http-api)
11. [DB 스키마](#11-db-스키마)
12. [설정](#12-설정)
13. [프롬프트](#13-프롬프트)
14. [알려진 한계와 확장 지점](#14-알려진-한계와-확장-지점)

---

## 1. 한눈에 보기

```
┌─────────┐  POST /chat/api/stream (SSE)  ┌──────────────────────────────┐
│ Browser │ ────────────────────────────> │  RAG Chat (Spring Boot)      │
│ main.js │ <───── 토큰 단위 스트리밍 ───── │                              │
└─────────┘                               └──┬──────────┬──────────┬─────┘
                                             │          │          │
                 ┌───────────────────────────┘          │          └───────────────┐
                 ▼                                      ▼                          ▼
   ┌───────────────────────────┐      ┌──────────────────────────┐   ┌──────────────────────┐
   │ PostgreSQL + pgvector     │      │ Text Embeddings Inference│   │ LLM (OpenAI 호환)     │
   │ · 문서/청크 + 벡터 (HNSW)  │      │ · bge-m3 임베딩          │   │ vLLM / Ollama 등      │
   │ · 키워드 (pg_trgm)         │      │ · bge-reranker-v2-m3     │   │ /v1/chat/completions │
   │ · 대화방/메시지/요약        │      └──────────────────────────┘   └──────────────────────┘
   └───────────────────────────┘
```

**한 문장 요약**: 질문을 받아 → (후속 질문이면) 검색용으로 재작성 → 벡터+키워드 검색을 RRF 로 합치고
리랭커로 재정렬 → 문서를 `[CONTEXT]`로 조립 → 이전 대화 + `[CONTEXT]` + 질문을 LLM 에 스트리밍 요청 →
받은 토큰을 그대로 브라우저로 흘리고 → 끝나면 인용된 출처만 남겨 DB 에 저장하고 필요 시 오래된 대화를 요약한다.

외부 의존은 모두 **오픈소스이며 표준 API**로만 연결한다.

| 역할 | 기본 구성 | 교체 가능 대상 |
|---|---|---|
| 벡터·키워드 검색, 이력 저장 | PostgreSQL 16 + pgvector + pg_trgm | pgvector 를 지원하는 PostgreSQL |
| 임베딩 | TEI + `BAAI/bge-m3` (1024차원) | OpenAI 호환 `/v1/embeddings` (vLLM, Ollama 등) |
| 리랭커 | TEI + `BAAI/bge-reranker-v2-m3` | TEI `/rerank` 형식 서버. 끄면 RRF 순위 사용 |
| LLM | vLLM + `openai/gpt-oss-120b` | OpenAI 호환 `/v1/chat/completions` |

---

## 2. 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어/런타임 | Java 21 (Virtual Threads) |
| 프레임워크 | Spring Boot 3.5 — Web, Thymeleaf, Data JPA, JDBC, Actuator |
| DB | PostgreSQL + pgvector. 대화 이력은 JPA, 검색은 `JdbcTemplate` (pgvector 연산자·배열 파라미터 때문) |
| HTTP 클라이언트 | `java.net.http.HttpClient` 래퍼 `HttpUtils` (SSE 스트리밍 포함) |
| 화면 | Thymeleaf + jQuery, Tailwind CSS(사전 빌드), marked + DOMPurify |
| 빌드 | Gradle, WAR 패키징 (내장 Tomcat 실행과 외장 Tomcat 배포 모두 가능) |

---

## 3. 디렉터리 구조

```
rag-chat/
├─ docker-compose.yml            PostgreSQL(pgvector) + 임베딩/리랭커(TEI)
├─ samples/                      예시 문서 (가상의 회사 규정)
├─ scripts/load-samples.sh       예시 문서 적재
└─ src/main
   ├─ java/com/ragchat/
   │  ├─ controller/             채팅 화면·API(SSE), 문서 관리 API, 검색 API, SSO 진입
   │  ├─ service/                ChatService(오케스트레이션), RetrievalService(모드 분기),
   │  │                          DocumentIngestService(적재), QueryRewrite/Summary/Tokenize, History
   │  ├─ retrieval/              검색 엔진 계층 — 청킹, 임베딩/리랭커 클라이언트,
   │  │                          pgvector 저장소, 키워드 추출, RRF, 하이브리드 검색
   │  ├─ prompt/                 시스템 프롬프트 로더 (외부 파일 → 클래스패스 폴백)
   │  ├─ config/                 토큰 예산(ContextBudget), 비동기 실행기, HttpUtils 빈
   │  ├─ dto/                    chat(화면 계약) / document / search / integration(LLM·임베딩·리랭커)
   │  ├─ entity/, repository/    대화방·메시지·요약 (JPA)
   │  └─ util/                   HttpUtils, InputGuard(마크업 차단), AnswerSections(출처 파싱)
   └─ resources/
      ├─ db/schema.sql           전체 스키마 (기동 시 자동 실행, 멱등)
      ├─ prompts/                system / query-rewrite / summary 프롬프트 (저장소 미포함)
      ├─ templates/, static/     화면
      └─ application*.properties 공통 + local / dev / test / prod
```

---

## 4. 문서 적재 흐름

`POST /api/documents` (JSON) 또는 `POST /api/documents/upload` (.txt/.md 파일).

```
DocumentIngestService.ingest(request)
  ├─ 1. TextChunker.split(content)                  본문 → 청크 목록
  ├─ 2. EmbeddingClient.embedAll("문서명\n" + 청크)   배치(기본 8건) 단위로 /v1/embeddings 호출
  │      → 응답 index 로 정렬, 차원(rag.embedding.dimension) 검증
  └─ 3. 한 트랜잭션으로 저장
         rag_document 1행 → document_id 발급
         rag_chunk N행   (document_id, chunk_no=0..N-1, content, embedding)
```

임베딩을 **모두 계산한 뒤** 저장한다. 중간에 실패하면 문서 행도 남지 않는다.
청크 일부만 들어간 문서는 인접 청크 확장(모드 2)을 망가뜨리기 때문이다.

### 청킹 규칙 (`TextChunker`)

| 문서 형태 | 판별 | 자르는 방식 |
|---|---|---|
| **조문형** (규정·지침) | `제N조(제목)` 줄이 있음 | **조문 1개 = 청크 1개**. 앞에 소속 `제N장 제M절`(또는 `부칙`) 경로를 붙인다 |
| Markdown | `#` 제목 줄이 있음 | 제목 단위 |
| 일반 텍스트 | 위 둘 다 없음 | 빈 줄로 나눈 문단을 `chunk-max-chars`(기본 800자)까지 이어 붙임 |

- 조문이 길면 줄 → 문장 → 글자 수 순으로 경계를 찾아 나누고, **이어지는 청크 앞에 `제N조(제목) (계속)`을 다시 붙인다.**
  그래서 **모든 청크의 첫머리에 자기 조문 표기가 있다.** 시스템 프롬프트는 이 성질을 이용해
  "인용하려는 문서의 본문 첫머리 조문 표기를 그대로 적으라"고 지시한다.
- `제5조에 따라 ...`처럼 본문 속 인용은 제목으로 오인하지 않는다. (제목은 `제N조` 단독 또는 `제N조(...)` 형태)
- `부칙`은 조문 번호가 다시 1부터 시작하므로 장(章)처럼 취급해 경로를 새로 잡는다.

### 청크 식별자

`{document_id}_{chunk_no 4자리}` — 예) `12_0003`.
화면의 문서번호로 노출되고, 앞부분(`12`)은 원문 바로가기 URL(`rag.ui.source-view-url`)에 붙는다.

---

## 5. 질문 1건이 처리되는 흐름

`POST /chat/api/stream` 하나를 따라가면 시스템 전체가 보인다.

```
[브라우저] main.js: fetch('/chat/api/stream', {question, chatSeq, retrievalMode})
   │
   ▼
[ChatApiController.streamQuestion]                                   ── 동기 구간
   ├─ 1. currentUserSeq(session)       세션의 사용자 ID → 사용자번호 (SSO 미사용 시 dev-user-seq)
   ├─ 2. 입력 검사 (InputGuard: HTML 태그·스크립트 스킴 차단)
   ├─ 3. chatSeq 없으면 새 대화방 생성, 있으면 소유자 검증
   ├─ 4. conversationContextService.buildMemory(chatSeq)   요약 + 최근 원문 턴
   ├─ 5. historyService.saveUserMessageAsync()             ★ 4번 이후 저장 (이력 중복 방지)
   ├─ 6. new SseEmitter(rag.sse.timeout-ms)
   ├─ 7. chatService.streamAnswer(...)                     여기서 Virtual Thread 로 분기
   └─ 8. emitter.send("chatInfo", chatSeq) → return emitter
                                                                       ── 비동기 구간
[ChatService.streamAnswer]
   ├─ Step 1. queryRewriteService.rewriteForSearch()   후속 질문 → 완결 질의 (첫 턴은 원문)
   ├─ Step 2. retrievalService.retrieve(searchQuery, mode)
   │            하이브리드 검색 + (모드 2) 인접 청크 확장. 0건이면 completeWithError
   ├─ Step 3. 토큰 예산 배분 → 부족하면 오래된 턴부터 제거
   ├─ Step 4. buildContext() → [CONTEXT] 블록 (문서마다 [문서N] 번호)
   ├─ Step 5. 출처 후보 목록 (인덱스+1 = [문서N])
   ├─ Step 6. LlmChatRequest 조립 → httpUtils.postStream() (stream: true)
   │            messages = [system 1개] + [이전 턴들] + [CONTEXT + 질문]
   ├─ Step 7. "data:" 줄 파싱 → delta.content 를 즉시 emitter.send + 누적
   │            include_usage 로 답변 토큰 수 확보. 클라이언트가 끊기면 읽기 중단
   └─ Step 8. 완료 후
              ├─ [document1] 같은 비표준 라벨을 [문서N] 으로 정규화
              ├─ 답변의 [출처]에서 [문서N] 파싱 → 채택 출처만 남김
              ├─ emitter.send("used_sources", 채택 출처)
              ├─ historyService.saveAssistantMessageAsync()   답변 + 출처 JSON + 토큰 수
              ├─ summaryService.maybeSummarizeAsync()        필요 시 오래된 대화 요약
              └─ emitter.complete()
```

---

## 6. 핵심 로직: 하이브리드 검색

`HybridSearchService.search(query)`

```
질의 ─┬─ EmbeddingClient.embed ─→ ChunkRepository.searchByVector   (코사인, top 20)  ─┐
      └─ KeywordQuery.likePatterns ─→ ChunkRepository.searchByKeywords (ILIKE, top 20) ─┴─→ RankFusion.fuse (RRF, k=60)
                                                                                              │
                                    상위 rerank-candidates(20)건 ─→ RerankClient.rerank ─→ top-n(5)건
```

### 벡터 검색

```sql
SELECT ..., 1 - (c.embedding <=> CAST(? AS vector)) AS score
  FROM rag_chunk c JOIN rag_document d ON d.document_id = c.document_id
 ORDER BY c.embedding <=> CAST(? AS vector)
 LIMIT ?
```

HNSW 인덱스(`vector_cosine_ops`)를 탄다. 벡터는 `[0.1,0.2,...]` 문자열 리터럴로 넘긴다.
임베딩 입력에는 **문서명 + 청크 본문**을 넣는다. 청크만으로는 어느 문서의 내용인지 알 수 없는 경우가 많다.

### 키워드 검색 — 형태소 분석기 없는 경량 방식

`KeywordQuery`가 질의를 어절로 나누고 흔한 조사·어미를 떼어 키워드를 만든다.

```
"연차휴가는 어떻게 산정되나요?"  →  ["연차휴가", "산정"]   →  ["%연차휴가%", "%산정%"]
```

- 떼고 나서 두 글자 이상 남을 때만 뗀다 (`기한`의 `한` 같은 오탐 방지)
- `어떻게`, `무엇`, `관련` 등 변별력 없는 표현은 버린다. 최대 8개
- 점수 = 본문에 포함된 키워드 수 + 문서명에 포함된 키워드 수 × 2
- `pg_trgm` GIN 인덱스가 `ILIKE '%...%'`를 가속한다

의미 검색은 벡터가 담당하므로, 키워드 검색의 목표는 **"정확한 용어가 들어간 청크를 놓치지 않는 것"** 이다.
정밀한 형태소 분석이 필요하면 `KeywordQuery`만 교체하면 된다.

### RRF (Reciprocal Rank Fusion)

벡터 유사도와 키워드 점수는 척도가 달라 더할 수 없다. 점수 대신 **순위**만 쓴다.

```
score(d) = Σ  1 / (k + rank_i(d))      (k = rag.search.rrf-k, 기본 60)
```

두 목록에 모두 나온 청크가 위로 올라온다. 동점이면 먼저 등장한 순서를 유지한다.

### 리랭킹

Cross-encoder(`bge-reranker-v2-m3`)가 질의와 (문서명 + 본문) 쌍의 관련도를 직접 매긴다.
벡터 검색보다 느리지만 정확하므로, RRF 상위 후보 20건에만 적용한다.

| 상황 | 동작 |
|---|---|
| `rag.reranker.enabled=false` | RRF 상위 `top-n`건 사용 |
| 리랭커 호출 실패 | 경고 로그 후 RRF 상위 `top-n`건으로 대체 |
| 키워드 검색 실패 | 경고 로그 후 벡터 결과만으로 계속 |
| 임베딩 실패 | 검색 불가 → 예외 (채팅은 오류로 종료) |

---

## 7. 핵심 로직: 인접 청크 확장(모드 2)과 근거 채택

`RetrievalService.retrieve(question, mode)` — 요청의 `retrievalMode`, 없으면 `rag.retrieval.mode`(기본 2).

| 모드 | 동작 |
|---|---|
| 1 `MODE_HYBRID` | 하이브리드 검색 결과만 근거 후보로 사용 |
| 2 `MODE_CHUNK_EXPANSION` | 검색 결과마다 **앞/뒤 청크(chunk_no ± 1)** 를 함께 제공 |

모드 2는 조문이 청크 경계에서 잘려 단서·예외·각 호가 누락되는 문제를 막는다.

```
검색 결과마다 ChunkRepository.findRange(document_id, no-1, no+1) 로 세 청크를 한 번에 조회
배치:  앞 청크 → 검색 결과 → 뒤 청크   (문맥이 이어지도록)
중복:  청크 ID 기준 제거. 인접 청크가 다른 검색 결과와 겹치면 "검색 결과"로만 담는다
```

> **청크를 병합하지 않는 이유.** 앞/뒤 청크를 하나의 지문으로 이어 붙이면 세 청크가 검색 청크의
> 메타데이터 하나를 공유하게 되어 LLM 이 앞/뒤 청크의 조문을 검색 청크의 조문으로 잘못 귀속시킨다.
> 그래서 청크마다 자기 메타데이터를 달고 **독립 문서**로 내보내며, `[구분: 검색결과 / 인접구간]` 라벨로 구별한다.

`RetrievalItem`

| 필드 | 용도 |
|---|---|
| `chunk` | 본문과 메타데이터. **화면과 LLM 이 같은 것을 본다** |
| `origin` | `HIT`(검색 결과) / `PREV`(직전 청크) / `NEXT`(직후 청크) |
| `anchorChunkId` | 인접 청크일 때 기준이 된 검색 결과의 청크 ID |

### 근거 채택과 화면 노출

검색 결과와 인접 청크는 **동등한 근거 후보**이며, 화면과 DB 에는 **답변이 실제로 인용한 문서만** 남는다.

```
[CONTEXT] 조립 시 각 문서에 [문서N] 번호 부여   (번호 = 출처 후보 인덱스 + 1, 절대 건너뛰지 않는다)
        ↓
LLM 답변의 [출처] 영역에서 '- [문서N] ...' 파싱  → markUsedSources()
        ↓
isUsed=true 인 것만 used_sources 이벤트로 전송 + DB search_content 에 저장
```

- `[출처]` 영역이 없으면(근거 없음 등 고정 응답) 채택 0건 → 화면에 안내 문구
- `[문서N]` 표기가 없으면 조문 표기(`제N조(제목)`) 매칭으로 폴백
- `rag.retrieval.source-display-limit`(기본 0 = 무제한)로 표시 상한 설정

### [CONTEXT] 문서 라벨

```
[문서3][구분: 검색결과][문서명: 휴가 규정][문서번호: 1_0004][버전: 3][시행일자: 2025-01-01]
제2장 연차유급휴가
제5조(미사용 연차의 처리) ① 회사는 ...
```

| 라벨 | 의미 |
|---|---|
| `문서번호` | 청크 식별자. 사람이 읽는 조문 번호가 아니다 |
| `버전` | 문서의 개정 차수. ⚠ **조문번호가 아니다** |
| `시행일자` | 문서 시행일 |

조문 번호는 라벨에 넣지 않는다. 넣으면 LLM 이 `버전`을 조문번호로 오인해 존재하지 않는 인용을 만든다.

---

## 8. 핵심 로직: 멀티턴 컨텍스트 관리

관련 클래스: `ConversationContextService`, `SummaryService`, `QueryRewriteService`.

### 8.1 LLM 에 전달되는 messages

```
[0] system    : <system-prompt.md>
                + "[이전 대화 요약] ... 맥락 파악에만 사용하고 [출처]로 인용하지 마라. <요약문>"
                ※ system 메시지는 항상 정확히 1개
[1] user      : "연차휴가는 어떻게 산정되나요?"        ← DB 에 저장된 질문 원문
[2] assistant : "...답변..."                          ← [출처]/[연관질문]은 제거하고 싣는다
[3] user      : "[CONTEXT][문서1]...[문서N]... [USER_QUESTION]그럼 미사용분은요?"
```

**원칙**

1. **검색 결과는 현재 턴에만 붙는다.** DB 에는 질문 원문만 저장하므로 `[CONTEXT]`가 턴마다 누적되지 않는다.
2. **system 메시지는 1개다.** 요약을 별도 system 메시지로 보내면 chat template 에 따라 무시되거나 오류가 난다.
3. **이력은 항상 user 메시지로 시작한다.** 턴 단위로 잘라내기 때문이다.
4. 이전 대화가 하나도 없으면 "그건요?" 같은 후속형 질문에 되묻도록 지시를 덧붙인다 (`NO_HISTORY_GUIDE`).

### 8.2 요약 + 원문 윈도잉 (`ConversationContextService.buildMemory`)

```
demo_chat_message 전체
├────────── 이미 요약된 구간 ──────────┬──────── 미요약 구간 ────────────────┤
│  demo_chat_summary 최신 1건으로 대체  │  턴 단위로 최신부터 예산만큼 원문 유지 │
│  (to_message_seq 까지)               │  초과분은 이번 턴 프롬프트에서 제외    │
```

- **원문 예산** = `min(history.retained.target-tokens, history.memory.max-tokens − 요약 토큰)`
  — 요약이 커지면 원문 자리를 그만큼 줄여 "요약 + 원문" 합계 상한을 지킨다.
- `windowTurnsByBudget()` — 최신 턴부터 담고 예산을 넘으면 오래된 턴을 자른다. 최소 1턴은 유지한다.

### 8.3 오래된 대화 요약 (`SummaryService.maybeSummarizeAsync`)

답변이 끝난 뒤 **비동기로** 실행되어 응답 지연이 없다.

```
미요약 원문 합계 > history.summarize.trigger-tokens (16000) ?
   └─ Yes → retained 예산(8000) 밖의 오래된 턴들을 대상으로
            ├─ [기존 요약] + [추가 대화] 를 summary-prompt.md 로 압축 (목표 2500토큰)
            ├─ 결과가 summary.max-tokens(4000) 를 넘으면 한 번 더 재압축 (2000토큰)
            └─ demo_chat_summary 에 version+1 로 저장 (to_message_seq = 요약한 마지막 메시지)
```

경계는 `buildMemory`와 같은 **턴 단위**로 잡아 한 턴이 요약과 원문으로 쪼개지지 않게 한다.

### 8.4 후속 질문 재작성 (`QueryRewriteService.rewriteForSearch`)

검색은 이전 대화를 모른다. "그럼 예외는?"을 그대로 검색하면 엉뚱한 문서가 온다.

```
첫 턴 → 호출 안 함 (추가 지연 0)
그 외 →
  1. 최근 3턴을 "사용자: / 상담원:" 형태로 조립 (이전 답변은 [출처] 앞까지, 400자 컷)
  2. query-rewrite-prompt.md 로 LLM 비스트리밍 호출 (temperature 0, reasoning_effort low)
  3. sanitize(): 첫 줄만 / 라벨·대괄호 포함 시 폐기 / 200자 초과 폐기
  4. 실패·폐기 → 질문 원문으로 폴백
```

재작성문은 **검색에만** 쓴다. LLM 에 전달하는 `[USER_QUESTION]`은 사용자 원문 그대로다.

---

## 9. 핵심 로직: 토큰 예산 배분

모델 컨텍스트 한도를 넘지 않도록 `ChatService`에서 강제한다. (`ContextBudget`)

```
inputBudget  =  server.context.max-tokens (131072)
              − server.context.reserve-tokens (4096)         chat template·특수 토큰 여유
              − request.generation.medium.max-tokens (16384)  답변 생성분
              − input.safety-margin-tokens (2000)             계산 오차 여유

contextCap   =  inputBudget − system 메시지 − 이전 턴 − 질문 − 8 × 메시지 수

while (contextCap < context.rag.min-tokens(8000) && 턴이 남아 있음)
    dropOldestTurn()        가장 오래된 user + 딸린 assistant 를 함께 제거

buildContext(items, contextCap)   상위 랭크부터 담고 초과 직전에 중단. 최소 1건 보장
```

우선순위: `system 프롬프트 + 현재 질문` > `[CONTEXT]` > `이전 대화`. RAG 답변에서는 근거가 최우선이다.

| 토큰 계산 | 방식 | 쓰는 곳 |
|---|---|---|
| `TokenizeService.estimateTokens()` | 로컬 근사 (한글·CJK 1자=1토큰, 그 외 4자=1토큰). 약간 과대 추정 | 프롬프트 예산 검사 (요청마다 수십 회) |
| `TokenizeService.countTokensSafe()` | vLLM `/tokenize` 호출. **실패 시 0 이 아니라 추정치** 반환 | DB 저장용 토큰 수 |
| `include_usage` | 스트리밍 마지막 청크의 `usage.completion_tokens` | 답변 토큰 수 (우선 사용) |

`/tokenize`가 없는 서버(Ollama 등)에서도 추정치로 동작한다.

---

## 10. HTTP API

### 채팅 (`ChatApiController`, `/chat/api`)

| 매핑 | 설명 |
|---|---|
| `POST /stream` | **메인 엔드포인트.** SSE 로 답변 스트리밍 |
| `POST /history` | 대화방의 전체 메시지 (화면 복원용). 소유자 검증 |
| `POST /left_chat_history` | 좌측 대화 목록 (Slice 페이징) |
| `POST /left_archiveyn_history` | 아카이브 목록 |
| `POST /chat_action` | 대화방 아카이브·해제·이름 변경·삭제. 소유자 검증 |

SSE 이벤트

| 이벤트 | 시점 | 데이터 |
|---|---|---|
| `chatInfo` | 스트리밍 시작 직후 | 대화방 번호 |
| (이름 없음) | 스트리밍 중 | `{"content": "..."}` |
| `used_sources` | 답변 완료 후 | 채택 출처 배열 `{id, docNo, title, content, version, effectiveDate, origin, anchorId, isUsed}` |

### 문서 관리 (`DocumentApiController`, `/api/documents`) — `rag.ingest.enabled=true` 일 때만

| 매핑 | 설명 |
|---|---|
| `POST /api/documents` | JSON `{title, version, effectiveDate, content}` 적재 |
| `POST /api/documents/upload` | multipart `file`(.txt/.md, UTF-8) + 선택 `title`/`version`/`effectiveDate` |
| `GET /api/documents` | 문서 목록 (청크 수 포함) |
| `DELETE /api/documents/{id}` | 문서와 청크 삭제 |

> 관리용 API 에는 별도 인증이 없다. 운영에서는 `rag.ingest.enabled=false`(prod 기본)로 두거나
> 게이트웨이·Spring Security 로 관리자만 접근하게 막아야 한다.

### 검색 확인 (`SearchApiController`)

| 매핑 | 설명 |
|---|---|
| `POST /api/search` | `{query, mode}` → 근거 후보 목록. **LLM 을 호출하지 않고** 검색 품질만 확인 |

### 화면 / 인증

| 매핑 | 설명 |
|---|---|
| `GET /`, `GET /chat/main[/{chatSeq}]` | 채팅 화면. `sso.required=true`인데 미인증이면 HTML 자체를 내려주지 않고 `/sso/fail`로 보낸다 |
| `GET /sso?ssoToken=...` | 외부 포털 진입점. 토큰을 **세션에** 저장 후 302 (URL·모델에 토큰을 남기지 않는다). 사용자가 바뀌면 세션을 새로 만든다(세션 고정 방어) |
| `GET/POST /dev/login` | dev 프로파일 전용 개발 로그인 |

실제 SSO 솔루션 연동은 `SsoController.resolveUserId()` **한 곳만** 바꾸면 된다.

---

## 11. DB 스키마

[`src/main/resources/db/schema.sql`](../src/main/resources/db/schema.sql) — `spring.sql.init.mode=always`면 기동 시 실행된다(모든 구문 멱등).
JPA 는 `ddl-auto=validate`로 검증만 한다.

```
rag_document (원본 문서)
  document_id BIGSERIAL PK, title, version, effective_date, source_name, created_at

rag_chunk (청크)                                   N : 1 → rag_document (ON DELETE CASCADE)
  chunk_seq BIGSERIAL PK, document_id, chunk_no, content, embedding vector(1024)
  UNIQUE (document_id, chunk_no)
  INDEX hnsw (embedding vector_cosine_ops)           벡터 검색
  INDEX gin  (content gin_trgm_ops), (title ...)     키워드 검색

demo_chat_list (대화방)       chat_seq, user_seq, title, archive_yn, reg_date, mod_date
demo_chat_message (메시지)    message_seq, chat_seq FK, role, content, search_content(출처 JSON), token_count
demo_chat_summary (누적 요약) summary_seq, chat_seq, summary_version, from/to_message_seq, summary_content
```

> 임베딩 모델을 바꿀 때는 `vector(1024)`와 `rag.embedding.dimension`을 함께 바꾸고 문서를 다시 적재한다.
> (차원이 다르면 `EmbeddingClient`가 적재 단계에서 바로 실패시킨다)

---

## 12. 설정

### 프로파일

| 프로파일 | 용도 | 특징 |
|---|---|---|
| `local` (기본) | 개인 PC | SSO 생략, 검색 방식 전환 UI 노출, DB 비밀번호 기본값 `postgres`(docker-compose 와 동일) |
| `dev` | 개발 서버 | SSO + 개발 전용 로그인 |
| `test` | QA 서버 | 검색 방식 전환 UI 노출 |
| `prod` | 운영 | SQL 로그·에러 상세 비노출, 문서 관리 API 닫힘, 스키마 자동 실행 안 함 |

비밀값·접속 정보는 모두 환경 변수(`${ENV:기본값}`)로 주입한다. 저장소에는 실제 값을 두지 않는다.

### 주요 키 (`application.properties`)

| 키 | 기본값 | 설명 |
|---|---|---|
| `rag.embedding.url` / `.model` | `localhost:8081/v1/embeddings` / `BAAI/bge-m3` | 임베딩 서버 |
| `rag.embedding.dimension` | 1024 | 스키마 `vector(N)`과 같아야 함 |
| `rag.embedding.batch-size` | 8 | 요청당 문장 수 |
| `rag.reranker.enabled` / `.url` | `true` / `localhost:8082/rerank` | 리랭커 |
| `rag.search.vector-top-k` / `keyword-top-k` | 20 / 20 | 검색기별 후보 수 |
| `rag.search.rrf-k` | 60 | RRF 상수 |
| `rag.search.rerank-candidates` | 20 | 리랭커에 넘길 후보 수 |
| `rag.search.top-n` | 5 | 최종 근거 수 (모드 2 는 앞/뒤 청크가 더해짐) |
| `rag.ingest.enabled` | `true` (prod `false`) | 문서 관리 API |
| `rag.ingest.chunk-max-chars` | 800 | 청크 최대 글자 수 |
| `rag.ui.assistant-name` / `model-label` / `source-view-url` | | 화면 표시 |

### 프로파일별 키

| 키 | 설명 |
|---|---|
| `rag.llm.url` / `.model` / `.api-key` / `.tokenize-url` | LLM 서버 (`LLM_URL`, `LLM_MODEL`, `LLM_API_KEY`) |
| `rag.llm.include-usage` | 스트리밍 `usage` 요청. 서버가 `stream_options`를 거부하면 `false` |
| `rag.retrieval.mode` / `.mode-selector.visible` | 검색 방식 기본값 / 화면 전환 UI 노출 |
| `rag.query-rewrite.*` | 질의 재작성 사용 여부·토큰·추론 강도 |
| `rag.prompt.system-path` | 외부 시스템 프롬프트 파일 (비우면 클래스패스) |
| `rag.sse.timeout-ms` | SSE 절대 제한 시간 (기본 180초) |
| `server.context.*`, `history.*`, `summary.*`, `context.rag.min-tokens` | [9장](#9-핵심-로직-토큰-예산-배분) 토큰 예산 |
| `sso.required` / `sso.portal-url` / `sso.dev-user-seq` | 인증 |

---

## 13. 프롬프트

> 프롬프트 파일은 저장소에 포함하지 않는다(`.gitignore`). 아래는 각 파일의 역할과 코드와의 계약이며,
> 실행하려면 README 의 "프롬프트 파일 준비"를 참고해 직접 작성한다.

| 파일 | 용도 |
|---|---|
| `system-prompt.md` | 답변 생성. 입력 구조 설명 → 질문 복원 → **유형 판정**(근거 없음 / 불명확 / 범위 밖 / 정상) → 작성 규칙 → `[출처]`·`[연관질문]` 형식 |
| `query-rewrite-prompt.md` | 후속 질문 → 완결된 검색 질의 한 문장 |
| `summary-prompt.md` | 기존 요약 + 추가 대화 → 500자 이내 통합 요약 |

`[출처]` 형식 `- [문서N] 문서명, 제N장 제M절 제K조(조제목)`은 `ChatService`의 채택 파싱과 맞물려 있으므로
바꿀 때는 `markUsedSources()`와 `AnswerSections`를 함께 확인한다.

`SystemPromptProvider`는 `rag.prompt.system-path` 외부 파일을 먼저 읽고(변경 시 재시작 없이 반영),
없거나 실패하면 클래스패스 기본본을 쓴다.

---

## 14. 알려진 한계와 확장 지점

### 한계

- **키워드 검색은 형태소 분석 없이 조사만 떼는 방식**이다. 복합어 분해나 동의어는 처리하지 않는다.
  (벡터 검색과 리랭커가 의미를 보완한다)
- **문서 관리 API 에 인증이 없다.** 운영에서는 끄거나 앞단에서 막는다.
- **적재는 동기 처리**다. 큰 문서를 많이 올리면 요청이 길어진다. 대량 적재는 배치 작업으로 분리하는 것이 좋다.
- **비동기 메시지 저장 실패는 조용히 이력 구멍을 만든다.** `persistMessage()`가 예외를 로그로만 남긴다.
- **검색 결과가 0건이면 SSE 가 오류로 끝난다.** 프론트에는 일반 오류로 보인다.

### 확장 지점

| 하고 싶은 일 | 고칠 곳 |
|---|---|
| 답변 품질·형식 튜닝 | `prompts/system-prompt.md` (외부 경로 지정 시 무재시작 반영) |
| 형태소 분석 기반 키워드 검색 | `KeywordQuery` 교체, 또는 PostgreSQL 텍스트 검색 구성으로 `searchByKeywords` 변경 |
| 다른 임베딩 모델 | `rag.embedding.*` + `schema.sql`의 `vector(N)` → 재적재 |
| 리랭커 교체 | `RerankClient` (요청/응답 형식만 맞추면 됨) |
| PDF·DOCX 적재 | `DocumentApiController.upload`에서 텍스트 추출 후 `DocumentIngestService.ingest` 호출 |
| 실제 SSO 연동 | `SsoController.resolveUserId()` |
| 검색 건수 조정 | `rag.search.*` |
| 이력·요약 분량 조정 | `history.*`, `summary.*` |
