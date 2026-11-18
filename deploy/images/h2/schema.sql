-- Same Member mapping and pooled sequence as Hibernate 6 in the baseline.
CREATE TABLE IF NOT EXISTS Member (id BIGINT NOT NULL PRIMARY KEY, email VARCHAR(255), name VARCHAR(255), phone_number VARCHAR(255), CONSTRAINT member_email_unique UNIQUE(email));
CREATE SEQUENCE IF NOT EXISTS Member_SEQ START WITH 1 INCREMENT BY 50;
CREATE TABLE IF NOT EXISTS B1_SCHEMA_VERSION (version INTEGER PRIMARY KEY);
INSERT INTO Member(id,name,email,phone_number)
  SELECT 0,'John Smith','john.smith@mailinator.com','2125551212'
  WHERE NOT EXISTS (SELECT 1 FROM B1_SCHEMA_VERSION WHERE version = 1)
    AND NOT EXISTS (SELECT 1 FROM Member WHERE id = 0);
MERGE INTO B1_SCHEMA_VERSION KEY(version) VALUES(1);
