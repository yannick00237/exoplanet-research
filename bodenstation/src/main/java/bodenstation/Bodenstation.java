package bodenstation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import exo.Direction;
import exo.Ground;
import exo.Measure;
import exo.Position;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enumeration of all known ExoPlanet commands
 */
enum ExoPlanetCmd {
    ORBIT, INIT, LAND, LANDED,
    SCAN, SCANED,
    MOVE, MOVED,
    MVSCAN, MVSCANED,
    ROTATE, ROTATED,
    CRASHED, EXIT, ERROR,
    GETPOS, POS,
    CHARGE, CHARGED,
    STATUS,
    UNKNOWN
    // Note: "partstatus" isn't in the official protocol; we omit it.
}

/**
 * Holds basic planet info: name, width, and height
 */
class Planet {
    private String name;
    private int width;
    private int height;

    public Planet(String name, int width, int height) {
        this.name = name;
        this.width = width;
        this.height = height;
    }

    public String getName() {
        return name;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
    }
}

/**
 * Helper class for converting to/from JSON messages
 * and building the known ExoPlanet commands.
 */
class ExoPlanetProtocol {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    public static Map<String, Object> fromJson(String json) {
        if (json == null) return null;
        return GSON.fromJson(json, MAP_TYPE);
    }

    public static String toJson(Map<String, Object> map) {
        return GSON.toJson(map);
    }

    public static ExoPlanetCmd getCmd(Map<String, Object> msg) {
        if (msg == null) return ExoPlanetCmd.UNKNOWN;
        Object cmdObj = msg.get("CMD");
        if (!(cmdObj instanceof String)) return ExoPlanetCmd.UNKNOWN;
        String cmdStr = ((String) cmdObj).toUpperCase(Locale.ROOT);
        try {
            return ExoPlanetCmd.valueOf(cmdStr);
        } catch (Exception e) {
            return ExoPlanetCmd.UNKNOWN;
        }
    }

    // Basic commands
    public static String buildOrbitCmd(String robotName) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "orbit");
        cmd.put("NAME", robotName);
        return toJson(cmd);
    }

    public static String buildLandCmd(int x, int y, Direction dir) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "land");
        Map<String, Object> pos = new HashMap<>();
        pos.put("X", x);
        pos.put("Y", y);
        pos.put("DIRECTION", dir.name());
        cmd.put("POSITION", pos);
        return toJson(cmd);
    }

    public static String buildMoveCmd() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "move");
        return toJson(cmd);
    }

    public static String buildScanCmd() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "scan");
        return toJson(cmd);
    }

    public static String buildMvScanCmd() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "mvscan");
        return toJson(cmd);
    }

    public static String buildRotateCmd(String rotation) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "rotate");
        cmd.put("ROTATION", rotation);  // "LEFT"/"RIGHT"
        return toJson(cmd);
    }

    public static String buildChargeCmd(int duration) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "charge");
        cmd.put("DURATION", duration);
        return toJson(cmd);
    }

    public static String buildGetPosCmd() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "getpos");
        return toJson(cmd);
    }
}

/**
 * Main Bodenstation class
 */
public class Bodenstation {
    private static final int DEFAULT_WIDTH = 10;
    private static final int DEFAULT_HEIGHT = 10;
    private static final int SOCKET_TIMEOUT = 2000;

    // All active RobotSession threads
    private final Map<String, RobotSession> sessions = new ConcurrentHashMap<>();
    // Holds which fields have been measured
    private final Map<Position, Measure> exploredFields = new ConcurrentHashMap<>();
    // Current positions of each robot
    private final Map<String, Position> robotPositions = new ConcurrentHashMap<>();

    // Planet data
    private Planet planet = new Planet("DefaultPlanet", DEFAULT_WIDTH, DEFAULT_HEIGHT);

    private ServerAcceptor serverAcceptor;
    private ConsoleUI consoleUI;

