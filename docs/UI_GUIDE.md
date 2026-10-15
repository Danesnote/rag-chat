# RAG Chat — 프론트엔드(UI) 내부 로직 가이드

[PROJECT_GUIDE.md](PROJECT_GUIDE.md)가 서버를 다룬다면, 이 문서는 **브라우저에서 돌아가는 코드**를 다룬다.
jQuery 기반의 **단일 페이지 애플리케이션(SPA)** 이며, 프레임워크(React/Vue)나 빌드 도구 없이
`<script>` 태그로 로드되는 순수 JS다.

| 파일 | 줄 수 | 역할 |
|---|---|---|
| `templates/views/main/chat.html` | 347 | 화면 골격(정적 DOM) + 서버 모델 주입 |
| `static/js/main.js` | 2,416 | **애플리케이션 로직 전체** |
| `static/js/common/app-util.js` | 426 | 공통 유틸 (알림·통신·XSS 방어·마크다운) |
| `static/css/tailwind.css` | (빌드 산출물) | Tailwind 유틸리티 |
| `static/css/chat_default.css` | - | 커스텀 스타일(스크롤바, 커서 깜빡임, 발췌 페이드 등) |

---

## 목차

1. [로딩 순서와 방어 코드](#1-로딩-순서와-방어-코드)
2. [화면 구조 (4개 뷰)](#2-화면-구조-4개-뷰)
3. [전역 상태 변수](#3-전역-상태-변수)
4. [초기화 흐름 `init()`](#4-초기화-흐름-init)
5. [SPA 라우팅](#5-spa-라우팅)
6. [답변 스트리밍 `streamAnswer()`](#6-답변-스트리밍-streamanswer)
7. [대화 블록 DOM 구조와 3단 로딩 상태](#7-대화-블록-dom-구조와-3단-로딩-상태)
8. [출처 렌더링](#8-출처-렌더링)
9. [과거 대화 복원 `loadChatHistory()`](#9-과거-대화-복원-loadchathistory)
10. [좌측 목록 페이징](#10-좌측-목록-페이징)
11. [사이드바 · 검색 뷰 · 컨텍스트 메뉴](#11-사이드바--검색-뷰--컨텍스트-메뉴)
12. [우측 상세 패널](#12-우측-상세-패널)
13. [XSS 방어 규약 (필수 준수)](#13-xss-방어-규약-필수-준수)
14. [`app-util.js` 공통 유틸](#14-app-utiljs-공통-유틸)
15. [localStorage 키](#15-localstorage-키)
16. [UI 알려진 이슈](#16-ui-알려진-이슈)

---

## 1. 로딩 순서와 방어 코드

`chat.html`의 스크립트 로딩 순서가 중요하다. `main.js`는 jQuery·marked·DOMPurify·SweetAlert2·lucide가
**이미 로드된 상태**를 전제한다.

```
jquery-3.7.1.min.js  →  lucide.min.js  →  marked.min.js  →  purify.min.js
  →  sweetalert2.min.js  →  common/app-util.js  →  main.js
```

모든 라이브러리는 **CDN이 아닌 로컬 파일**(`static/js/vendor/`)이다. 폐쇄망 전제.

### 라이브러리 누락 시 방어 (3단)

| 대상 | 방어 위치 | 동작 |
|---|---|---|
| lucide | `main.js` 최상단 | `window.lucide = { createIcons: function(){} }` no-op 대체 → **아이콘만 안 보이고 나머지는 정상** |
| marked | `appUtil.renderMarkdown` | 원문을 이스케이프한 평문 + `<br>`로 대체 |
| DOMPurify | `appUtil.renderMarkdown` | `console.error` 후 **평문 처리.** 절대 원본 HTML을 그대로 흘리지 않는다 |
| localStorage | `appUtil.storageGet/Set` | `try/catch`. 시크릿 모드·저장소 차단 환경에서 초기화가 멈추지 않게 |

---

## 2. 화면 구조 (4개 뷰)

`#main-scroll-area` 안에서 **3개 뷰를 show/hide로 교체**하고, `#right-side-panel`이 오버레이로 붙는다.

```
┌─ #sidebar (250px ⇄ 72px) ──┬─ #main-scroll-area ───────────────┬─ #right-side-panel ─┐
│  #logo-section             │                                   │  (hidden 기본)      │
│  #primary-actions          │  ┌ #view-initial ────────────┐    │  #panel-resizer     │
│   ├ 새 대화 (.btn-home)     │  │ 중앙 입력창                │    │  #panel-title       │
│   └ #btn-sidebar-search    │  │ #main-search-input        │    │  #panel-meta        │
│                            │  │ #recommended-list (추천)   │    │  #panel-content     │
│  #sidebar-lists            │  └───────────────────────────┘    │   ← 출처 전문 표시    │
│   #sidebar-expanded-content│  ┌ #view-search ─────────────┐    │                     │
│    ├ #counsel-list  (대화)  │  │ 대화/아카이브 탭            │    │  300~600px 리사이즈  │
│    └ #archive-list (아카이브)│  │ #search-page-input        │    │                     │
│   #sidebar-collapsed-      │  │ #search-results-list      │    │                     │
│     content (아이콘만)      │  └───────────────────────────┘    │                     │
│                            │  ┌ #view-area ───────────────┐    │                     │
│  #retrieval-mode-select    │  │ 대화 블록 N개 (동적 생성)    │    │                     │
│    (dev 전용)               │  │  #view-result_{seq} ...   │    │                     │
│  #theme-toggle             │  └───────────────────────────┘    │                     │
└────────────────────────────┤  #scroll-indicator (맨아래 버튼)   │                     │
                             │  #bottom-input-area               │                     │
                             │   ├ #bottom-search-input          │                     │
                             │   └ #bottom-recommended-popup     │                     │
                             └───────────────────────────────────┴─────────────────────┘
```

### 뷰 전환 규칙

| 상황 | `#view-initial` | `#view-search` | `#view-area` | `#bottom-input-area` |
|---|---|---|---|---|
| 새 대화 (`showNewChatView`) | **show** | hide | empty | hidden |
| 대화 열기 (`openChatHistory`) | hide | hide | **채움** | show |
| 검색 뷰 (`#btn-sidebar-search`) | hide | **show** | hide | hidden |
| 질문 전송 (`streamAnswer`) | hide | hide | **append** | show |

### 서버 → 화면 주입값 (`chat.html`)

| Thymeleaf | 용도 |
|---|---|
| `${chatSeq}` | 전역 `chatSeq` 변수 초기값. URL로 특정 대화 진입 시 사용 |
| `${retrievalSelectorVisible}` | `#retrieval-mode-select` 렌더 여부 (dev만 true) |
| `${defaultRetrievalMode}` | `#retrieval-mode-default` hidden 값. **prod에서 select가 없을 때의 폴백** |

---

## 3. 전역 상태 변수

`main.js` 상단에 선언된 전역 상태. **프레임워크가 없으므로 이 변수들이 사실상 앱의 스토어다.**

### 대화 목록 (counsel) — 아카이브(archive)도 동일 구조로 대칭 존재

| 변수 | 초기값 | 의미 |
|---|---|---|
| `counselQuestions` | `[]` | 서버에서 받은 대화방 목록 |
| `counselDisplayLimit` | `3` | **화면에 보여줄 개수.** 더보기로 +10씩 증가 |
| `counselNextPage` | `0` | 다음에 요청할 서버 페이지 번호 |
| `counselHasNext` | `false` | 서버에 더 있는지 |
| `isLoadingCounselPage` | `false` | 중복 요청 방지 |
| `counselRequestVersion` | `0` | **경쟁 조건 방지 토큰** (아래 10장 참조) |
| `isAnimatingCounselLoad` | `false` | 더보기 애니메이션 중 플래그 |
| `CHAT_LIST_PAGE_SIZE` | `10` | 서버 요청 크기 = 더보기 증가폭 |

### 스트리밍

| 변수 | 의미 |
|---|---|
| `isGenerating` | 답변 생성 중. **전송/중지 버튼 아이콘 토글의 기준** |
| `abortController` | `AbortController`. 중지 버튼이 실제로 fetch를 끊는 수단 |
| `activeStreamSeq` | 현재 스트리밍 중인 블록의 seq. **중단 시 그 블록의 로딩 UI만 정리하기 위해 필요** |

### 라우팅 / 레이아웃

| 변수 | 의미 |
|---|---|
| `chatSeq` | 서버 주입 초기값에서 시작. 현재 보고 있는 대화방 |
| `currentChatSeq` | `chatSeq`의 런타임 사본. **스트리밍·비동기 콜백에서 사용** |
| `isSidebarOpen` | 사이드바 펼침 여부 |
| `isSidebarAutoCollapsed` | **창 폭이 좁아 자동으로 접힌 것인지** (사용자 조작과 구분) |
| `isCounselOpen` / `isArchiveOpen` | 좌측 섹션 접힘 |
| `currentSearchTab` | 검색 뷰의 현재 탭 (`counsel` / `archive`) |

> `chatSeq`와 `currentChatSeq`가 둘 다 있는 이유: `chatSeq`는 화면 상태,
> `currentChatSeq`는 비동기 응답이 도착했을 때 "아직 이 방을 보고 있는가"를 판정하는 기준이다.
> `loadChatHistory`의 콜백은 `currentChatSeq !== requestedChatSeq`면 응답을 버린다.

---

## 4. 초기화 흐름 `init()`

`jQuery(window).ready()` 안에서 **모든 이벤트 핸들러를 먼저 등록**하고, 마지막에 `init()`을 호출한다.

```
init()
├─ appUtil.initSanitizerHooks()   DOMPurify 훅 등록 (링크 tabnabbing 방어) ★ 렌더 전에 필수
├─ lucide.createIcons()           정적 DOM의 아이콘 그리기
├─ getLeftChatHistory()           좌측 대화 목록 page 0 요청
├─ getLeftArchiveHistory()        좌측 아카이브 목록 page 0 요청
├─ renderRecommended()            추천 질문 5개를 중앙/하단 팝업에 렌더
├─ 테마 텍스트 동기화              #theme-text 를 현재 html.dark 상태에 맞춤
├─ chatSeq > 0 이면 loadChatHistory(chatSeq)   URL로 특정 대화 진입
└─ history.replaceState()         뒤로가기 기준점 확보
```

> **주의**: `initSanitizerHooks()`는 `renderMarkdown()`이 처음 호출되기 전에 실행돼야 한다.
> DOMPurify 훅은 한 번만 등록되며(`appUtil._sanitizerHookInstalled` 가드), 훅 등록 전에 렌더된
> 마크다운의 링크에는 `rel="noopener noreferrer"`가 붙지 않는다.

### 이벤트 위임 패턴

동적으로 생성되는 요소(대화 블록, 출처 카드, 목록 아이템)는 반드시 **`$(document).on()` 위임**으로
바인딩한다. 정적 요소만 `$('#id').on()` 직접 바인딩.

| 위임 셀렉터 | 동작 |
|---|---|
| `.search-recommend-trigger` | 추천 질문 클릭 → `streamAnswer()`. `isGenerating`이면 무시(중복 방지) |
| `.search-trigger` | 대화 목록 클릭 → `openChatHistory()` |
| `.chat-tab-btn` | 답변/검색결과 탭 전환 |
| `.action-archive` / `.action-unarchive` / `.action-rename` / `.action-delete` | 대화방 액션 → `/chat/api/chat_action` |
| `.menu-btn` | 컨텍스트 메뉴 열기 |
| `.btn-copy-answer` | 답변 복사 |
| `.source-card-box` / `.search-result-box` | 우측 패널 열기 |
| `.btn-home` | 새 대화 |

---

## 5. SPA 라우팅

**서버 라우팅 없이 `history.pushState`로만 URL을 바꾼다.** 새로고침하면 서버가
`/chat/main/{chatSeq}`를 렌더하고, `${chatSeq}`가 주입되어 같은 화면이 복원된다.

| 함수 | 설명 |
|---|---|
| `updateBrowserHistory(url, chatId, historyAction)` | `historyAction`이 `'none'`이면 아무것도 안 함(popstate 처리용), `'replace'`면 `replaceState`, 그 외(`'push'`)는 **현재 경로와 다를 때만** `pushState` |
| `getChatSeqFromPath()` | `/^\/chat\/main\/(\d+)\/?$/` 정규식으로 URL에서 방 번호 추출. 매칭 안 되면 `null` |
| `clearChatContent()` | `#view-area` 비우기 + 흘러나온 `.chat-turn-block` 제거 |
| `showNewChatView(historyAction='push')` | 생성 중이면 `stopGenerating()` → 상태 초기화 → `/chat/main`으로 URL 변경 → 초기 뷰 표시 |
| `openChatHistory(id, historyAction='push')` | 방 번호 검증 → 생성 중이면 중단 → URL 변경 → **이미 로드된 방이면 재요청 생략** (`isAlreadyLoaded`) |

### `popstate` 처리

```js
$(window).on('popstate', function() {
    const pathChatSeq = getChatSeqFromPath();
    if (pathChatSeq !== null) openChatHistory(pathChatSeq, 'none');
    else                      showNewChatView('none');
});
```

**`'none'`을 넘기는 이유**: 뒤로가기로 이미 URL이 바뀐 상태이므로, 여기서 다시 `pushState`하면
히스토리가 무한히 쌓여 뒤로가기가 동작하지 않게 된다.

### 새 대화방 생성 시 URL 확정

새 질문은 `chatSeq: null`로 전송되고, 서버가 방을 만들어 **SSE `chatInfo` 이벤트로 번호를 알려준다.**
그 시점에 `pushState('/chat/main/{seq}')`로 URL을 확정하고 좌측 목록을 갱신한다.

---

## 6. 답변 스트리밍 `streamAnswer()`

가장 복잡한 함수. `async function`이며 `fetch` + `ReadableStream`으로 SSE를 **수동 파싱**한다
(`EventSource`는 POST와 요청 본문을 지원하지 않으므로 쓸 수 없다).

```
streamAnswer(questionText)
│
├─ [준비]
│   isGenerating = true
│   .icon-send 숨김 / .icon-stop 표시        ← 같은 버튼이 전송↔중지로 바뀜
│   입력창 비우기
│   tempSeq = Date.now()                    ★ 블록 식별자 (DB id가 아직 없으므로)
│   activeStreamSeq = tempSeq
│   #view-area.append(createChatBlockHtml(tempSeq, question, getRetrievalMode()))
│   isNewChat = !currentChatSeq
│   기존 방이면 promoteChatToTop(currentChatSeq)   ← 서버 왕복 없이 목록 최상단으로
│   abortController = new AbortController()
│
├─ [요청] POST /chat/api/stream
│   body: { question, chatSeq: currentChatSeq, model, retrievalMode: getRetrievalMode() }
│   signal: abortController.signal
│
├─ [수신 루프] response.body.getReader() + TextDecoder
│   buffer += decode(value)
│   lines = buffer.split('\n');  buffer = lines.pop()   ★ 잘린 마지막 줄은 다음 회차로
│   for (line of lines):
│     ├─ line.startsWith('event:') → 이벤트명 플래그 설정
│     │     ※ includes()로 검사하면 답변 본문에 "event:" 가 들어간 줄이 이벤트로
│     │       오인되어 화면에 출력되지 않는다. 반드시 startsWith.
│     └─ line.startsWith('data:') → 플래그에 따라 분기
│           ├─ chatInfo      : currentChatSeq 확정, 새 방이면 pushState + 목록 갱신
│           ├─ used_sources  : 출처 로딩 끄고 createSourceBlockHtml() 로 카드 렌더
│           ├─ sources       : ⚠ 현재 주석 처리(미사용)
│           └─ (이벤트명 없음): JSON.parse → data.content 를 accumulatedText 에 누적
│                 첫 조각 도착 시 answer-loading → answer-result 로 전환
│   루프 끝마다:  targetDiv.innerHTML = appUtil.renderMarkdown(accumulatedText)
│                scrollChatToBottom()
│
├─ [catch]
│   error.name === 'AbortError'  → "[답변 생성이 중지되었습니다.]" (회색)
│   그 외                        → "[통신 중 오류가 발생했습니다]" (빨강)
│
└─ [finally]
    출처 로딩창 강제 숨김 (무한 로딩 방지)
    cursor-blink 클래스 제거
    createCopyButtonHtml() 을 답변 블록 뒤에 붙임
    $(targetDiv).data('markdown', accumulatedText)   ★ 복사용 원본을 메모리에 보관
    isGenerating = false, activeStreamSeq = null, 아이콘 복구
```

### 렌더링 방식이 "누적 후 전체 재렌더"인 이유

토큰 조각을 그냥 이어 붙이면 미완성 마크다운(`**굵게` 만 온 상태)이 깨져 보인다.
그래서 **매 회차마다 `accumulatedText` 전체를 다시 `renderMarkdown()`** 한다.
답변이 길어질수록 재렌더 비용이 커지지만(O(n²)), 1000자 이내 답변 전제라 실측상 문제없다.

### 중단 `stopGenerating()`

```
abortController.abort()          → fetch 가 AbortError 로 끊김
                                 → 서버의 emitter.send() 가 IOException
                                 → ChatService 의 while 루프 탈출 (LLM 읽기 중단, 리소스 절약)
isGenerating = false, 아이콘 복구
activeStreamSeq 로 해당 블록의 로딩 UI 3개 정리
```

> `activeStreamSeq`가 필요한 이유: 블록 ID에 seq 접미사가 붙으므로
> `#answer-loading-state` 같은 접미사 없는 셀렉터로는 **아무것도 잡히지 않는다.**

호출 지점: 중지 버튼, `showNewChatView()`, `openChatHistory()`.

### 검색 방식 결정 `getRetrievalMode()`

```
#retrieval-mode-select 가 있으면(dev)  → 그 값
없으면(prod)                          → #retrieval-mode-default hidden 값(서버 기본값)
둘 다 없으면                           → 2 (하이브리드 + 인접 청크)
```
dev에서 select를 바꾸면 `localStorage['retrievalMode']`에 저장되어 다음 방문에 복원된다.

---

## 7. 대화 블록 DOM 구조와 3단 로딩 상태

`createChatBlockHtml(seq, questionText, retrievalMode)`가 만드는 한 턴의 골격이다.
**모든 ID에 `seq` 접미사가 붙는다** — 한 화면에 여러 턴이 동시에 존재하기 때문.

```
#view-result_{seq}  .chat-turn-block
├─ (질문 말풍선)  ← appUtil.escapeHtml(questionText)
├─ (모드 배지)    ← retrievalMode === 2 이면 "청크 확장" 표시
├─ 탭 버튼
│   .chat-tab-btn[data-seq][data-target="answer"]   답변
│   .chat-tab-btn[data-seq][data-target="search"]   검색결과
│
├─ #content-answer-{seq}                     ← 답변 탭
│   ├─ #source-loading-state-{seq}           ① 출처 로딩 (animate-pulse)
│   ├─ #source-card-container-{seq}         ② 출처 카드 가로 스크롤 (hidden→표시)
│   ├─ #answer-loading-state-{seq}           ③ 답변 로딩 (스피너)
│   └─ #answer-result-state-{seq}            ④ 답변 본문 (hidden→표시)
│       └─ .ai-answer-block
│           └─ #llm-content-{seq}            ★ 마크다운 렌더 대상
│                                            ★ .data('markdown') 에 원본 보관
│       (이 뒤에 .action-bar-area 복사 버튼이 append 됨)
│
└─ #content-search-{seq}                     ← 검색결과 탭 (초기 hidden)
    └─ .search-result-box × N
```

### 로딩 상태 전이

| 시점 | `source-loading` | `source-card-container` | `answer-loading` | `answer-result` |
|---|---|---|---|---|
| 블록 생성 직후 | **표시** | hidden | **표시** | hidden |
| `used_sources` 수신 | hidden | **표시** | 표시 | hidden |
| 첫 텍스트 조각 수신 | hidden | 표시 | hidden | **표시** |
| `finally` (성공/실패 무관) | **강제 hidden** | - | hidden | 표시 |

`finally`에서 출처 로딩을 강제로 끄는 이유: 검색 결과가 없어 서버가 에러로 끊으면
`used_sources`가 오지 않아 **뱅글뱅글이 영구히 남는다.**

### 탭 전환 시 재측정

검색결과 탭은 초기 `hidden`이므로 요소 높이가 **0으로 측정된다.** 그래서 탭을 켜는 시점에
`markClampedExcerpts()`를 다시 호출해 발췌 잘림 여부를 판정한다.

---

## 8. 출처 렌더링

`createSourceBlockHtml(dataPart, tempSeq)` 하나가 **두 곳을 동시에** 그린다.

**서버가 답변에 실제로 인용된 출처만 내려주므로**, 프론트는 받은 것을 두 영역에 모두 그린다.
(예전에는 전체 후보를 받아 프론트가 `isUsed`로 걸렀다)

| 위치 | 대상 | 모양 |
|---|---|---|
| `#source-card-container-{seq}` | 답변 탭 상단 | 가로 스크롤 카드 (220px 고정) |
| `#content-search-{seq}` | 검색결과 탭 | 세로 리스트 |

### 번호와 배지

- **번호**: `src.docNo`(= `[CONTEXT]`의 `[문서N]` 번호)를 쓴다. 답변 본문의 `- [문서3] 정보보안 지침…`
  표기와 카드 번호가 일치해 사용자가 대조할 수 있다. 없으면 표시 순번으로 폴백.
- **배지**: `src.origin`으로 구분한다.

| `origin` | 배지 | 색 | 의미 |
|---|---|---|---|
| `HIT` | 검색결과 | 파랑 | 질문으로 직접 검색된 문서 |
| `PREV` / `NEXT` | 인접구간 | 주황 | 검색된 조문의 직전/직후 구간. **이것도 답변 근거다** |

### 채택 0건일 때

인용이 하나도 없으면(유형1~3 '근거 없음' 응답 등) 검색결과 탭에
**"답변에 인용된 출처가 없습니다."** 안내를 띄우고 카드는 그리지 않는다.
빈 영역을 아무 설명 없이 남기지 않기 위함이다.

### 카드 폭을 고정한 이유

원래 `flex-1 min-w-0`이었는데, 카드들이 화면 폭을 나눠 가져 찌그러져 내용이 거의 안 보였다.
`w-[220px] shrink-0`으로 바꿔야 컨테이너가 실제로 가로 스크롤된다.
카드는 "읽는 곳"이 아니라 **어떤 문서가 채택됐는지 식별하는 칩**이며, 전문은
검색결과 탭과 우측 패널에서 본다. 그래서 제목 1줄 + 메타 1줄 + 본문 2줄로 높이를 고정한다.

### 전문을 DOM이 아니라 메모리에 보관

```js
$card.data('fullContent', fullText).data('sourceTitle', rawTitle);
```

화면에는 축약본/발췌만 보이므로, 우측 패널에 전문을 넘기려면 어딘가에 원본이 있어야 한다.
`data-*` 속성에 넣으면 긴 문서 본문이 HTML에 그대로 박혀 용량과 이스케이프 문제가 생긴다.
**jQuery `.data()`는 DOM 속성이 아니라 내부 캐시에 저장**되므로 이 용도에 맞다.
같은 이유로 답변 복사도 `.data('markdown')`을 쓴다.

### 메타 라벨

```
문서 {id} · 버전 {version} · 시행 {effectiveDate}
```

**필드 이름에 속지 않도록 주의한다.**

| 표시 | 원본 필드 | 실제 의미 |
|---|---|---|
| `문서` | `id` | 청크 식별자 `{문서ID}_{청크번호}` |
| `버전` | `version` | 문서의 개정 차수. ⚠ **조문번호가 아니다** |
| `시행` | `effectiveDate` | 시행일자. ⚠ 공포일자가 아니다 |

조문번호(제N조)는 메타데이터에 없고 **본문(`content`) 첫머리**에 있다.
적재 시 청커가 각 청크의 본문을 그 조문 표기로 시작하게 만들어 두어서, 카드의 본문 미리보기
(2줄 클램프)만 봐도 `제21조(연차유급휴가) ①…` 처럼 어느 조문인지 바로 보인다.
우측 패널(`openSidePanel`)의 `#panel-meta`도 같은 규칙으로 표시한다.

검색결과 여부는 별도 배지(`src.origin`)로 보여준다. (위 "번호와 배지" 참조)

### 발췌 잘림 판정 `markClampedExcerpts($scope)`

```js
const isClamped = (this.scrollHeight - this.clientHeight) > 2;
$(this).toggleClass('is-clamped', isClamped);              // CSS 페이드 그라데이션
$(this).siblings('.excerpt-more').toggleClass('hidden', !isClamped);  // "전문 보기 →"
```

**두 번의 `requestAnimationFrame`으로 감싼 이유**: `show()` 직후 동기 측정은 값이 튀어서
잘리지 않은 문단에도 페이드가 붙는다. 레이아웃과 페인트가 끝난 다음 프레임에 측정해야 한다.
추가로 웹폰트(Pretendard)가 늦게 적용되면 줄 높이가 바뀌므로 `document.fonts.ready`에서
한 번 더 측정한다.

---

## 9. 과거 대화 복원 `loadChatHistory()`

`POST /chat/api/history`는 메시지를 **평면 배열**로 준다.

```
[{id:912, role:"USER", content:"..."},
 {id:913, role:"ASSISTANT", content:"...", searchContent:"[{...}]"}, ...]
```

화면은 "질문+답변"이 한 블록이므로 **짝짓기(pairing)** 가 필요하다.

```
forEach(item):
  role === 'USER'      → currentQuestion, currentBlockSeq = item.id 기억 (아직 안 그림)
  role === 'ASSISTANT' → 이 시점에 블록을 그린다
                         ├─ createChatBlockHtml(currentBlockSeq, currentQuestion)
                         ├─ 로딩 3개 끄고 answer-result 켜기
                         ├─ searchContent 있으면 createSourceBlockHtml() 로 출처 복원
                         ├─ #llm-content-{seq}.html(renderMarkdown(item.content))
                         ├─ .data('markdown', item.content)  ← 복사용
                         └─ createCopyButtonHtml() 붙이기
                         그리고 임시 변수 초기화
```

### 두 개의 방어 코드

| 상황 | 처리 |
|---|---|
| USER 없이 ASSISTANT만 있음 | `currentQuestion = "(질문 내역 없음)"` 로 블록 생성 |
| 마지막 USER의 답변이 없음 (스트리밍 중 에러/중단) | 루프 후 남은 `currentBlockSeq`로 블록을 그리고 **"답변 생성 중 오류가 발생했거나 중단된 대화입니다."** 빨간 문구 표시 |

두 번째는 실제로 자주 발생한다 — 사용자가 중지를 누르거나 검색 결과가 없어 SSE가 끊기면
질문만 저장되고 답변은 저장되지 않는다.

### 블록 ID가 두 종류인 점

| 경로 | 블록 seq |
|---|---|
| 실시간 스트리밍 | `Date.now()` (타임스탬프) |
| 과거 대화 복원 | USER 메시지의 **DB id** |

같은 대화라도 **새로고침 전후로 DOM ID가 달라진다.** ID에 의존하는 코드를 추가할 때 주의.

---

## 10. 좌측 목록 페이징

대화(counsel)와 아카이브(archive)가 **완전히 대칭인 코드 2벌**로 존재한다.
(`getLeftChatHistory` ↔ `getLeftArchiveHistory`, `renderLeftCharHistory` ↔ `renderLeftArchiveHistory`)

### 이중 페이징 구조

서버 페이징과 화면 표시 개수가 **분리**되어 있다.

```
서버:  page 0 (10건) → page 1 (10건) → ...        counselNextPage / counselHasNext
화면:  최초 3건만 표시 → 더보기 → +10건 → ...       counselDisplayLimit
```

`#toggle-counsel-list` (더보기) 클릭 시 3분기:

| 조건 | 동작 |
|---|---|
| 메모리에 아직 안 보여준 항목이 있음 | `displayLimit += 10` → 서버 요청 없이 다시 그리기 |
| 메모리는 다 봤지만 서버에 더 있음(`hasNext`) | `getLeftChatHistory(false, true)` → 다음 페이지 요청 후 확장 |
| 더 이상 없음 | `displayLimit = 3` 으로 **접기** |

### 경쟁 조건 방지 `requestVersion`

```js
if (reset) counselRequestVersion += 1;
const requestVersion = counselRequestVersion;
// ... 응답 콜백에서
if (requestVersion !== counselRequestVersion) return;   // 낡은 응답 폐기
```

사용자가 빠르게 아카이브/삭제/이름변경을 연달아 하면 `reset` 요청이 여러 번 나가고,
**응답 순서가 뒤바뀌어 낡은 목록이 최신 목록을 덮어쓸 수 있다.** 버전 토큰으로 막는다.
`loadChatHistory`도 같은 목적으로 `currentChatSeq !== requestedChatSeq`를 검사한다.

### 활성 항목이 접힌 영역에 있을 때

현재 보고 있는 방이 목록 3번째 밖에 있으면, 그 항목이 보이도록 `displayLimit`을 늘린다.

```js
let itemIndex = counselQuestions.findIndex(q => Number(q.id) === Number(chatSeq));
if (itemIndex > 2) counselDisplayLimit = Math.max(counselDisplayLimit, itemIndex + 1);
```

그 뒤 `focusActiveChatInSidebar()`가 `.active-chat-item`을 **스크롤 영역 중앙으로** 부드럽게 이동시킨다.
이때 컨테이너 클래스를 `shrink-0 flex-1` → `shrink`로 바꾼다 —
그래야 항목이 4개일 때는 딱 4개 높이만 차지하고, 20개일 때 스크롤이 생긴다.

### 서버 왕복 없는 즉시 정렬 `promoteChatToTop(chatId)`

질문을 보내면 그 대화방이 목록 맨 위로 올라가야 한다(ChatGPT 방식). 서버 재조회 대신
**메모리의 `modDate`를 현재 시각(`nowModDateString()`, `yyyy-MM-dd HH:mm`)으로 갱신하고 재정렬**한다.
서버의 `mod_date` 갱신은 비동기로 따로 일어난다.

### 병합 `mergeChatRoomItems(current, new)`

`Map`으로 id 중복을 제거하고 `modDate` 내림차순(동일 시각이면 id 내림차순)으로 재정렬한다.

---

## 11. 사이드바 · 검색 뷰 · 컨텍스트 메뉴

### 사이드바 토글 `updateSidebarUI()`

```
펼침(250px):  #sidebar-expanded-content 표시, #sidebar-collapsed-content 숨김
접힘(72px):   반대. #primary-actions 숨기고 아이콘 컬럼만 노출 (검색 아이콘 중복 방지)
```

### 자동 접힘과 사용자 조작 구분

```js
$(window).resize(function() {
    const isNarrow = $(window).width() < 1024;
    if (isNarrow && isSidebarOpen)                              → 접고 isSidebarAutoCollapsed = true
    else if (!isNarrow && !isSidebarOpen && isSidebarAutoCollapsed) → 다시 펼치고 플래그 해제
});
```

**`isSidebarAutoCollapsed`가 없으면** 사용자가 직접 접어둔 사이드바를 창 크기 변경이
임의로 다시 펼쳐 버린다. 사용자가 `#toggle-sidebar`를 누르면 이 플래그가 `false`로 초기화된다.

`.sidebar-expand-trigger`(접힘 상태의 대화/아카이브 아이콘)를 누르면 사이드바를 펼치고
`scrollIntoView`로 해당 섹션까지 이동한다.

### 검색 뷰 — 클라이언트 필터링

**서버 검색이 아니다.** 이미 메모리에 있는 `counselQuestions` / `archivedQuestions`를
제목 부분 문자열로 필터링한다(`renderSearchListView(query)`).

```js
filtered = targetArr.filter(item => String(item.text||'').toLowerCase().includes(q));
```

즉 **서버에서 아직 안 가져온 페이지의 대화는 검색되지 않는다.** 각 행에서 아카이브/이름변경/삭제도
바로 할 수 있고, 액션 후 `renderCharHistoryAll()`이 검색 결과까지 함께 갱신한다.

### 컨텍스트 메뉴의 z-index 처리

`.popup-menu`는 `position: fixed`로 화면 좌표에 직접 배치한다.

```
1. 다른 팝업 전부 닫기
2. #counsel-list > div, #archive-list > div 의 z-index 를 auto 로 초기화
3. 클릭한 항목의 부모만 z-index: 9999   ← 층 꼬임 해결의 핵심
4. getBoundingClientRect() 로 버튼 위치 계산
   top = btnRect.bottom + 4
   화면 아래로 넘치면 → top = btnRect.top - menuHeight - 4  (위로 뒤집기)
   left = btnRect.right - 150
```

닫기 트리거: `#sidebar-lists` 스크롤, `document` 클릭. 두 경우 모두 **z-index를 되돌린다**
(안 되돌리면 그 항목이 계속 다른 요소 위에 떠 있다).
`.popup-menu` 자체 클릭은 `stopPropagation()`으로 닫힘을 막는다.

### 답변 복사 `.btn-copy-answer`

```
$btn.closest('.action-bar-area').prev('.ai-answer-block').find('[id^="llm-content-"]')
  → 못 찾으면 .prev() 로 한 번 더 시도 (방어 코드)
textToCopy = $contentDiv.data('markdown') || $contentDiv[0].innerText
```

**복사 방식이 환경에 따라 갈린다** — 폐쇄망은 HTTP + IP 접속이 흔하다.

| 환경 | 방식 |
|---|---|
| HTTPS 또는 localhost (`window.isSecureContext`) | `navigator.clipboard.writeText()` |
| HTTP (IP 직접 접속) | 화면 밖 `<textarea>` + `document.execCommand('copy')` |

성공 시 버튼이 2초간 초록 체크 + "완료"로 바뀌고 원래대로 복구된다.

### 테마 토글

`<html>`에 `dark` 클래스를 토글하고 `localStorage['theme']`에 저장.
`#theme-text`를 `DARK MODE` / `LIGHT MODE`로 동기화한다.
SweetAlert2 팝업도 `document.documentElement.classList.contains('dark')`를 읽어 배경/글자색을 맞춘다.

---

## 12. 우측 상세 패널

`openSidePanel(id, contentText, titleText, version, effectiveDate)`

```
제목과 본문이 둘 다 비어 있을 때만 열지 않는다 (한쪽이라도 있으면 표시)
#panel-title.text(titleText)                          ★ .html() 아님
#panel-content.text(normalizeSourceText(contentText)) ★ .html() 아님
#panel-meta.text("문서번호 X · 버전 Y · 시행일자 Z")
```

> **출처 원문은 평문으로만 취급한다.** `.html()`을 쓰면 원문 속 태그가 실행된다.
> 검색엔진 색인 데이터는 신뢰할 수 없는 입력이다.

### 리사이저 `#panel-resizer`

```
mousedown/touchstart → isResizing = true, body 커서 col-resize, transition 임시 제거
mousemove/touchmove  → newWidth = window.innerWidth - bodyPaddingRight - clientX
                       clamp: 최소 300px, 최대 min(창폭 × 45%, 600px)
mouseup/touchend     → isResizing = false, transition 복구
```

트랜지션을 드래그 중에만 제거하는 이유: 그대로 두면 마우스를 따라가는 게 눈에 띄게 늦다.
`#btn-reset-panel-size`는 400px로 되돌린다(이때는 트랜지션을 잠시 켜서 부드럽게).
마우스와 터치를 모두 지원한다(태블릿).

---

## 13. XSS 방어 규약 (필수 준수)

이 프로젝트에서 **화면에 꽂히는 문자열의 출처는 전부 신뢰할 수 없다.**

| 데이터 | 출처 | 위험 |
|---|---|---|
| LLM 답변 | 모델 생성 | 프롬프트 인젝션으로 `<script>` 유도 가능 |
| 출처 제목·본문 | 검색엔진 색인 | 원본 문서에 태그가 섞여 있을 수 있음 |
| 사용자 질문 | 사용자 입력 | 직접 입력 |
| 대화방 제목 | 사용자 입력(이름변경) | 직접 입력 |

### 반드시 지킬 규칙

| 넣는 곳 | 써야 하는 함수 |
|---|---|
| 템플릿 문자열의 **텍스트** 위치 | `appUtil.escapeHtml(str)` |
| 템플릿 문자열의 **속성값** 위치 (`data-x="..."`) | `appUtil.escapeAttr(str)` |
| 마크다운을 HTML로 (`innerHTML`) | `appUtil.renderMarkdown(md)` — **이것만 사용** |
| 출처 원문 표시 | `.text()` + `appUtil.normalizeSourceText()` |

### `renderMarkdown()` 파이프라인

```
markdownText
  → marked.parse()                     (결과를 신뢰하지 않는다)
  → DOMPurify.sanitize(rawHtml, {
        ALLOWED_TAGS: APP_MD_ALLOWED_TAGS,   // p, h1~h6, table 계열, code, a, img, input(체크리스트) 등
        ALLOWED_ATTR: APP_MD_ALLOWED_ATTR,   // href, src, colspan, class, id, checked 등
        ALLOW_DATA_ATTR: false                   // data-* 전면 차단
    })
  → innerHTML 삽입
```

`initSanitizerHooks()`가 등록하는 `afterSanitizeAttributes` 훅이 모든 `<a href>`에
`target="_blank"` + `rel="noopener noreferrer"`를 강제한다 (**tabnabbing 방어**).

> **절대 하지 말 것**: 외부 유래 문자열을 `escapeHtml` 없이 템플릿 문자열에 끼워 넣기,
> `renderMarkdown` 대신 `marked.parse()`를 직접 `innerHTML`에 넣기,
> 출처 원문을 `.html()`로 넣기.

---

## 14. `app-util.js` 공통 유틸

이 화면이 실제로 쓰는 함수만 담은 작은 유틸 모음이다. 전역 `window.appUtil`로 노출된다.

| 분류 | 함수 | 설명 |
|---|---|---|
| **통신** | `ajaxPost(url, data, success, error)` | `$.ajax` JSON POST 래퍼. **타임아웃 3분**. 401 이면 `error` 대신 `handleSessionExpired()` 호출 |
| | `handleSessionExpired()` | 세션 만료 alert 을 **한 번만** 띄우고 확인 시 `SSO_PORTAL_URL`로 이동 |
| **알림** | `toast(message)` | SweetAlert2 토스트. 우상단 2.5초 |
| | `alert(message, icon)` | 확인 버튼만. **첫 줄=제목, 나머지=본문.** 평문 처리 |
| | `confirm(message)` | 확인/취소. `Promise<boolean>` |
| | `prompt(title, defaultValue)` | 입력창(이름 변경용). 빈 값 검증 포함. 취소 시 `null` |
| **입력** | `isEmptyString(s)` | null/undefined/공백뿐인지 |
| | `removeAllSpacesAndIsBlank(str)` | 공백 제거 후 빈 문자열인지 |
| | `containsMarkup(str)` / `MARKUP_REJECT_MESSAGE` | HTML 태그·스크립트 스킴 판정. 서버 `InputGuard`와 같은 규칙 |
| **XSS** | `escapeHtml(str)` | `& < > " ' \`` → 엔티티 |
| | `escapeAttr(str)` | 속성값용 이스케이프 |
| | `renderMarkdown(md)` | marked + DOMPurify (13장 참조) |
| | `initSanitizerHooks()` | DOMPurify 훅 1회 등록 |
| **답변** | `normalizeCitationLabels(text)` | `[document1]` 같은 비표준 라벨을 `[문서1]`로 통일 (서버 `AnswerSections`와 동일) |
| **텍스트** | `normalizeSourceText(text)` | 아래 상세 |
| | `toPreviewText(text)` | `normalizeSourceText` 후 모든 공백을 하나로 → 카드 미리보기용 |
| **저장소** | `storageGet(key, default)` / `storageSet(key, value)` | `localStorage` + `try/catch` |

#### `normalizeSourceText()` 4단계

외부에서 가져온 문서의 개행이 **리터럴 `"\n"` 두 글자로 저장된 경우**가 있어 이를 복원한다.

```
1) 리터럴 이스케이프 복원:  \r\n, \n, \r → 실제 개행 / \t → 공백
2) 실제 개행 통일:          CRLF, CR → LF
3) 줄 단위 정리:            가로 공백(스페이스/탭/NBSP) 압축 + 양끝 trim
4) 빈 줄 축약:              연속 3줄 이상 → 2줄
```

---

## 15. localStorage 키

| 키 | 값 | 쓰는 곳 |
|---|---|---|
| `theme` | `'dark'` / `'light'` | `#theme-toggle` |
| `retrievalMode` | `'1'` / `'2'` | `#retrieval-mode-select` (dev만) |
| `selectedModel` | `'v1.2.1'` (기본) | `streamAnswer()`에서 읽어 서버로 전송. **모델 선택 UI가 제거되어 저장하는 곳이 없다** |

접근은 전부 `appUtil.storageGet/Set`을 거친다. 시크릿 모드나 저장소 차단 환경에서
`localStorage` 접근 자체가 예외를 던질 수 있어 `try/catch`로 감싸져 있다.

---

## 16. UI 알려진 이슈

### 1. 컨텍스트 경로(context path)가 하드코딩되어 있다 ⚠ 배포 시 주의

`main.js`가 절대 경로를 그대로 쓴다.

```js
fetch('/chat/api/stream', ...)              // API
appUtil.ajaxPost('/chat/api/history', ...)  // API
updateBrowserHistory('/chat/main/' + seq)  // 라우팅
/^\/chat\/main\/(\d+)\/?$/                 // 경로 파싱
```

`chat.html`은 `th:href="@{/img/favicon.ico}"`로 컨텍스트 경로를 반영하지만 **JS는 반영하지 않는다.**
`rag-chat.war`를 외장 Tomcat에 올려 컨텍스트 경로가 `/rag-chat`가 되면 실제 URL은
`/rag-chat/chat/main/1`, API는 `/rag-chat/chat/api/stream`이 되어야 하는데 JS가 `/chat/api/stream`을
호출해 **전부 404**가 된다.

**대응**: ROOT 컨텍스트(`ROOT.war`)로 배포하거나, 템플릿에서 컨텍스트 경로를 전역 변수로 주입해
JS가 그것을 접두어로 쓰게 고쳐야 한다.

### 2. ~~`sources` 이벤트가 버려진다~~ (해소됨)

서버에서 `sources` 이벤트 전송을 제거했다. 이제 `used_sources` 한 번만 전송되며,
그것도 **채택된 출처만** 담기므로 전송량이 크게 줄었다.
`main.js`의 `isSourcesEvent` 분기는 남아 있으나 서버가 보내지 않아 실행되지 않는다(정리 대상).

### 3. 모델 선택 잔재

모델이 단일이라 드롭다운이 제거됐는데 `streamAnswer()`는 여전히
`appUtil.storageGet('selectedModel', 'v1.2.1')`을 읽어 서버로 보낸다.
서버(`ChatService.streamAnswer`)의 `selectedModel` 파라미터는 **사용되지 않는다**
(설정 모델 고정). 동작에 문제는 없으나 양쪽 다 정리 대상.

### 4. 검색이 로드된 페이지 범위로 제한된다

검색 뷰는 클라이언트 필터링이므로 **아직 서버에서 안 가져온 대화는 검색되지 않는다.**
대화가 수십 개를 넘으면 사용자가 "검색해도 안 나온다"고 느낀다.
서버 측 제목 검색 API가 필요하다.

### 5. 마크다운 전체 재렌더의 비용

토큰이 올 때마다 `accumulatedText` 전체를 `marked.parse()` + `DOMPurify.sanitize()`한다.
답변 길이에 대해 O(n²)이며, 시스템 프롬프트가 1000자 이내를 지시하므로 현재는 문제없지만
답변 상한을 늘리면 후반부에 타이핑이 눌리는 현상이 생길 수 있다.

### 6. 대화/아카이브 코드가 완전히 중복

`getLeftChatHistory`/`getLeftArchiveHistory`, `renderLeftCharHistory`/`renderLeftArchiveHistory`,
그리고 상태 변수 7쌍이 `counsel`/`archive` 접두어만 다른 동일 코드다(약 300줄).
한쪽만 고치고 다른 쪽을 빠뜨리는 버그가 나기 쉽다. `archiveYn`을 인자로 받는 한 벌로 합칠 수 있다.

### 7. 오타성 함수명

`renderLeftCharHistory` (**Char**) — `Chat`의 오타. `renderCharHistoryAll`도 같다.
동작에는 영향 없지만 검색·가독성에 방해된다.
