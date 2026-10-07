CREATE TABLE IF NOT EXISTS triage_session (
  id VARCHAR(64) PRIMARY KEY,
  patient_id VARCHAR(128) NOT NULL,
  title VARCHAR(160) NOT NULL,
  preview VARCHAR(500) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS triage_message (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  FOREIGN KEY (session_id) REFERENCES triage_session(id)
);

CREATE TABLE IF NOT EXISTS triage_message_provenance (
  message_id VARCHAR(64) PRIMARY KEY,
  meta_json TEXT NOT NULL,
  FOREIGN KEY (message_id) REFERENCES triage_message(id)
);

CREATE TABLE IF NOT EXISTS triage_message_order (
  message_id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  sequence_number BIGINT NOT NULL,
  UNIQUE (session_id, sequence_number),
  FOREIGN KEY (message_id) REFERENCES triage_message(id),
  FOREIGN KEY (session_id) REFERENCES triage_session(id)
);

CREATE TABLE IF NOT EXISTS triage_assessment (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  version_number INT NOT NULL,
  result_json TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE (session_id, version_number),
  FOREIGN KEY (session_id) REFERENCES triage_session(id)
);

CREATE TABLE IF NOT EXISTS triage_assessment_anchor (
  assessment_id VARCHAR(64) PRIMARY KEY,
  assistant_message_id VARCHAR(64) NOT NULL,
  FOREIGN KEY (assessment_id) REFERENCES triage_assessment(id),
  FOREIGN KEY (assistant_message_id) REFERENCES triage_message(id)
);

CREATE TABLE IF NOT EXISTS triage_eligibility (
  session_id VARCHAR(64) PRIMARY KEY,
  confirmed_at TIMESTAMP NOT NULL,
  FOREIGN KEY (session_id) REFERENCES triage_session(id)
);

CREATE TABLE IF NOT EXISTS human_review_request (
  id VARCHAR(64) PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL UNIQUE,
  patient_id VARCHAR(128) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  FOREIGN KEY (session_id) REFERENCES triage_session(id)
);

CREATE TABLE IF NOT EXISTS agent_call_log (
  id VARCHAR(64) PRIMARY KEY,
  called_at TIMESTAMP NOT NULL,
  purpose VARCHAR(160) NOT NULL,
  actor VARCHAR(128) NOT NULL,
  model VARCHAR(160) NOT NULL,
  input_tokens INT NOT NULL,
  output_tokens INT NOT NULL,
  elapsed_ms BIGINT NOT NULL,
  success BOOLEAN NOT NULL,
  tools_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sim_slot (
  doctor_id VARCHAR(64) NOT NULL,
  slot_date VARCHAR(10) NOT NULL,
  remaining INT NOT NULL,
  total INT NOT NULL,
  PRIMARY KEY (doctor_id, slot_date)
);

CREATE TABLE IF NOT EXISTS catalog_department (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(80) NOT NULL UNIQUE,
  enabled BOOLEAN NOT NULL
);
CREATE TABLE IF NOT EXISTS catalog_doctor (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  title VARCHAR(80) NOT NULL,
  department_id VARCHAR(64) NOT NULL,
  enabled BOOLEAN NOT NULL,
  slot_date VARCHAR(10) NOT NULL,
  period VARCHAR(16) NOT NULL,
  fee INT NOT NULL,
  FOREIGN KEY (department_id) REFERENCES catalog_department(id)
);

CREATE TABLE IF NOT EXISTS sim_appointment (
  id VARCHAR(64) PRIMARY KEY,
  patient_id VARCHAR(128) NOT NULL,
  doctor_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(64) NOT NULL,
  doctor_json TEXT NOT NULL,
  status VARCHAR(32) NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  UNIQUE (patient_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS patient_profile (
  patient_id VARCHAR(128) PRIMARY KEY,
  display_name VARCHAR(80) NOT NULL,
  birth_date VARCHAR(10),
  allergies VARCHAR(1000) NOT NULL,
  medications VARCHAR(1000) NOT NULL,
  health_background VARCHAR(1000) NOT NULL,
  version_number BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_document (
  id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(160) NOT NULL,
  body LONGTEXT NOT NULL,
  source VARCHAR(4000) NOT NULL,
  status VARCHAR(32) NOT NULL,
  chunk_count INT NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_document_metadata (
  document_id VARCHAR(64) PRIMARY KEY,
  metadata_json LONGTEXT NOT NULL,
  FOREIGN KEY (document_id) REFERENCES knowledge_document(id)
);

CREATE TABLE IF NOT EXISTS review_access_audit (
  id VARCHAR(64) PRIMARY KEY,
  actor VARCHAR(128) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  outcome VARCHAR(16) NOT NULL,
  accessed_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_call_trace (
  call_id VARCHAR(64) PRIMARY KEY,
  trace_id VARCHAR(64) NOT NULL,
  FOREIGN KEY (call_id) REFERENCES agent_call_log(id)
);
CREATE INDEX IF NOT EXISTS idx_agent_call_trace_id ON agent_call_trace(trace_id);
