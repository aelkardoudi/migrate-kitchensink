CREATE TABLE Member (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255),
    phone_number VARCHAR(255),
    CONSTRAINT member_email_unique UNIQUE (email)
);

INSERT INTO Member(id, name, email, phone_number) VALUES
    (3, 'Zulu', 'zulu@example.test', '0555555555'),
    (0, 'Alpha', 'alpha@example.test', '0123456789'),
    (2, 'Alpha', 'other-alpha@example.test', '0333333333');
