CREATE TABLE Member (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255),
    phone_number VARCHAR(255),
    CONSTRAINT member_email_unique UNIQUE (email)
);

-- A previous WildFly instance has already reserved the block containing IDs 2 and 3.
CREATE SEQUENCE Member_SEQ START WITH 101 INCREMENT BY 50;

INSERT INTO Member(id, name, email, phone_number) VALUES
    (3, 'Zulu', 'zulu@example.test', '0555555555'),
    (0, 'Alpha', 'alpha@example.test', '0123456789'),
    (2, 'Alpha', 'other-alpha@example.test', '0333333333');
