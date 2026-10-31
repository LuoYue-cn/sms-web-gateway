-- 验证码识别结果、短信类别（入库时计算；code_ver 记录识别规则版本，规则升级后可只重扫旧版本的行）
ALTER TABLE messages ADD COLUMN code TEXT;
ALTER TABLE messages ADD COLUMN category TEXT;
ALTER TABLE messages ADD COLUMN code_ver INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversations ADD COLUMN last_code TEXT;

-- 「最近验证码」只扫带验证码的行；回填只扫尚未按当前规则处理的收件行；统计按时间范围扫描
CREATE INDEX idx_messages_code_time ON messages(timestamp) WHERE code IS NOT NULL;
CREATE INDEX idx_messages_codever ON messages(code_ver) WHERE direction = 'incoming';
CREATE INDEX idx_messages_time ON messages(timestamp);
CREATE INDEX idx_tasks_created ON send_tasks(created_at);
