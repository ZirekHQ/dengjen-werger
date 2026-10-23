FROM postgres:17

COPY --chown=postgres:postgres schema.sql /docker-entrypoint-initdb.d/schema.sql

USER postgres
