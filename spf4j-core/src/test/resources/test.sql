DROP TABLE HEARTBEATS;
DROP TABLE PERMITS_BY_OWNER;
DROP TABLE SEMAPHORES;


CREATE TABLE HEARTBEATS (
   OWNER VARCHAR(255) NOT NULL,
   INTERVAL_MILLIS bigint(20) NOT NULL,
   LAST_HEARTBEAT_INSTANT_MILLIS bigint(20) NOT NULL,
   PRIMARY KEY (OWNER),
   UNIQUE KEY HEARTBEATS_PK (OWNER)
);

CREATE TABLE SEMAPHORES (
  SEMAPHORE_NAME VARCHAR(255) NOT NULL,
  AVAILABLE_PERMITS BIGINT(16) NOT NULL,
  TOTAL_PERMITS BIGINT(16) NOT NULL,
  LAST_UPDATED_BY VARCHAR(255) NOT NULL,
  LAST_UPDATED_AT BIGINT NOT NULL,
  PRIMARY KEY (SEMAPHORE_NAME),
  UNIQUE KEY SEMAPHORE_NAME_PK (SEMAPHORE_NAME)
);

CREATE TABLE PERMITS_BY_OWNER (
   SEMAPHORE_NAME VARCHAR(255) NOT NULL,
   OWNER VARCHAR(255) NOT NULL,
   PERMITS BIGINT(16) NOT NULL,
   LAST_UPDATED_AT BIGINT NOT NULL,
   PRIMARY KEY (SEMAPHORE_NAME, OWNER),
   UNIQUE KEY PERMITS_BY_OWNER_PK (SEMAPHORE_NAME, OWNER),
   FOREIGN KEY (SEMAPHORE_NAME) REFERENCES SEMAPHORES(SEMAPHORE_NAME)
);

insert into SEMAPHORES (SEMAPHORE_NAME, AVAILABLE_RESERVATIONS, MAX_RESERVATIONS, LAST_UPDATED_BY, LAST_UPDATED_AT)
VALUES ('test_sem', 1, 3, 'Z', 0);
insert into SEMAPHORES (SEMAPHORE_NAME, AVAILABLE_RESERVATIONS, MAX_RESERVATIONS, LAST_UPDATED_BY, LAST_UPDATED_AT)
VALUES ('test_sem2', 1, 2, 'Z', 0);

insert into PERMITS_BY_OWNER (SEMAPHORE_NAME, OWNER, PERMITS, LAST_UPDATED_AT)
VALUES ('test_sem', 'Z', 1, 0);
insert into PERMITS_BY_OWNER (SEMAPHORE_NAME, OWNER, PERMITS, LAST_UPDATED_AT)
VALUES ('test_sem', 'A', 1, 0);

insert into HEARTBEATS (OWNER, INTERVAL_MILLIS, LAST_HEARTBEAT_INSTANT_MILLIS)
VALUES ('A', 10000, 0);

select * from SEMAPHORES;

select * from RESERVATIONS_BY_OWNER;

SELECT SUM(PERMITS) FROM RESERVATIONS_BY_OWNER RO
 WHERE RO.SEMAPHORE_NAME = 'test_sem' AND  NOT EXISTS (select H.OWNER from HEARTBEATS H where H.OWNER = RO.OWNER);

SELECT OWNER, PERMITS FROM PERMITS_BY_OWNER RO
 WHERE RO.SEMAPHORE_NAME = 'test_sem' AND  NOT EXISTS (select H.OWNER from HEARTBEATS H where H.OWNER = RO.OWNER);

DELETE FROM PERMITS_BY_OWNER RO
 WHERE RO.SEMAPHORE_NAME = 'test_sem' AND PERMITS = 0 AND NOT EXISTS (select H.OWNER from HEARTBEATS H where H.OWNER = RO.OWNER);


UPDATE RESERVATIONS_BY_OWNER SET RESERVATIONS = RESERVATIONS + ?, LAST_UPDATED_AT = ?
 WHERE OWNER = ? AND SEMAPHORE = ?


UPDATE SEMPATHORES SET RESERVATIONS = RESERVATIONS + ?,
            AVAILABLE_RESERVATIONS = AVAILABLE_RESERVATIONS  - 1, LAST_UPDATED_AT  = ? WHERE "
            + availableReservationsColumn + " < " + semTableDesc.getMaxReservationsColumn();