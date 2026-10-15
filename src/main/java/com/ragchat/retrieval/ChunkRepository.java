package com.ragchat.retrieval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * 문서·청크 저장소 (PostgreSQL + pgvector).
 *
 * <p>벡터 연산자({@code <=>})와 배열 파라미터를 써야 해서 JPA 대신 {@link JdbcTemplate} 으로 직접 다룬다.
 * 벡터는 {@code [0.1,0.2,...]} 문자열 리터럴을 {@code CAST(? AS vector)} 로 넘긴다.</p>
 */
@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private static final String SELECT_CHUNK = """
            SELECT c.document_id, c.chunk_no, c.content, d.title, d.version, d.effective_date
            """;

    private final JdbcTemplate jdbcTemplate;

    // ── 검색 ─────────────────────────────────────────────

    /**
     * 코사인 거리 기준 벡터 검색. 반환 점수는 코사인 유사도(1 - 거리)다.
     */
    public List<DocumentChunk> searchByVector(float[] queryVector, int limit) {
        String vector = EmbeddingClient.toVectorLiteral(queryVector);
        String sql = SELECT_CHUNK + """
                     , 1 - (c.embedding <=> CAST(? AS vector)) AS score
                  FROM rag_chunk c
                  JOIN rag_document d ON d.document_id = c.document_id
                 ORDER BY c.embedding <=> CAST(? AS vector)
                 LIMIT ?
                """;
        return jdbcTemplate.query(sql, CHUNK_MAPPER, vector, vector, limit);
    }

    /**
     * 키워드 부분일치 검색. 점수는 본문에 포함된 키워드 수 + 문서명에 포함된 키워드 수 × 2 다.
     * (trigram GIN 인덱스가 ILIKE 를 가속한다)
     *
     * @param likePatterns {@link KeywordQuery#likePatterns(String)} 결과
     */
    public List<DocumentChunk> searchByKeywords(List<String> likePatterns, int limit) {
        if (likePatterns.isEmpty()) {
            return List.of();
        }
        String sql = SELECT_CHUNK + """
                     , (SELECT count(*) FROM unnest(?::text[]) p WHERE c.content ILIKE p)
                       + 2 * (SELECT count(*) FROM unnest(?::text[]) p WHERE d.title ILIKE p) AS score
                  FROM rag_chunk c
                  JOIN rag_document d ON d.document_id = c.document_id
                 WHERE c.content ILIKE ANY (?::text[])
                    OR d.title ILIKE ANY (?::text[])
                 ORDER BY score DESC, c.document_id, c.chunk_no
                 LIMIT ?
                """;
        return jdbcTemplate.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            Array patterns = con.createArrayOf("text", likePatterns.toArray());
            ps.setArray(1, patterns);
            ps.setArray(2, patterns);
            ps.setArray(3, patterns);
            ps.setArray(4, patterns);
            ps.setInt(5, limit);
            return ps;
        }, CHUNK_MAPPER);
    }

    /**
     * 같은 문서에서 청크 순번이 {@code [fromNo, toNo]} 범위인 청크를 순번 순으로 조회한다.
     * (인접 청크 확장에 사용)
     */
    public List<DocumentChunk> findRange(long documentId, int fromNo, int toNo) {
        String sql = SELECT_CHUNK + """
                     , 0 AS score
                  FROM rag_chunk c
                  JOIN rag_document d ON d.document_id = c.document_id
                 WHERE c.document_id = ? AND c.chunk_no BETWEEN ? AND ?
                 ORDER BY c.chunk_no
                """;
        return jdbcTemplate.query(sql, CHUNK_MAPPER, documentId, fromNo, toNo);
    }

    // ── 적재 / 관리 ──────────────────────────────────────

    public long insertDocument(String title, String version, String effectiveDate, String sourceName) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rag_document (title, version, effective_date, source_name) VALUES (?, ?, ?, ?)",
                    new String[]{"document_id"});
            ps.setString(1, title);
            ps.setString(2, version);
            ps.setString(3, effectiveDate);
            ps.setString(4, sourceName);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("문서 ID 를 발급받지 못했습니다.");
        }
        return key.longValue();
    }

    public void insertChunks(long documentId, List<String> contents, List<float[]> embeddings) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO rag_chunk (document_id, chunk_no, content, embedding) VALUES (?, ?, ?, CAST(? AS vector))",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setLong(1, documentId);
                        ps.setInt(2, i);
                        ps.setString(3, contents.get(i));
                        ps.setString(4, EmbeddingClient.toVectorLiteral(embeddings.get(i)));
                    }

                    @Override
                    public int getBatchSize() {
                        return contents.size();
                    }
                });
    }

    /** 문서 목록 (최근 등록 순) */
    public List<DocumentSummary> findDocuments() {
        return jdbcTemplate.query("""
                SELECT d.document_id, d.title, d.version, d.effective_date, d.source_name, d.created_at,
                       (SELECT count(*) FROM rag_chunk c WHERE c.document_id = d.document_id) AS chunk_count
                  FROM rag_document d
                 ORDER BY d.document_id DESC
                """, (rs, n) -> {
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new DocumentSummary(
                    rs.getLong("document_id"),
                    rs.getString("title"),
                    rs.getString("version"),
                    rs.getString("effective_date"),
                    rs.getString("source_name"),
                    rs.getInt("chunk_count"),
                    createdAt == null ? null : createdAt.toLocalDateTime());
        });
    }

    /** 문서와 그 청크를 삭제한다. (청크는 FK ON DELETE CASCADE) */
    public boolean deleteDocument(long documentId) {
        return jdbcTemplate.update("DELETE FROM rag_document WHERE document_id = ?", documentId) > 0;
    }

    public Map<String, Object> countAll() {
        return jdbcTemplate.queryForMap(
                "SELECT (SELECT count(*) FROM rag_document) AS documents, (SELECT count(*) FROM rag_chunk) AS chunks");
    }

    private static final RowMapper<DocumentChunk> CHUNK_MAPPER = (rs, n) -> new DocumentChunk(
            rs.getLong("document_id"),
            rs.getInt("chunk_no"),
            rs.getString("title"),
            rs.getString("version"),
            rs.getString("effective_date"),
            rs.getString("content"),
            rs.getDouble("score")
    );

    /**
     * 문서 목록 항목.
     */
    public record DocumentSummary(
            long documentId,
            String title,
            String version,
            String effectiveDate,
            String sourceName,
            int chunkCount,
            java.time.LocalDateTime createdAt
    ) {
    }
}
