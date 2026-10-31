-- 每日报告：每个日期（按站点时区）一份，重新生成会覆盖。
-- status: running | done | failed；source: ai | rules（AI 不可用时回退到规则模板）
CREATE TABLE reports (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    report_date       TEXT    NOT NULL UNIQUE,
    status            TEXT    NOT NULL,
    source            TEXT    NOT NULL DEFAULT 'rules',
    model             TEXT    NOT NULL DEFAULT '',
    detail_level      INTEGER NOT NULL DEFAULT 0,
    content_json      TEXT    NOT NULL DEFAULT '{}',
    error             TEXT    NOT NULL DEFAULT '',
    prompt_tokens     INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL
);
