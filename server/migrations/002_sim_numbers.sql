-- 管理员在网页上手动填写的 SIM 号码（JSON：{"0":"+8613800138000","1":"..."}，键为卡槽号）。
-- 手机自动上报的号码仍保存在 sims_json 里；展示时手动值优先，因为很多运营商不往 SIM 里写号码。
ALTER TABLE devices ADD COLUMN sim_numbers_json TEXT NOT NULL DEFAULT '{}';
