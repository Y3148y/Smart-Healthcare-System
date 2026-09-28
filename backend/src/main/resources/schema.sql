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

CREATE TABLE IF NOT EXISTS sim_slot (
  doctor_id VARCHAR(64) NOT NULL,
  slot_date VARCHAR(10) NOT NULL,
  remaining INT NOT NULL,
  total INT NOT NULL,
  PRIMARY KEY (doctor_id, slot_date)
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
