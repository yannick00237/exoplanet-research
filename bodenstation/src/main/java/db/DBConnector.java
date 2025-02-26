package db;

import exo.Direction;

import java.sql.*;

/**
 * DBConnector: Provides helper functions for saving robots,
 * measurements, retrieving planet info, etc.
 * <p>
 * NOTE: This is just a skeleton. Connection details & real queries
 * need to be adapted once the DB is configured.
 */
public class DBConnector {

    private String url;
    private String user;
    private String password;

    public DBConnector(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        // e.g. "jdbc:postgresql://localhost:5432/exoplanet"
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Finds a planet by its width and height. Returns the name if found, else null.
     */
    public String findPlanetBySize(Integer width, Integer height) {
        String sql = "SELECT name FROM Planet WHERE width=? AND height=?";
        try (Connection conn = getConnection();
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

    /**
     * Saves or updates the robot data (position, direction, energy, temp).
     * If not existing, you might do an INSERT, else UPDATE. This is only a skeleton.
     */
    public void saveRobot(String robotName, Direction dir, Integer posX, Integer posY, Float temp, Integer energy) {
        // Example approach: check if robot exists => update or insert
        String sqlInsertOrUpdate = "INSERT INTO Robot(name,direction,positionX,positionY,temperature,energy,groundstation_id) "
                + "VALUES(?,?,?,?,?,?,1) "
                + "ON CONFLICT (name) DO UPDATE SET "
                + "direction=EXCLUDED.direction, positionX=EXCLUDED.positionX, positionY=EXCLUDED.positionY, "
                + "temperature=EXCLUDED.temperature, energy=EXCLUDED.energy";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlInsertOrUpdate)) {
            ps.setString(1, robotName);
            ps.setString(2, dir.name());
            ps.setInt(3, posX);
            ps.setInt(4, posY);
            ps.setFloat(5, temp == null ? 20f : temp);
            ps.setInt(6, energy == null ? 100 : energy);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves measurement for a field. If the field doesn't exist, create it; else update. Then create a Measurement record.
     */
    public void saveFieldMeasurement(String planetName, Integer width, Integer height,
                                     Integer fieldX, Integer fieldY, String groundType, Float temperature,
                                     String robotName) {
        // 1) find planet_id by name or size
        // 2) insert or update field
        // 3) find or create robot
        // 4) insert measurement
        // SKELETON => user must fill with real logic
    }

    // Add additional helper methods as needed...
}
