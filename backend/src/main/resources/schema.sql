CREATE TABLE IF NOT EXISTS app_user (
 user_id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(50) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, nickname VARCHAR(50) NOT NULL,
 role VARCHAR(10) NOT NULL DEFAULT 'USER', create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS travel_plan (
 plan_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, plan_name VARCHAR(100) NOT NULL,
 start_city VARCHAR(100) NOT NULL, destination_city VARCHAR(100) NOT NULL, current_version_id BIGINT,
 deleted BOOLEAN NOT NULL DEFAULT FALSE, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_plan_user_updated (user_id, update_time),
 FOREIGN KEY (user_id) REFERENCES app_user(user_id)
);
CREATE TABLE IF NOT EXISTS generation_task (
 task_id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, request_hash CHAR(64) NOT NULL, task_type VARCHAR(10) NOT NULL,
 plan_id BIGINT, base_version_id BIGINT, input_snapshot MEDIUMTEXT NOT NULL,
 modification_feedback VARCHAR(500), status VARCHAR(16) NOT NULL, phase VARCHAR(16),
 search_snapshot_id BIGINT, result_version_id BIGINT, error_code VARCHAR(50), error_summary VARCHAR(500),
 mode VARCHAR(10) NOT NULL, phase_timings MEDIUMTEXT,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, start_time TIMESTAMP, finish_time TIMESTAMP,
 UNIQUE (user_id, request_id), INDEX idx_task_user_status (user_id,status), INDEX idx_task_status_start (status,start_time),
 FOREIGN KEY (user_id) REFERENCES app_user(user_id)
);
CREATE TABLE IF NOT EXISTS search_snapshot (
 snapshot_id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL UNIQUE,
 destination_city VARCHAR(100) NOT NULL, research_json MEDIUMTEXT NOT NULL,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(task_id) REFERENCES generation_task(task_id)
);
CREATE TABLE IF NOT EXISTS travel_plan_version (
 version_id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT NOT NULL, version_no INT NOT NULL,
 parent_version_id BIGINT, task_id BIGINT NOT NULL UNIQUE, search_snapshot_id BIGINT NOT NULL,
 input_snapshot MEDIUMTEXT NOT NULL, modification_feedback VARCHAR(500), content_json MEDIUMTEXT NOT NULL,
 expense_summary MEDIUMTEXT NOT NULL, total_cost DECIMAL(12,2) NOT NULL,
 budget_difference DECIMAL(12,2) NOT NULL, over_budget BOOLEAN NOT NULL,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(plan_id, version_no),
 FOREIGN KEY(plan_id) REFERENCES travel_plan(plan_id), FOREIGN KEY(task_id) REFERENCES generation_task(task_id),
 FOREIGN KEY(search_snapshot_id) REFERENCES search_snapshot(snapshot_id)
);
CREATE TABLE IF NOT EXISTS api_log (
 log_id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 stage VARCHAR(16) NOT NULL, call_type VARCHAR(10) NOT NULL, provider VARCHAR(50) NOT NULL,
 model_name VARCHAR(100), status VARCHAR(30) NOT NULL, cost_time_ms BIGINT NOT NULL,
 input_tokens BIGINT, output_tokens BIGINT, search_units DECIMAL(12,4), provider_request_id VARCHAR(150),
 estimated_cost DECIMAL(16,6), cost_status VARCHAR(16) NOT NULL, pricing_snapshot TEXT,
 error_summary VARCHAR(500), create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_log_task_stage (task_id,stage), INDEX idx_log_time (create_time)
);
CREATE TABLE IF NOT EXISTS operation_log (
 operation_id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_user_id BIGINT NOT NULL,
 operation_type VARCHAR(50) NOT NULL, target_type VARCHAR(30) NOT NULL, target_id BIGINT NOT NULL,
 task_id BIGINT, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
