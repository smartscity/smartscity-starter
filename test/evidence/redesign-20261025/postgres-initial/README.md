# Initial PostgreSQL harness attempt

PARTIAL: the portable PostgreSQL distribution contains server tools but no `createdb` executable. The private server was stopped and its data directory removed; no production test ran in this attempt. The retry uses the existing PostgreSQL JDBC driver to create the owned database. See [completed run](../postgres/result.json).
