package db;

import exo.Direction;
import exo.Ground;

import java.sql.*;

/**
 * DBConnector: A helper class for connecting to and managing a MariaDB database.
 */
public class DBConnector {

    private static final String SERVER_URL = "jdbc:mariadb://localhost:3306";
    private static final String DB_NAME = "exo";
    private static final String USER = "root";
    private static final String PASSWORD = "password";

    private final String serverUrl;
    private final String dbName;
    private final String user;
    private final String password;

    public DBConnector() {
        this(SERVER_URL, DB_NAME, USER, PASSWORD);
    }

    public DBConnector(String serverUrl, String dbName, String user, String password) {
        this.serverUrl = serverUrl;
        this.dbName = dbName;
        this.user = user;
        this.password = password;
    }

    private Connection getDBConnection() throws SQLException {
        return DriverManager.getConnection(serverUrl + "/" + dbName, user, password);
    }

    public String findPlanetBySize(Integer width, Integer height) {
        String sql = "SELECT name FROM Planet WHERE width=? AND height=?";
        try (Connection conn = getDBConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, width);
            ps.setInt(2, height);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("name");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void saveRobot(String robotName, Direction dir, Integer posX, Integer posY,
                          Float temperature, Integer energy, String groundStationName) {
        String getGroundStationId = "SELECT groundstation_id FROM GroundStation WHERE name=?";
        String upsert =
                "INSERT INTO Robot(name,direction,positionX,positionY,temperature,energy,groundstation_id) " +
                        "VALUES (?,?,?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "direction=VALUES(direction), " +
                        "positionX=VALUES(positionX), " +
                        "positionY=VALUES(positionY), " +
                        "temperature=VALUES(temperature), " +
                        "energy=VALUES(energy), " +
                        "groundstation_id=VALUES(groundstation_id)";

        try (Connection conn = getDBConnection();
             PreparedStatement gsStmt = conn.prepareStatement(getGroundStationId);
             PreparedStatement ps = conn.prepareStatement(upsert)) {

            gsStmt.setString(1, groundStationName);
            ResultSet rs = gsStmt.executeQuery();
            if (!rs.next()) {
                System.err.println("Ground station not found: " + groundStationName);
                return;
            }
            int groundstationId = rs.getInt("groundstation_id");

            ps.setString(1, robotName);
            ps.setString(2, dir == null ? "NORTH" : dir.name());
            ps.setInt(3, posX);
            ps.setInt(4, posY);
            ps.setFloat(5, temperature);
            ps.setInt(6, energy);
            ps.setInt(7, groundstationId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveFieldMeasurement(String robotName, int planetWidth, int planetHeight,
                                     int posX, int posY, Ground groundType, float temperature) {
        String getPlanetId = "SELECT planet_id FROM Planet WHERE width=? AND height=?";
        String getRobotId = "SELECT robot_id FROM Robot WHERE name=?";
        String fieldUpsert =
                "INSERT INTO Field(planet_id, positionX, positionY, ground, temperature) " +
                        "VALUES(?,?,?,?,?) " +
                        "ON DUPLICATE KEY UPDATE ground=VALUES(ground), temperature=VALUES(temperature)";
        String measurementInsert =
                "INSERT INTO Measurement(robot_id, field_id) " +
                        "VALUES(?, (SELECT field_id FROM Field WHERE planet_id=? AND positionX=? AND positionY=?))";

        try (Connection conn = getDBConnection();
             PreparedStatement planetStmt = conn.prepareStatement(getPlanetId);
             PreparedStatement robotStmt = conn.prepareStatement(getRobotId);
             PreparedStatement fieldStmt = conn.prepareStatement(fieldUpsert);
             PreparedStatement measurementStmt = conn.prepareStatement(measurementInsert)) {

            planetStmt.setInt(1, planetWidth);
            planetStmt.setInt(2, planetHeight);
            ResultSet planetRs = planetStmt.executeQuery();
            if (!planetRs.next()) return;
            int planetId = planetRs.getInt("planet_id");

            robotStmt.setString(1, robotName);
            ResultSet robotRs = robotStmt.executeQuery();
            if (!robotRs.next()) return;
            int robotId = robotRs.getInt("robot_id");

            fieldStmt.setInt(1, planetId);
            fieldStmt.setInt(2, posX);
            fieldStmt.setInt(3, posY);
            fieldStmt.setString(4, groundType.name());
            fieldStmt.setFloat(5, temperature);
            fieldStmt.executeUpdate();

            measurementStmt.setInt(1, robotId);
            measurementStmt.setInt(2, planetId);
            measurementStmt.setInt(3, posX);
            measurementStmt.setInt(4, posY);
            measurementStmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
