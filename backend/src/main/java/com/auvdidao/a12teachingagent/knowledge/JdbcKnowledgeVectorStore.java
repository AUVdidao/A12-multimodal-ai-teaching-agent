package com.auvdidao.a12teachingagent.knowledge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class JdbcKnowledgeVectorStore implements KnowledgeVectorStore {

    private final JdbcTemplate jdbcTemplate;
    private final boolean postgres;

    public JdbcKnowledgeVectorStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.postgres = isPostgres(dataSource);
    }

    @Override
    public PersistenceResult replace(
            Long projectId,
            Long materialId,
            Long modelConnectionId,
            String modelId,
            int dimension,
            List<VectorInput> vectors
    ) {
        requirePostgres();
        validateInputs(projectId, materialId, modelConnectionId, modelId, dimension, vectors);
        jdbcTemplate.update("delete from lessonforge_chunk_embeddings where project_id=? and material_id=?", projectId, materialId);
        for (VectorInput input : vectors) {
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                        insert into lessonforge_chunk_embeddings
                            (chunk_id, project_id, material_id, model_connection_id, model_id, dimension, vector, status, error_summary, created_at, updated_at)
                        values (?, ?, ?, ?, ?, ?, ?, 'READY', null, now(), now())
                        on conflict (chunk_id) do update set
                            project_id=excluded.project_id,
                            material_id=excluded.material_id,
                            model_connection_id=excluded.model_connection_id,
                            model_id=excluded.model_id,
                            dimension=excluded.dimension,
                            vector=excluded.vector,
                            status='READY',
                            error_summary=null,
                            updated_at=now()
                        """);
                statement.setLong(1, input.chunkId());
                statement.setLong(2, projectId);
                statement.setLong(3, materialId);
                statement.setLong(4, modelConnectionId);
                statement.setString(5, modelId.strip());
                statement.setInt(6, dimension);
                statement.setArray(7, connection.createArrayOf("float8", input.vector().toArray(Double[]::new)));
                return statement;
            });
        }
        return new PersistenceResult(vectors.size(), dimension);
    }

    @Override
    public List<VectorHit> search(Long projectId, List<Long> materialIds, List<Double> queryVector, int dimension, int limit) {
        requirePostgres();
        if (projectId == null || projectId <= 0 || queryVector == null || queryVector.size() != dimension || dimension <= 0 || limit < 1 || limit > 20) {
            throw new IllegalArgumentException("vector search request is invalid");
        }
        List<Long> scopedMaterials = materialIds == null ? List.of() : materialIds.stream().filter(Objects::nonNull).distinct().toList();
        String materialClause = scopedMaterials.isEmpty()
                ? ""
                : " and e.material_id in (" + "?, ".repeat(Math.max(0, scopedMaterials.size() - 1)) + "?)";
        String sql = """
                select e.chunk_id, e.material_id,
                       coalesce((
                           select sum(a.value * b.value) /
                                  nullif(sqrt(sum(a.value * a.value) * sum(b.value * b.value)), 0)
                           from unnest(e.vector) with ordinality as a(value, ord)
                           join unnest(q.vector) with ordinality as b(value, ord) on a.ord=b.ord
                       ), 0) as score
                from lessonforge_chunk_embeddings e
                cross join (select ?::double precision[] as vector) q
                where e.project_id=? and e.status='READY' and e.dimension=?
                """ + materialClause + " order by score desc, e.chunk_id asc limit ?";
        return jdbcTemplate.query(connection -> {
            var statement = connection.prepareStatement(sql);
            int index = 1;
            statement.setArray(index++, connection.createArrayOf("float8", queryVector.toArray(Double[]::new)));
            statement.setLong(index++, projectId);
            statement.setInt(index++, dimension);
            for (Long materialId : scopedMaterials) {
                statement.setLong(index++, materialId);
            }
            statement.setInt(index, limit);
            return statement;
        }, (resultSet, rowNum) -> new VectorHit(
                resultSet.getLong("chunk_id"),
                resultSet.getLong("material_id"),
                resultSet.getDouble("score")
        ));
    }

    @Override
    public boolean hasCompleteIndex(Long projectId, List<Long> materialIds, int dimension) {
        requirePostgres();
        if (projectId == null || projectId <= 0 || materialIds == null || materialIds.isEmpty() || dimension <= 0) {
            return false;
        }
        List<Long> scopedMaterials = materialIds.stream().filter(Objects::nonNull).distinct().toList();
        String placeholders = "?, ".repeat(Math.max(0, scopedMaterials.size() - 1)) + "?";
        String sql = """
                select exists (
                    select 1
                    from knowledge_chunks c
                    where c.project_id=? and c.material_id in (%s)
                )
                and not exists (
                    select 1
                    from knowledge_chunks c
                    where c.project_id=? and c.material_id in (%s)
                      and not exists (
                          select 1 from lessonforge_chunk_embeddings e
                          where e.chunk_id=c.id and e.project_id=c.project_id
                            and e.material_id=c.material_id and e.status='READY' and e.dimension=?
                      )
                )
                """.formatted(placeholders, placeholders);
        return Boolean.TRUE.equals(jdbcTemplate.query(sql, statement -> {
            int index = 1;
            statement.setLong(index++, projectId);
            for (Long materialId : scopedMaterials) statement.setLong(index++, materialId);
            statement.setLong(index++, projectId);
            for (Long materialId : scopedMaterials) statement.setLong(index++, materialId);
            statement.setInt(index, dimension);
        }, resultSet -> resultSet.next() && resultSet.getBoolean(1)));
    }

    @Override
    public boolean available() {
        return postgres;
    }

    private void validateInputs(Long projectId, Long materialId, Long modelConnectionId, String modelId, int dimension, List<VectorInput> vectors) {
        if (projectId == null || projectId <= 0 || materialId == null || materialId <= 0 || modelConnectionId == null || modelConnectionId <= 0 || modelId == null || modelId.isBlank() || dimension <= 0 || vectors == null || vectors.isEmpty()) {
            throw new IllegalArgumentException("vector persistence request is invalid");
        }
        var seen = new java.util.HashSet<Long>();
        for (VectorInput input : vectors) {
            if (input == null || input.chunkId() == null || input.chunkId() <= 0 || input.vector() == null || input.vector().size() != dimension || !seen.add(input.chunkId()) || input.vector().stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
                throw new IllegalArgumentException("vector persistence contains invalid chunk data");
            }
        }
    }

    private void requirePostgres() {
        if (!postgres) {
            throw new IllegalStateException("VECTOR_STORE_UNAVAILABLE");
        }
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException exception) {
            throw new IllegalStateException("VECTOR_STORE_METADATA_UNAVAILABLE", exception);
        }
    }
}
