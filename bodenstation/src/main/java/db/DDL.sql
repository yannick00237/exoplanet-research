CREATE DATABASE IF NOT EXISTS exo;
USE exo;

CREATE TABLE GroundStation
(
    groundstation_id SERIAL PRIMARY KEY,
    name             VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE Planet
(
    planet_id SERIAL PRIMARY KEY,
    name      VARCHAR(100) NOT NULL UNIQUE,
    width     INT          NOT NULL,
    height    INT          NOT NULL
);

CREATE TABLE Field
(
    field_id    SERIAL PRIMARY KEY,
    planet_id   INT                 NOT NULL,
    positionX   INT                 NOT NULL,
    positionY   INT                 NOT NULL,
    ground      ENUM ('NICHTS','SAND','GEROELL','FELS','WASSER',
        'PFLANZEN','MORAST','LAVA') NOT NULL,
    temperature DECIMAL(9, 6),
    FOREIGN KEY (planet_id) REFERENCES Planet (planet_id)
);

CREATE TABLE Robot
(
    robot_id         SERIAL PRIMARY KEY,
    name             VARCHAR(100) NOT NULL UNIQUE,
    direction        ENUM ('NORTH','EAST','SOUTH','WEST'),
    positionX        INT,
    positionY        INT,
    temperature      DECIMAL(9, 6),
    energy           INT DEFAULT 100,
    groundstation_id INT          NOT NULL,
    FOREIGN KEY (groundstation_id) REFERENCES GroundStation (groundstation_id)
);

CREATE TABLE Measurement
(
    measurement_id SERIAL PRIMARY KEY,
    robot_id       INT NOT NULL,
    field_id       INT NOT NULL,
    measure_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (robot_id) REFERENCES Robot (robot_id),
    FOREIGN KEY (field_id) REFERENCES Field (field_id)
);

-- DML to populate Planet table with known sizes
INSERT INTO Planet(name, width, height)
VALUES ('Default', 10, 16),
       ('Jupiter Io', 20, 15),
       ('Pandora', 40, 25);

INSERT INTO GroundStation(name)
VALUES ('Titan Control Center'),
       ('Atlas Research Hub'),
       ('Orion Base');