    public static void main(String[] args) {
        Bodenstation station = new Bodenstation();
        station.start(9000);
    }

    public void start(int port) {
        System.out.println("Bodenstation starting on port " + port + "...");
        serverAcceptor = new ServerAcceptor(port, this);
        serverAcceptor.start();

        consoleUI = new ConsoleUI(this);
        consoleUI.start();
    }

    // ------- Session management -------
    public synchronized void registerSession(String robotName, RobotSession session) {
        sessions.put(robotName, session);
    }

    public synchronized void unregisterSession(String robotName) {
        sessions.remove(robotName);
        robotPositions.remove(robotName);
    }

    public Map<String, RobotSession> getSessions() {
        return sessions;
    }

    // ------- Planet data -------
    public Planet getPlanet() {
        return planet;
    }

    public synchronized void setPlanetSize(int w, int h) {
        planet.setSize(w, h);
    }

    // ------- Field/Position data -------
    public Map<Position, Measure> getExploredFields() {
        return exploredFields;
    }

    public Map<String, Position> getRobotPositions() {
        return robotPositions;
    }

    public synchronized boolean allFieldsExplored() {
        // Simple approach: if we've measured w*h fields, we assume fully explored
        return exploredFields.size() >= (planet.getWidth() * planet.getHeight());
    }

    public synchronized void setRobotPosition(String robotName, int x, int y, Direction dir) {
        robotPositions.put(robotName, new Position(x, y, dir));
    }

    public synchronized Position getRobotPosition(String robotName) {
        return robotPositions.get(robotName);
    }

    /**
     * Store a newly measured field in exploredFields.
     */
    public synchronized void updateExploredField(int x, int y, Measure m) {
        // direction optional
        exploredFields.put(new Position(x, y, Direction.NORTH), m);
    }

    public void shutdown() {
        System.out.println("Bodenstation shutting down...");
        if (serverAcceptor != null) {
            serverAcceptor.interrupt();
            try {
                serverAcceptor.closeServer();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for (RobotSession rs : sessions.values()) {
            rs.interrupt();
            rs.closeSocket();
        }
        if (consoleUI != null) {
            consoleUI.interrupt();
        }
        System.out.println("Goodbye.");
        System.exit(0);
    }
}

/**
 * Accepts new connections from RemoteRobots
 */
class ServerAcceptor extends Thread {
    private static final int ACCEPT_TIMEOUT = 2000;
    private ServerSocket serverSocket;
    private final int port;
    private final Bodenstation stationRef;

    public ServerAcceptor(int port, Bodenstation stationRef) {
        this.port = port;
        this.stationRef = stationRef;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(ACCEPT_TIMEOUT);
            System.out.println("[ServerAcceptor] Listening on port " + port);

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
                    String robotName = "Robot-" + UUID.randomUUID().toString().substring(0, 8);
                    RobotSession rs = new RobotSession(client, robotName, stationRef);
                    stationRef.registerSession(robotName, rs);
                    rs.start();
                    System.out.println("New Robot connected => " + robotName);
                } catch (SocketTimeoutException e) {
                    // no connection => retry
                }
            }
        } catch (IOException e) {
            System.out.println("Could not listen on port " + port + ": " + e);
        }
        System.out.println("[ServerAcceptor] ends");
    }

    public void closeServer() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
    }
}

/**
 * A RobotSession represents the server-side perspective of a single RemoteRobot.
 * It receives commands from the exoPlanet (via the Robot) and also implements
 * an autopilot that can proactively send commands to the Robot.
 */
class RobotSession extends Thread {
    // Sleep intervals
    private static final int ACTION_SLEEP_MS = 500;
    private static final int CHARGE_DURATION_SECONDS = 5;
    private static final float ENERGY_WARN_THRESHOLD = 20f;

    private final Socket socket;
    private final String robotName;
    private final Bodenstation stationRef;

    private PrintWriter outWriter;
    private BufferedReader inReader;

