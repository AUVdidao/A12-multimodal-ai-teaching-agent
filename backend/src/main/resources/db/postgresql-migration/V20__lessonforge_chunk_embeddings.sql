create table if not exists lessonforge_chunk_embeddings (
    chunk_id bigint not null,
    project_id bigint not null,
    material_id bigint not null,
    model_connection_id bigint not null,
    model_id varchar(255) not null,
    dimension integer not null,
    vector double precision[] not null,
    status varchar(32) not null,
    error_summary varchar(500),
    created_at timestamp(6) not null default now(),
    updated_at timestamp(6) not null default now(),
    primary key (chunk_id),
    constraint uk_lessonforge_embedding_chunk unique (chunk_id),
    constraint ck_lessonforge_embedding_dimension check (dimension > 0),
    constraint ck_lessonforge_embedding_status check (status in ('READY', 'FAILED'))
);

create index if not exists idx_lessonforge_embedding_scope
    on lessonforge_chunk_embeddings (project_id, material_id, status, dimension);
