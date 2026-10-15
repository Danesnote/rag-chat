-- =====================================================================
-- RAG Chat 스키마 (PostgreSQL + pgvector)
--
-- spring.sql.init.mode=always 이면 애플리케이션 기동 시 자동 실행된다. (모든 구문이 멱등)
-- 운영 DB 처럼 확장 생성 권한이 없으면 DBA 가 이 파일을 한 번 실행하고 init.mode=never 로 둔다.
--
-- [주의] embedding 컬럼 차원(1024)은 임베딩 모델(BAAI/bge-m3)의 출력 차원과 같아야 한다.
--        모델을 바꾸면 이 값과 rag.embedding.dimension 을 함께 바꾸고 문서를 다시 적재한다.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ── 대화 ─────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS demo_chat_list (
    chat_seq    BIGSERIAL    PRIMARY KEY,
    reg_date    TIMESTAMP,
    title       TEXT,
    user_seq    BIGINT       NOT NULL,
    archive_yn  VARCHAR(8)   DEFAULT 'N',
    mod_date    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS demo_chat_message (
    message_seq     BIGSERIAL    PRIMARY KEY,
    content         TEXT,
    search_content  TEXT,
    reg_date        TIMESTAMP,
    role            VARCHAR(16),
    chat_seq        BIGINT       NOT NULL REFERENCES demo_chat_list (chat_seq),
    token_count     INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS demo_chat_summary (
    summary_seq       BIGSERIAL     PRIMARY KEY,
    chat_seq          BIGINT        NOT NULL,
    summary_version   INTEGER       NOT NULL,
    from_message_seq  BIGINT        NOT NULL,
    to_message_seq    BIGINT        NOT NULL,
    summary_content   TEXT          NOT NULL,
    token_count       INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_list_user    ON demo_chat_list (user_seq, archive_yn, mod_date DESC);
CREATE INDEX IF NOT EXISTS idx_chat_message_chat ON demo_chat_message (chat_seq, message_seq);
CREATE INDEX IF NOT EXISTS idx_chat_summary_chat ON demo_chat_summary (chat_seq, summary_version DESC);

-- ── 검색 대상 문서 ───────────────────────────────────

-- 원본 문서 1건
CREATE TABLE IF NOT EXISTS rag_document (
    document_id     BIGSERIAL    PRIMARY KEY,
    title           TEXT         NOT NULL,
    version         VARCHAR(32),
    effective_date  VARCHAR(32),
    source_name     TEXT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 문서를 나눈 청크. (document_id, chunk_no) 로 앞/뒤 청크를 찾는다.
CREATE TABLE IF NOT EXISTS rag_chunk (
    chunk_seq    BIGSERIAL     PRIMARY KEY,
    document_id  BIGINT        NOT NULL REFERENCES rag_document (document_id) ON DELETE CASCADE,
    chunk_no     INTEGER       NOT NULL,
    content      TEXT          NOT NULL,
    embedding    vector(1024)  NOT NULL,
    UNIQUE (document_id, chunk_no)
);

-- 벡터 검색: 코사인 거리 HNSW 인덱스
CREATE INDEX IF NOT EXISTS idx_rag_chunk_embedding ON rag_chunk USING hnsw (embedding vector_cosine_ops);
-- 키워드 검색: ILIKE 부분일치를 가속하는 trigram 인덱스 (한국어 형태소 분석기 없이도 동작)
CREATE INDEX IF NOT EXISTS idx_rag_chunk_content_trgm ON rag_chunk USING gin (content gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rag_document_title_trgm ON rag_document USING gin (title gin_trgm_ops);