    private volatile boolean isCrashed = false;
    private volatile boolean isAutonomous = false;
    private Thread autoPilotThread;

    // The robot's internal status (temp, energy, etc.) updated via status messages only
    private float robotTemp = 20f;
    private float robotEnergy = 100f;

    // Some advanced statuses
    // e.g. { "MOTOR": 100, "ROT_R": 90, "COOLER": 100, etc. }
    private final Map<String, Integer> partStatusMap = new HashMap<>();

    public RobotSession(Socket clientSocket, String robotName, Bodenstation stationRef) {
        this.socket = clientSocket;
        this.robotName = robotName;
        this.stationRef = stationRef;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(2000); // 2s read timeout
            outWriter = new PrintWriter(socket.getOutputStream(), true);
            inReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // "orbit" => the planet acknowledges
            sendJson(ExoPlanetProtocol.buildOrbitCmd(robotName));

            // Main read loop: handle responses & status updates
            while (!Thread.currentThread().isInterrupted()) {
                String line;
                try {
                    line = inReader.readLine();
                } catch (SocketTimeoutException e) {
                    // Timeout => check for interrupt => continue
                    if (Thread.currentThread().isInterrupted()) break;
                    continue;
                }
                if (line == null) {
                    System.out.println("[" + robotName + "] Connection closed.");
                    break;
                }
                // Process JSON
                Map<String, Object> msgMap = ExoPlanetProtocol.fromJson(line);
                ExoPlanetCmd cmd = ExoPlanetProtocol.getCmd(msgMap);
                handleIncoming(cmd, msgMap);

                if (isCrashed) {
                    break; // no further actions
                }
            }
        } catch (IOException e) {
            System.out.println("[" + robotName + "] Connection lost: " + e);
        } finally {
            stopAutoPilot();
            closeSocket();
            stationRef.unregisterSession(robotName);
            System.out.println("[" + robotName + "] Session ended.");
        }
    }

    /**
     * Send a JSON command to the RemoteRobot.
     */
    public void sendJson(String jsonCmd) {
        if (outWriter != null) {
            outWriter.println(jsonCmd);
        }
    }

    /**
     * Turn on/off autopilot.
     */
    public synchronized void setAutonomous(boolean auto) {
        if (isCrashed) {
            System.out.println("[" + robotName + "] cannot setAutonomous => Robot crashed.");
            return;
        }
        this.isAutonomous = auto;
        System.out.println("[" + robotName + "] Autonomy => " + auto);
        if (auto) {
            startAutoPilot();
        } else {
            stopAutoPilot();
        }
    }

    public boolean isAutonomous() {
        return isAutonomous;
    }

    public String getRobotName() {
        return robotName;
    }

    private void startAutoPilot() {
        if (autoPilotThread != null && autoPilotThread.isAlive()) {
            autoPilotThread.interrupt();
        }
        autoPilotThread = new Thread(this::runAutoPilot, "AutoPilot-" + robotName);
        autoPilotThread.start();
    }

    private void stopAutoPilot() {
        if (autoPilotThread != null) {
            autoPilotThread.interrupt();
            autoPilotThread = null;
        }
    }

    // ------------------------------------
    // AutoPilot BFS logic
    // ------------------------------------
    private void runAutoPilot() {
        System.out.println("[" + robotName + "] Autopilot started.");
        try {
            // Wait for "init" => planet size known
            while (!Thread.currentThread().isInterrupted()) {
                int w = stationRef.getPlanet().getWidth();
                int h = stationRef.getPlanet().getHeight();
                if (w > 0 && h > 0) break;
                Thread.sleep(ACTION_SLEEP_MS);
            }

            // Land at (0,0) if free; else pick another free cell
            Position landPos = findFreeLandingSpot();
            System.out.println("[" + robotName + "] Attempting to land at: " + landPos);
            sendJson(ExoPlanetProtocol.buildLandCmd(landPos.getX(), landPos.getY(), landPos.getDir()));
            Thread.sleep(ACTION_SLEEP_MS);

            // BFS all fields
            Set<String> visited = new HashSet<>();
            Queue<int[]> frontier = new LinkedList<>();

            int w = stationRef.getPlanet().getWidth();
            int h = stationRef.getPlanet().getHeight();
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    frontier.offer(new int[]{xx, yy});
                }
            }

            while (!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                // If everything is explored, exit
                if (stationRef.allFieldsExplored()) {
                    System.out.println("[" + robotName + "] Planet fully explored => exit");
                    sendJson("{\"CMD\":\"exit\"}");
                    break;
                }
                if (frontier.isEmpty()) {
                    System.out.println("[" + robotName + "] BFS done => exit");
                    sendJson("{\"CMD\":\"exit\"}");
                    break;
                }
                int[] target = frontier.poll();
                String key = target[0] + "_" + target[1];
                if (visited.contains(key)) continue;
                visited.add(key);

                // Move towards that field
                moveRobotStepByStep(target[0], target[1]);

                // Because we can't do any local energy updates,
                // we rely on "STATUS" or "CHARGED" updates from the planet.
                // => If we see a WARN_LOW_ENERGY or something, we handle it.

                // Attempt an mvscan => move + scan in one command
                sendJson(ExoPlanetProtocol.buildMvScanCmd());
                Thread.sleep(ACTION_SLEEP_MS);
            }
        } catch (InterruptedException e) {
            System.out.println("[" + robotName + "] AutoPilot interrupted.");
        }
        System.out.println("[" + robotName + "] Autopilot ended.");
    }

    /**
     * Find a free spot for landing. For simplicity, try (0,0),
     * if it's occupied, search next best. If none found, fallback to (0,0).
     */
    private Position findFreeLandingSpot() {
        // If (0,0) is free => return it
        // else check other positions
        // e.g. dir = NORTH by default
        for (int y = 0; y < stationRef.getPlanet().getHeight(); y++) {
            for (int x = 0; x < stationRef.getPlanet().getWidth(); x++) {
                // If no robot has that (x,y)
                if (!isCellOccupied(x, y)) {
                    return new Position(x, y, Direction.NORTH);
                }
            }
        }
        return new Position(0, 0, Direction.NORTH);
    }

    private boolean isCellOccupied(int x, int y) {
        for (Position pos : stationRef.getRobotPositions().values()) {
            if (pos.getX() == x && pos.getY() == y) {
                return true;
            }
        }
        return false;
    }

    private void moveRobotStepByStep(int targetX, int targetY) throws InterruptedException {
        // Basic approach: rotate -> move -> rotate -> move, etc.
        Position currentPos = stationRef.getRobotPosition(robotName);
        if (currentPos == null) {
            // Fallback
            currentPos = new Position(0, 0, Direction.NORTH);
            stationRef.setRobotPosition(robotName, 0, 0, Direction.NORTH);
        }
        while (!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
            int dx = targetX - currentPos.getX();
            int dy = targetY - currentPos.getY();
            if (dx == 0 && dy == 0) {
                // Reached target
                break;
            }
            // Determine next step
            Direction neededDir = null;
            if (Math.abs(dx) > 0) {
                neededDir = dx > 0 ? Direction.EAST : Direction.WEST;
            } else if (Math.abs(dy) > 0) {
                neededDir = dy > 0 ? Direction.SOUTH : Direction.NORTH;
            }
            if (neededDir != null && currentPos.getDir() != neededDir) {
                // rotate
                String rotation = computeRotation(currentPos.getDir(), neededDir);
                sendJson(ExoPlanetProtocol.buildRotateCmd(rotation));
                Thread.sleep(ACTION_SLEEP_MS);
            } else {
                // check collision
                if (nextCellOccupied(currentPos)) {
                    // If stuck in mud or something, we might rotate to get out
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Math.random() > 0.5 ? "LEFT" : "RIGHT"));
                    Thread.sleep(ACTION_SLEEP_MS);
                    continue;
                }
                // Send "move"
                sendJson(ExoPlanetProtocol.buildMoveCmd());
                Thread.sleep(ACTION_SLEEP_MS * 2);
            }
            currentPos = stationRef.getRobotPosition(robotName);
            if (currentPos == null) break;
        }
    }

    private boolean nextCellOccupied(Position pos) {
        // Check the cell in the direction of pos.dir
        int x = pos.getX();
        int y = pos.getY();
        switch (pos.getDir()) {
            case NORTH:
                y--;
                break;
            case EAST:
                x++;
                break;
            case SOUTH:
                y++;
                break;
            case WEST:
                x--;
                break;
        }
        for (Map.Entry<String, Position> e : stationRef.getRobotPositions().entrySet()) {
            if (e.getKey().equals(robotName)) continue;
            Position other = e.getValue();
            if (other.getX() == x && other.getY() == y) {
                return true;
            }
        }
        return false;
    }

    private String computeRotation(Direction current, Direction target) {
        List<Direction> dirs = Arrays.asList(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
        int ci = dirs.indexOf(current);
        int ti = dirs.indexOf(target);
        int diff = ti - ci;
        if (diff < 0) diff += 4;
        // if diff=1 => RIGHT, diff=3 => LEFT
        if (diff == 1) return "RIGHT";
        if (diff == 3) return "LEFT";
        // if diff=2 => 180 turn => we do "RIGHT" twice in BFS approach
        return "RIGHT";
    }

    // ------------------------------------------------------
    // handleIncoming: interpret ExoPlanet messages
    // ------------------------------------------------------
    private void handleIncoming(ExoPlanetCmd cmd, Map<String, Object> msg) {
        switch (cmd) {
            case INIT:
                handleInit(msg);
                break;
            case LANDED:
                handleLanded(msg);
                break;
            case SCANED:
            case MVSCANED:
                handleScaned(msg);
                break;
            case MOVED:
                handleMoved(msg);
                break;
            case ROTATED:
                handleRotated(msg);
                break;
            case CHARGED:
                handleCharged(msg);
                break;
            case STATUS:
                handleStatus(msg);
                break;
            case CRASHED:
                System.out.println("[" + robotName + "] CRASHED => session ends.");
                isCrashed = true;
                break;
            case ERROR:
                System.out.println("[" + robotName + "] ERROR => " + msg);
                break;
            case EXIT:
                System.out.println("[" + robotName + "] Robot requested exit => session ends.");
                isCrashed = true;
                break;
            default:
                System.out.println("[" + robotName + "] Unhandled CMD => " + cmd + " => " + msg);
        }
    }

    private void handleInit(Map<String, Object> msg) {
        // {"CMD":"init","SIZE":{"WIDTH":20,"HEIGHT":15}}
        Map<String, Object> sizeMap = (Map<String, Object>) msg.get("SIZE");
        if (sizeMap != null) {
            int w = asInt(sizeMap.get("WIDTH"), stationRef.getPlanet().getWidth());
            int h = asInt(sizeMap.get("HEIGHT"), stationRef.getPlanet().getHeight());
            stationRef.setPlanetSize(w, h);
            System.out.println("[" + robotName + "] Planet => width=" + w + ", height=" + h);
        }
    }

    private void handleLanded(Map<String, Object> msg) {
        // {"CMD":"landed","MEASURE":{"GROUND":"SAND","TEMP":22.5}}
        Map<String, Object> measure = (Map<String, Object>) msg.get("MEASURE");
        if (measure != null) {
            handleMeasure(measure, getPosX(), getPosY());
        }
    }

    private void handleScaned(Map<String, Object> msg) {
        // {"CMD":"scaned","MEASURE":{"GROUND":"FELS","TEMP":12.0}}
        // or {"CMD":"mvscaned","MEASURE":...,"POSITION":...}
        Map<String, Object> measure = (Map<String, Object>) msg.get("MEASURE");
        if (measure != null) {
            handleMeasure(measure, getPosX(), getPosY());
        }
        // If "mvscaned" also has a position update
        if (msg.containsKey("POSITION")) {
            Map<String, Object> posMap = (Map<String, Object>) msg.get("POSITION");
            updatePositionFromMap(posMap);
        }
    }

    private void handleMoved(Map<String, Object> msg) {
        // {"CMD":"moved","POSITION":{"X":..., "Y":..., "DIRECTION":"..."}}
        Map<String, Object> posMap = (Map<String, Object>) msg.get("POSITION");
        if (posMap != null) {
            updatePositionFromMap(posMap);
        }
    }

    private void handleRotated(Map<String, Object> msg) {
        // {"CMD":"rotated","DIRECTION":"EAST"}
        if (msg.containsKey("DIRECTION")) {
            String dStr = (String) msg.get("DIRECTION");
            Direction dir = parseDirection(dStr, Direction.NORTH);
            Position p = stationRef.getRobotPosition(robotName);
            if (p != null) {
                p.setDir(dir);
            }
        }
    }

    private void handleCharged(Map<String, Object> msg) {
        // {"CMD":"charged","STATUS":{"TEMP":..., "ENERGY":..., "MESSAGE":"..."}}
        Map<String, Object> st = (Map<String, Object>) msg.get("STATUS");
        if (st != null) handleStatusData(st);
        System.out.println("[" + robotName + "] Charge completed => new status: temp=" + robotTemp + ", energy=" + robotEnergy);
    }

    private void handleStatus(Map<String, Object> msg) {
        // {"CMD":"status","STATUS":{"TEMP":..., "ENERGY":..., "MESSAGE":"STUCK_IN_MUD|MOTOR=50"}}
        Map<String, Object> st = (Map<String, Object>) msg.get("STATUS");
        if (st != null) handleStatusData(st);
    }

    /**
     * Parse the "STATUS" data => update robotTemp, robotEnergy, parse warnings.
     */
    private void handleStatusData(Map<String, Object> stMap) {
        float temp = asFloat(stMap.get("TEMP"), robotTemp);
        float energy = asFloat(stMap.get("ENERGY"), robotEnergy);
        robotTemp = temp;
        robotEnergy = energy;

        // message can contain e.g. "STUCK_IN_MUD|WARN_LOW_ENERGY|ROT_R=50"
        String messages = (String) stMap.get("MESSAGE");
        if (messages == null || messages.isEmpty()) return;

        String[] tokens = messages.split("\\|");
        for (String token : tokens) {
            token = token.trim();
            if (token.contains("=")) {
                // e.g. "MOTOR=90"
                parsePartStatus(token);
            } else {
                // e.g. "STUCK_IN_MUD", "MOVE_STOP", "WARN_LOW_ENERGY", etc.
                handleStatusEvent(token);
            }
        }
    }

    private void parsePartStatus(String eq) {
        // e.g. "ROT_R=70"
        String[] parts = eq.split("=");
        if (parts.length == 2) {
            String comp = parts[0].trim();
            int val = asInt(parts[1], 100);
            partStatusMap.put(comp, val);
            System.out.println("[" + robotName + "] Updated part: " + comp + " => " + val + "%");
        }
    }

    private void handleStatusEvent(String event) {
        switch (event) {
            case "WARN_MIN_TEMP":
                System.out.println("[" + robotName + "] WARNING: risk of freezing!");
                // Possibly auto-charge or wait to heat
                break;
            case "WARN_MAX_TEMP":
                System.out.println("[" + robotName + "] WARNING: risk of overheating!");
                break;
            case "HEATER_ON":
                System.out.println("[" + robotName + "] Heater turned on.");
                break;
            case "HEATER_OFF":
                System.out.println("[" + robotName + "] Heater turned off.");
                break;
            case "COOLER_ON":
                System.out.println("[" + robotName + "] Cooler turned on.");
                break;
            case "COOLER_OFF":
                System.out.println("[" + robotName + "] Cooler turned off.");
                break;
            case "WARN_LOW_ENERGY":
                System.out.println("[" + robotName + "] Low energy => must charge soon!");
                // If autopilot is active, we can do an immediate "charge" next
                break;
            case "EMERGENCY_CALL":
                System.out.println("[" + robotName + "] EMERGENCY: Robot near crash!");
                break;
            case "STUCK_IN_MUD":
                System.out.println("[" + robotName + "] Robot stuck in mud => Attempting rotate!");
                // Autopilot might do an immediate rotate command
                if (isAutonomous) {
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Math.random() > 0.5 ? "LEFT" : "RIGHT"));
                }
                break;
            case "CHARGE_END":
                System.out.println("[" + robotName + "] Charge finished!");
                break;
            case "MOVE_STOP":
                System.out.println("[" + robotName + "] Move was stopped mid-way!");
                break;
            case "MOVE_DIRECTION_CHANGED":
                System.out.println("[" + robotName + "] Movement direction changed due to malfunction!");
                break;
            case "SCAN_STOP":
                System.out.println("[" + robotName + "] Scan was stopped mid-way!");
                break;
            case "ROTATE_STOP":
                System.out.println("[" + robotName + "] Rotation was stopped mid-way!");
                break;
            default:
                System.out.println("[" + robotName + "] Unknown status event: " + event);
        }
    }

    // e.g. measure => store field & optionally do local logic
    private void handleMeasure(Map<String, Object> measureMap, int x, int y) {
        try {
            String groundStr = (String) measureMap.get("GROUND");
            float tempVal = asFloat(measureMap.get("TEMP"), 0f);
            Ground ground = Ground.valueOf(groundStr.toUpperCase(Locale.ROOT));
            Measure measure = new Measure(ground, tempVal);

            stationRef.updateExploredField(x, y, measure);
            System.out.println("[" + robotName + "] Measured field (" + x + "," + y + ") => " + measure);
        } catch (Exception e) {
            System.out.println("[" + robotName + "] measure parse error => " + measureMap);
        }
    }

    // Because we can't directly update energy or temp from moves, we do nothing here.
    // The planet will eventually send "status" or "charged" to let us know changes.
    private void handleEnergyForMove(int nx, int ny) {
        // (Removed direct energy manipulations)
        // Rely on "status" to update real energy
    }

    // Similarly, we don't forcibly set local temperature. We rely on "status" messages.
    private void handleTempEffect(float groundTemp) {
        // No direct local changes. The planet decides & notifies via "status".
    }

    // Helpers
    private int getPosX() {
        Position p = stationRef.getRobotPosition(robotName);
        return p != null ? p.getX() : 0;
    }

    private int getPosY() {
        Position p = stationRef.getRobotPosition(robotName);
        return p != null ? p.getY() : 0;
    }

    private void updatePositionFromMap(Map<String, Object> posMap) {
        int x = asInt(posMap.get("X"), 0);
        int y = asInt(posMap.get("Y"), 0);
        String dStr = (String) posMap.get("DIRECTION");
        Direction dir = parseDirection(dStr, Direction.NORTH);
        stationRef.setRobotPosition(robotName, x, y, dir);
    }

    public void closeSocket() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int asInt(Object val, int fallback) {
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        return fallback;
    }

    private float asFloat(Object val, float fallback) {
        if (val instanceof Number) {
            return ((Number) val).floatValue();
        }
        return fallback;
    }

    private Direction parseDirection(String dirStr, Direction def) {
        if (dirStr == null) return def;
        try {
            return Direction.valueOf(dirStr.toUpperCase());
        } catch (Exception e) {
            return def;
        }
    }
}

