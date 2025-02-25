CREATE TABLE GroundStation (
    groundstation_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE Planet (
    planet_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    width INT NOT NULL,
    height INT NOT NULL
);

CREATE TABLE Field (
    field_id INT AUTO_INCREMENT PRIMARY KEY,
    planet_id INT NOT NULL,
    positionX INT NOT NULL,
    positionY INT NOT NULL,
    ground ENUM ('NICHTS', 'SAND', 'GEROELL', 'FELS', 'WASSER', 'PFLANZEN', 'MORAST', 'LAVA') NOT NULL,
    temperature DECIMAL(9,6),
    FOREIGN KEY (planet_id) REFERENCES Planet (planet_id)
);

CREATE TABLE Robot (
    robot_id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    direction ENUM ('NORTH', 'EAST', 'SOUTH', 'WEST') NOT NULL,
    positionX INT NOT NULL,
    positionY INT NOT NULL,
    groundstation_id INT NOT NULL,
    FOREIGN KEY (groundstation_id) REFERENCES GroundStation (groundstation_id)
);

CREATE TABLE Measurement (
    measurement_id INT AUTO_INCREMENT PRIMARY KEY,
    robot_id INT NOT NULL,
    field_id INT NOT NULL,
    measure_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (robot_id) REFERENCES Robot (robot_id),
    FOREIGN KEY (field_id) REFERENCES Field (field_id)
);

-- Insert dummy data into GroundStation
INSERT INTO GroundStation (name) VALUES
('Alpha Station'),
('Beta Outpost'),
('Gamma Base');

-- Insert dummy data into Planet
INSERT INTO Planet (name, width, height) VALUES
('Terra Prime', 10, 10),
('Xylo 7', 15, 12),
('Nebula 9', 8, 8);

-- Insert dummy data into Field
INSERT INTO Field (planet_id, positionX, positionY, ground, temperature) VALUES
(1, 0, 0, 'SAND', 25.5),
(1, 1, 0, 'FELS', 28.0),
(1, 0, 1, 'WASSER', 15.2),
(1, 2, 2, 'PFLANZEN', 22.8),
(1, 4, 0, 'GEROELL', 30.1),
(1, 5, 5, 'LAVA', 100.0),
(1, 3, 3, 'MORAST', 10.0),
(1, 9, 9, 'NICHTS', -10.0),
(1, 3, 5, 'SAND', 35.0),
(1, 5, 6, 'SAND', 36.0),
(1, 5, 7, 'SAND', 37.0),
(1, 6, 5, 'SAND', 38.0),
(1, 7, 5, 'SAND', 39.0);

-- Insert dummy data into Robot
INSERT INTO Robot (name, direction, positionX, positionY, groundstation_id) VALUES
('Rover 1', 'NORTH', 1, 1, 1),
('Explorer 2', 'EAST', 5, 5, 2),
('Surveyor 3', 'SOUTH', 3, 3, 3),
('Probe 4', 'WEST', 0, 0, 1);

-- Insert dummy data into Measurement
INSERT INTO Measurement (robot_id, field_id) VALUES
(1, 1),
(1, 2),
(2, 5),
(3, 7),
(4, 8),
(1, 9),
(1, 10),
(1, 11),
(1, 12),
(1, 13);
