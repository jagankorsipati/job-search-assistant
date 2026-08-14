CREATE SCHEMA IF NOT EXISTS job_search_assistant AUTHORIZATION CURRENT_USER;

COMMENT ON SCHEMA job_search_assistant IS
    'Application-owned schema; business tables are introduced only by later reviewed migrations.';