/**
 * Simple console UI for user interaction with Bodenstation
 */
class ConsoleUI extends Thread {
    private final Scanner consoleScanner = new Scanner(System.in);
    private final Bodenstation stationRef;

    public ConsoleUI(Bodenstation station) {
        this.stationRef = station;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            showMenu();
            if (!consoleScanner.hasNextLine()) break;
            String line = consoleScanner.nextLine().trim();
            if (line.isEmpty()) continue;

            switch (line.toLowerCase()) {
                case "exit":
                    stationRef.shutdown();
                    return;
                case "ls":
                    listRobots();
                    break;
                case "sel":
                    selectRobot();
                    break;
                case "stats":
                    System.out.println("Explored fields: " + stationRef.getExploredFields().size());
                    break;
                default:
                    System.out.println("Unknown command: " + line);
            }
        }
    }

    private void showMenu() {
        System.out.println("\n--- BODENSTATION MENU ---");
        System.out.println("[ls]    => list robots");
        System.out.println("[sel]   => select a robot for manual commands");
        System.out.println("[stats] => how many fields explored so far?");
        System.out.println("[exit]  => exit Bodenstation");
        System.out.print("> ");
    }

    private void listRobots() {
        Map<String, RobotSession> sess = stationRef.getSessions();
        if (sess.isEmpty()) {
            System.out.println("No robots connected.");
            return;
        }
        for (String robotKey : sess.keySet()) {
            RobotSession rs = sess.get(robotKey);
            System.out.println(" * " + robotKey + " (autonomous=" + rs.isAutonomous() + ")");
        }
    }

    private void selectRobot() {
        System.out.println("Enter Robot Name:");
        if (!consoleScanner.hasNextLine()) return;
        String rName = consoleScanner.nextLine().trim();
        RobotSession rs = stationRef.getSessions().get(rName);
        if (rs == null) {
            System.out.println("Robot not found: " + rName);
            return;
        }
        runRobotSubMenu(rs);
    }

    private void runRobotSubMenu(RobotSession robot) {
        boolean running = true;
        while (running && !Thread.currentThread().isInterrupted()) {
            System.out.println("\n-- SubMenu for " + robot.getRobotName() + " --");
            System.out.println("[1] toggle autonomy");
            System.out.println("[2] manual move");
            System.out.println("[3] manual scan");
            System.out.println("[4] mvscan (move + scan in one command)");
            System.out.println("[5] rotate left/right");
            System.out.println("[c] charge (5s)");
            System.out.println("[gp] getpos");
            System.out.println("[b] back to main menu");
            System.out.print("> ");

            if (!consoleScanner.hasNextLine()) break;
            String cmd = consoleScanner.nextLine().trim().toLowerCase();
            switch (cmd) {
                case "1":
                    robot.setAutonomous(!robot.isAutonomous());
                    break;
                case "2":
                    robot.sendJson(ExoPlanetProtocol.buildMoveCmd());
                    break;
                case "3":
                    robot.sendJson(ExoPlanetProtocol.buildScanCmd());
                    break;
                case "4":
                    robot.sendJson(ExoPlanetProtocol.buildMvScanCmd());
                    break;
                case "5":
                    System.out.println("Enter 'left' or 'right':");
                    if (!consoleScanner.hasNextLine()) break;
                    String rot = consoleScanner.nextLine().trim().toUpperCase(Locale.ROOT);
                    if (!rot.equals("LEFT") && !rot.equals("RIGHT")) {
                        System.out.println("Invalid rotation. Must be 'left' or 'right'");
                    } else {
                        robot.sendJson(ExoPlanetProtocol.buildRotateCmd(rot));
                    }
                    break;
                case "c":
                    robot.sendJson(ExoPlanetProtocol.buildChargeCmd(5));
                    break;
                case "gp":
                    robot.sendJson(ExoPlanetProtocol.buildGetPosCmd());
                    break;
                case "b":
                    running = false;
                    break;
                default:
                    System.out.println("Unknown command: " + cmd);
            }
        }
    }
}
