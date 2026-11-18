SELECT t.TABLE_SCHEMA db, COUNT(*) history_rows FROM information_schema.tables t WHERE t.TABLE_NAME='flyway_schema_history' GROUP BY t.TABLE_SCHEMA ORDER BY t.TABLE_SCHEMA;
