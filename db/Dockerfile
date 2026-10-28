FROM postgres:17

COPY --chown=postgres:postgres migrations/*.sql /docker-entrypoint-initdb.d/

USER postgres
