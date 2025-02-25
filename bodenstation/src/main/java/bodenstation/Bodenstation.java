package bodenstation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import exo.*;

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
 * Enumeration of all known ExoPlanet commands.
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
}

/**
 * Utility for building & parsing JSON-based ExoPlanet commands.
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

    public static String buildRotateCmd(Rotation rot) {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "rotate");
        cmd.put("ROTATION", rot.name());
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

    public static String buildExitCmd() {
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("CMD", "exit");
        return toJson(cmd);
    }
}

/**
 * Main Bodenstation application.
 */
public class Bodenstation {
    // Basic planet info (initially unknown => -1)
    private volatile String planetName = "UnknownPlanet";
    private volatile int planetWidth = -1;
    private volatile int planetHeight = -1;

    // Robot sessions
    private final Map<String, RobotSession> sessions = new ConcurrentHashMap<>();
    // Explored fields
    private final Map<Position, Measure> exploredFields = new ConcurrentHashMap<>();
    // Current Robot Positions
    private final Map<String, Position> robotPositions = new ConcurrentHashMap<>();
    // Collision reservation system
    private final Set<Position> reservedPositions = ConcurrentHashMap.newKeySet();

    private static final int BODENSTATION_PORT = 9999;

    private ServerAcceptor serverAcceptor;
    private ConsoleUI consoleUI;

    public static void main(String[] args) {
        Bodenstation station = new Bodenstation();
        station.start(BODENSTATION_PORT);
    }

    public void start(int port) {
        System.out.println("[Bodenstation] Starting on port " + port);
        serverAcceptor = new ServerAcceptor(port, this);
        serverAcceptor.start();

        consoleUI = new ConsoleUI(this);
        consoleUI.start();
    }

    // Session management
    public void registerSession(String name, RobotSession session) {
        sessions.put(name, session);
    }

    public void unregisterSession(String name) {
        sessions.remove(name);
        robotPositions.remove(name);
    }

    // Planet management
    public void setPlanetName(String name) {
        this.planetName = name;
    }

    public void setPlanetSize(int width, int height) {
        this.planetWidth = width;
        this.planetHeight = height;
        System.out.println("[Bodenstation] Planet size => w=" + width + " h=" + height);
    }

    public int getPlanetWidth() {
        return planetWidth;
    }

    public int getPlanetHeight() {
        return planetHeight;
    }

    // Field data
    public Map<Position, Measure> getExploredFields() {
        return exploredFields;
    }

    public boolean allFieldsExplored() {
        if (planetWidth < 1 || planetHeight < 1) return false;
        return exploredFields.size() >= (planetWidth * planetHeight);
    }

    public void updateFieldMeasurement(int x, int y, Measure measure) {
        exploredFields.put(new Position(x, y), measure);
    }

    // Robot positions
    public void setRobotPosition(String robotName, int x, int y, Direction d) {
        robotPositions.put(robotName, new Position(x, y, d));
    }

    public Position getRobotPosition(String robot) {
        return robotPositions.get(robot);
    }

    // Reservation-based collision avoidance
    public synchronized boolean reserveCell(Position cell) {
        if (reservedPositions.contains(cell)) {
            return false;
        }
        for (Position rp : robotPositions.values()) {
            if (rp.getX() == cell.getX() && rp.getY() == cell.getY()) {
                return false;
            }
        }
        reservedPositions.add(cell);
        return true;
    }

    public synchronized void releaseCell(Position cell) {
        reservedPositions.remove(cell);
    }

    public Map<String, RobotSession> getSessions() {
        return sessions;
    }

    // Shutdown
    public void shutdown() {
        System.out.println("[Bodenstation] Shutting down...");
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
        System.out.println("[Bodenstation] Goodbye!");
        System.exit(0);
    }

    // ------------------------------------------------------------------------------
    // SERVER ACCEPTOR
    // ------------------------------------------------------------------------------
    class ServerAcceptor extends Thread {
        private static final int ACCEPT_TIMEOUT_MS = 2000;
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
                serverSocket.setSoTimeout(ACCEPT_TIMEOUT_MS);
                System.out.println("[ServerAcceptor] Listening on port " + port);

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = serverSocket.accept();
                        String robotName = "Robot-" + UUID.randomUUID().toString().substring(0, 8);
                        RobotSession rs = new RobotSession(client, robotName, stationRef);
                        stationRef.registerSession(robotName, rs);
                        rs.start();
                        System.out.println("New Robot => " + robotName);
                    } catch (SocketTimeoutException e) {
                        // no connection => loop
                    }
                }
            } catch (IOException e) {
                System.out.println("[ServerAcceptor] Could not listen on port " + port + ": " + e);
            }
            System.out.println("[ServerAcceptor] ends");
        }

        public void closeServer() throws IOException {
            if (serverSocket != null) {
                serverSocket.close();
            }
        }
    }

    // ------------------------------------------------------------------------------
    // ROBOT SESSION
    // ------------------------------------------------------------------------------
    class RobotSession extends Thread {
        private static final int SOCKET_TIMEOUT_MS = 2000;
        private static final int ACTION_SLEEP_MS = 500;
        private static final int CHARGE_DURATION_S = 5;

        private final Socket socket;
        private final String robotName;
        private final Bodenstation stationRef;

        private PrintWriter out;
        private BufferedReader in;

        private boolean isCrashed = false;
        private boolean isAutonomous = false;
        private boolean hasEnteredOrbit = false; // once we get init
        private boolean hasLanded = false; // once we get landed

        // Robot states updated only by `status` or `charged`
        private float robotTemp = 20f;
        private int robotEnergy = 100;

        // BFS with an ordered Deque, row-major fill
        private final Deque<Position> frontier = new ArrayDeque<>();

        private Thread autoPilotThread;

        // If we get a "landed" measure but don't have the position yet,
        // we store it here. Then when we get "pos" or a 'moved' with position,
        // we apply that measure.
        private Measure pendingLandingMeasure = null;

        // Movement re-attempt if we get a MOVE_STOP
        private Position lastMoveTarget = null;    // Where we were trying to go
        private boolean needReAttempt = false;   // If we get MOVE_STOP

        // Attempt to handle STUCK_IN_MUD => left first, then right
        private boolean stuckLeftTried = false; // if we do left rotation for STUCK_IN_MUD, next time do right

        public RobotSession(Socket client, String name, Bodenstation station) {
            this.socket = client;
            this.robotName = name;
            this.stationRef = station;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // 1) Send orbit
                sendJson(ExoPlanetProtocol.buildOrbitCmd(robotName));

                while (!Thread.currentThread().isInterrupted() && !isCrashed) {
                    String line;
                    try {
                        line = in.readLine();
                        if (line == null) {
                            System.out.println("[" + robotName + "] Connection closed by remote.");
                            break;
                        }
                        Map<String, Object> msg = ExoPlanetProtocol.fromJson(line);
                        ExoPlanetCmd cmd = ExoPlanetProtocol.getCmd(msg);
                        handleIncoming(cmd, msg);
                    } catch (SocketTimeoutException e) {
                        // check for interrupt
                    }
                }
            } catch (IOException e) {
                System.out.println("[" + robotName + "] Connection lost => " + e);
            } finally {
                stopAutoPilot();
                closeSocket();
                stationRef.unregisterSession(robotName);
                System.out.println("[" + robotName + "] Session ended.");
            }
        }

        // Send JSON command
        public void sendJson(String cmd) {
            if (out != null) out.println(cmd);
        }

        // Autonomy
        public synchronized void setAutonomous(boolean auto) {
            if (isCrashed) {
                System.out.println("[" + robotName + "] can't go autonomous => crashed");
                return;
            }
            isAutonomous = auto;
            System.out.println("[" + robotName + "] Autonomy => " + auto);
            // If we already have orbit, we can start BFS
            if (auto && hasEnteredOrbit) {
                startAutoPilot();
            } else if (!autoPilotThreadHasStopped()) {
                stopAutoPilot();
            }
        }

        private boolean autoPilotThreadHasStopped() {
            return (autoPilotThread == null || !autoPilotThread.isAlive());
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

        public boolean isAutonomous() {
            return isAutonomous;
        }

        public String getRobotName() {
            return robotName;
        }

        // BFS-based autopilot
        private void runAutoPilot() {
            System.out.println("[" + robotName + "] autopilot started...");
            try {
                // If not in orbit => wait for "init"
                while (!Thread.currentThread().isInterrupted() && !hasEnteredOrbit) {
                    Thread.sleep(ACTION_SLEEP_MS);
                }
                // If still not => cannot BFS
                if (!hasEnteredOrbit) {
                    System.out.println("[" + robotName + "] cannot BFS => no orbit");
                    return;
                }

                // Fill BFS queue
                fillFrontier();

                // If not landed => attempt landing
                if (!hasLanded) {
                    attemptLanding();
                    return;
                }

                // BFS main loop
                while (!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                    removeAlreadyMeasured();

                    if (stationRef.allFieldsExplored()) {
                        System.out.println("[" + robotName + "] Planet fully explored => exit");
                        sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    }

                    Position next = pickFrontier();
                    if (next == null) {
                        System.out.println("[" + robotName + "] BFS frontier empty => exit");
                        sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    }

                    // If we have low energy => immediate charge
                    if (robotEnergy < 20) {
                        System.out.println("[" + robotName + "] Low energy => immediate charge");
                        sendJson(ExoPlanetProtocol.buildChargeCmd(CHARGE_DURATION_S));
                        // wait for "charged"
                        Thread.sleep((CHARGE_DURATION_S + 1) * 1000L);
                        continue;
                    }

                    // If we need to re-attempt a move
                    if (needReAttempt && lastMoveTarget != null) {
                        System.out.println("[" + robotName + "] Re-attempt => " + lastMoveTarget);
                        moveStepByStep(lastMoveTarget.getX(), lastMoveTarget.getY());
                        needReAttempt = false;
                        continue; // then do mvscan
                    }

                    // normal BFS => move
                    moveStepByStep(next.getX(), next.getY());
                    Thread.sleep(ACTION_SLEEP_MS);
                }

            } catch (InterruptedException e) {
                System.out.println("[" + robotName + "] autopilot interrupted");
            }
            System.out.println("[" + robotName + "] autopilot ended.");
        }

        private void fillFrontier() {
            frontier.clear();
            int w = stationRef.getPlanetWidth();
            int h = stationRef.getPlanetHeight();
            if (w < 1 || h < 1) return; // not known
            // row-major fill
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    frontier.addLast(new Position(xx, yy));
                }
            }
        }

        private void removeAlreadyMeasured() {
            // remove from frontier if measured
            frontier.removeIf(p -> stationRef.getExploredFields().containsKey(new Position(p.getX(), p.getY())));
        }

        private Position pickFrontier() {
            // pop from front
            if (frontier.isEmpty()) return null;
            return frontier.pollFirst();
        }

        private void attemptLanding() throws InterruptedException {
            // For now we land on (0,0)
            sendJson(ExoPlanetProtocol.buildLandCmd(0, 0, Direction.EAST));
            System.out.println("[" + robotName + "] Attempting to land at ");
            Thread.sleep(ACTION_SLEEP_MS);
        }

        private void moveStepByStep(int tx, int ty) throws InterruptedException {
            Position cur = stationRef.getRobotPosition(robotName);
            if (cur == null) {
                System.out.println("[" + robotName + "] No known position => skip BFS move");
                return;
            }
            lastMoveTarget = new Position(tx, ty);

            while (!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                int dx = tx - cur.getX();
                int dy = ty - cur.getY();
                if (dx == 0 && dy == 0) break; // reached

                Direction neededDir = null;
                if (Math.abs(dx) > 0) {
                    neededDir = dx > 0 ? Direction.EAST : Direction.WEST;
                } else if (Math.abs(dy) > 0) {
                    neededDir = dy > 0 ? Direction.SOUTH : Direction.NORTH;
                }
                if (neededDir != null && cur.getDir() != neededDir) {
                    Rotation rot = computeRotation(cur.getDir(), neededDir);
                    sendJson(ExoPlanetProtocol.buildRotateCmd(rot));
                    Thread.sleep(ACTION_SLEEP_MS);
                    continue;
                } else {
                    // reserve next
                    Position nextCell = nextPosition(cur);
                    if (!stationRef.reserveCell(nextCell)) {
                        System.out.println("[" + robotName + "] nextCell reserved => rotate or skip");
                        sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                        Thread.sleep(ACTION_SLEEP_MS);
                        continue;
                    }

                    if (!stationRef.getExploredFields().containsKey(nextCell)) {
                        System.out.println("[" + robotName + "] Using MVSCAN for next cell: " + nextCell);
                        sendJson(ExoPlanetProtocol.buildMvScanCmd());
                    } else {
                        sendJson(ExoPlanetProtocol.buildMoveCmd());
                    }

                    Thread.sleep(ACTION_SLEEP_MS * 2);
                    stationRef.releaseCell(nextCell);
                }
                cur = stationRef.getRobotPosition(robotName);
                if (cur == null) break;
            }
        }

        private Position nextPosition(Position pos) {
            int x = pos.getX();
            int y = pos.getY();
            switch (pos.getDir()) {
                case NORTH:
                    y--;
                    break;
                case SOUTH:
                    y++;
                    break;
                case EAST:
                    x++;
                    break;
                case WEST:
                    x--;
                    break;
            }
            return new Position(x, y);
        }

        private Rotation computeRotation(Direction currentDir, Direction targetDir) {
            List<Direction> circle = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
            int ci = circle.indexOf(currentDir);
            int ti = circle.indexOf(targetDir);
            int diff = ti - ci;
            if (diff < 0) diff += 4;
            if (diff == 1) return Rotation.RIGHT;
            if (diff == 3) return Rotation.LEFT;
            return Rotation.RIGHT;
        }

        // ----------------------------------------------------------------
        // handleIncoming
        // ----------------------------------------------------------------
        private void handleIncoming(ExoPlanetCmd cmd, Map<String, Object> msg) {
            switch (cmd) {
                case INIT:
                    handleInit(msg);
                    break;
                case LANDED:
                    handleLanded(msg);
                    break;
                case SCANED:
                    handleScaned(msg);
                    break;
                case MVSCANED:
                    // we do handleScaned plus handleMoved
                    handleScaned(msg);
                    handleMoved(msg);
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
                case POS:
                    handlePos(msg);
                    break;
                case CRASHED:
                    System.out.println("[" + robotName + "] CRASHED => end session");
                    isCrashed = true;
                    break;
                case ERROR:
                    System.out.println("[" + robotName + "] ERROR => " + msg);
                    break;
                case EXIT:
                    System.out.println("[" + robotName + "] Robot says exit => end session");
                    isCrashed = true;
                    break;
                default:
                    System.out.println("[" + robotName + "] unhandled => " + cmd + " => " + msg);
            }
        }

        private void handleInit(Map<String, Object> msg) {
            // => we have planet size => hasEnteredOrbit=true
            Map<String, Object> sizeMap = (Map<String, Object>) msg.get("SIZE");
            if (sizeMap != null) {
                int w = asInt(sizeMap.get("WIDTH"), -1);
                int h = asInt(sizeMap.get("HEIGHT"), -1);
                stationRef.setPlanetSize(w, h);
                hasEnteredOrbit = true;
                System.out.println("[" + robotName + "] ORBIT -> Planet w=" + w + ",h=" + h);
                // if isAutonomous => start BFS if not started
                if (isAutonomous && autoPilotThreadHasStopped()) {
                    startAutoPilot();
                }
            }
        }

        private void handleLanded(Map<String, Object> msg) {
            // we got some measure => store in pendingLandingMeasure
            // we do not know the actual (x,y) yet => so we do a getpos
            Map<String, Object> measure = (Map<String, Object>) msg.get("MEASURE");
            if (measure != null) {
                pendingLandingMeasure = parseMeasurement(measure);
            }
            // request actual position and set the hasLanded flag once we receive it
            sendJson(ExoPlanetProtocol.buildGetPosCmd());
        }

        private void handleScaned(Map<String, Object> msg) {
            Map<String, Object> measureMap = (Map<String, Object>) msg.get("MEASURE");
            if (measureMap != null) {
                // store measure at current position
                Position pos = stationRef.getRobotPosition(robotName);
                if (pos != null) {
                    handleMeasurement(measureMap, pos.getX(), pos.getY());
                }
            }
        }

        private void handleMoved(Map<String, Object> msg) {
            // update local position
            Map<String, Object> posMap = (Map<String, Object>) msg.get("POSITION");
            if (posMap != null) {
                updatePosition(posMap);
            }
        }

        private void handleRotated(Map<String, Object> msg) {
            if (msg.containsKey("DIRECTION")) {
                String dStr = (String) msg.get("DIRECTION");
                Direction nd = parseDir(dStr, Direction.NORTH);
                Position cur = stationRef.getRobotPosition(robotName);
                if (cur != null) {
                    cur.setDir(nd);
                }
            }
        }

        private void handleCharged(Map<String, Object> msg) {
            Map<String, Object> st = (Map<String, Object>) msg.get("STATUS");
            if (st != null) handleStatusData(st);
            System.out.println("[" + robotName + "] CHARGED => energy=" + robotEnergy + ", temp=" + robotTemp);
        }

        private void handleStatus(Map<String, Object> msg) {
            Map<String, Object> st = (Map<String, Object>) msg.get("STATUS");
            if (st != null) handleStatusData(st);
        }

        private void handlePos(Map<String, Object> msg) {
            // {"CMD":"pos","POSITION":{"X":..,"Y":..,"DIRECTION":".."}}
            Map<String, Object> posMap = (Map<String, Object>) msg.get("POSITION");
            if (posMap != null) {
                updatePosition(posMap);
                // If we had pendingLandingMeasure => apply it
                if (pendingLandingMeasure != null) {
                    Position cur = stationRef.getRobotPosition(robotName);
                    if (cur != null) {
                        handleMeasurementInternal(pendingLandingMeasure, cur.getX(), cur.getY());
                    }
                    // if isAutonomous => start BFS if not started
                    if (isAutonomous && autoPilotThreadHasStopped()) {
                        startAutoPilot();
                    }
                    pendingLandingMeasure = null;
                    hasLanded = true;
                }
            }
        }

        private void handleStatusData(Map<String, Object> stMap) {
            robotTemp = asFloat(stMap.get("TEMP"), robotTemp);
            robotEnergy = asInt(stMap.get("ENERGY"), robotEnergy);

            String message = (String) stMap.get("MESSAGE");
            if (message == null || message.isEmpty()) return;
            String[] tokens = message.split("\\|");
            for (String t : tokens) {
                t = t.trim();
                if (t.contains("=")) {
                    parsePartStatus(t);
                } else {
                    handleStatusEvent(t);
                }
            }
        }

        private void parsePartStatus(String eq) {
            // e.g. "MOTOR=50"
            String[] parts = eq.split("=");
            if (parts.length == 2) {
                String comp = parts[0].trim();
                int val = asInt(parts[1], 100);
                System.out.println("[" + robotName + "] PART " + comp + " => " + val + "%");
            }
        }

        private void handleStatusEvent(String event) {
            switch (event) {
                case "WARN_MIN_TEMP":
                case "WARN_MAX_TEMP":
                case "HEATER_ON":
                case "HEATER_OFF":
                case "COOLER_ON":
                case "COOLER_OFF":
                case "EMERGENCY_CALL":
                case "CHARGE_END":
                case "SCAN_STOP":
                case "ROTATE_STOP":
                    System.out.println("[" + robotName + "] status => " + event);
                    break;
                case "WARN_LOW_ENERGY":
                    System.out.println("[" + robotName + "] => WARN_LOW_ENERGY => immediate charge if autopilot");
                    if (isAutonomous) {
                        sendJson(ExoPlanetProtocol.buildChargeCmd(CHARGE_DURATION_S));
                    }
                    break;
                case "STUCK_IN_MUD":
                    System.out.println("[" + robotName + "] => STUCK_IN_MUD => rotate left first, next time right");
                    if (isAutonomous) {
                        if (!stuckLeftTried) {
                            sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.LEFT));
                            stuckLeftTried = true;
                        } else {
                            sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                            stuckLeftTried = false;
                        }
                    }
                    break;
                case "MOVE_STOP":
                    System.out.println("[" + robotName + "] => MOVE_STOP => re-attempt next BFS iteration");
                    needReAttempt = true;
                    break;
                case "MOVE_DIRECTION_CHANGED":
                    System.out.println("[" + robotName + "] => MOVE_DIRECTION_CHANGED => do getpos, recalc BFS if needed");
                    sendJson(ExoPlanetProtocol.buildGetPosCmd());
                    break;
                default:
                    System.out.println("[" + robotName + "] => unknown status => " + event);
            }
        }

        // We'll parse the measure object fully here
        private Measure parseMeasurement(Map<String, Object> measureMap) {
            try {
                String groundStr = (String) measureMap.get("GROUND");
                float tmpVal = asFloat(measureMap.get("TEMP"), 0f);
                Ground g = Ground.valueOf(groundStr.toUpperCase(Locale.ROOT));
                return new Measure(g, tmpVal);
            } catch (Exception e) {
                System.out.println("[" + robotName + "] measure parse error => " + measureMap);
                return null;
            }
        }

        private void handleMeasurement(Map<String, Object> measureMap, int x, int y) {
            // just parse and store
            Measure meas = parseMeasurement(measureMap);
            if (meas != null) {
                handleMeasurementInternal(meas, x, y);
            }
        }

        private void handleMeasurementInternal(Measure meas, int x, int y) {
            stationRef.updateFieldMeasurement(x, y, meas);
            System.out.println("[" + robotName + "] measured => (" + x + "," + y + ") => " + meas);
        }

        private void updatePosition(Map<String, Object> posMap) {
            int x = asInt(posMap.get("X"), 0);
            int y = asInt(posMap.get("Y"), 0);
            String dStr = (String) posMap.get("DIRECTION");
            Direction nd = parseDir(dStr, Direction.NORTH);
            stationRef.setRobotPosition(robotName, x, y, nd);
        }

        // I/O
        public void closeSocket() {
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // Helpers
        private int asInt(Object val, int fallback) {
            if (val instanceof Number) return ((Number) val).intValue();
            return fallback;
        }

        private float asFloat(Object val, float fallback) {
            if (val instanceof Number) return ((Number) val).floatValue();
            return fallback;
        }

        private Direction parseDir(String s, Direction def) {
            if (s == null) return def;
            try {
                return Direction.valueOf(s.toUpperCase());
            } catch (Exception e) {
                return def;
            }
        }
    }

    // ------------------------------------------------------------------------------
    // SIMPLE CONSOLE UI
    // ------------------------------------------------------------------------------
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
                        System.out.println("Explored fields => " + stationRef.getExploredFields().size());
                        System.out.println("Planet => width=" + stationRef.getPlanetWidth()
                                + ", height=" + stationRef.getPlanetHeight());
                        break;
                    default:
                        System.out.println("Unknown command => " + line);
                }
            }
        }

        private void showMenu() {
            System.out.println("\n-- BODENSTATION MENU --");
            System.out.println("[ls]    => list robots");
            System.out.println("[sel]   => select a robot (manual commands)");
            System.out.println("[stats] => show exploration stats");
            System.out.println("[exit]  => shut down");
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
                System.out.println(" * " + robotKey + " => autonomous=" + rs.isAutonomous());
            }
        }

        private void selectRobot() {
            System.out.println("Enter Robot Name:");
            if (!consoleScanner.hasNextLine()) return;
            String rName = consoleScanner.nextLine().trim();
            RobotSession rs = stationRef.getSessions().get(rName);
            if (rs == null) {
                System.out.println("Robot not found => " + rName);
                return;
            }
            robotSubMenu(rs);
        }

        private void robotSubMenu(RobotSession robot) {
            boolean running = true;
            while (running && !Thread.currentThread().isInterrupted()) {
                System.out.println("\n-- SubMenu for " + robot.getRobotName() + " --");
                System.out.println("[1] toggle autonomy");
                System.out.println("[2] manual move");
                System.out.println("[3] manual scan");
                System.out.println("[4] mvscan");
                System.out.println("[5] rotate left/right");
                System.out.println("[c] charge(5s)");
                System.out.println("[gp] getpos");
                System.out.println("[x] exit command");
                System.out.println("[b] back");
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
                        String rotStr = consoleScanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        Rotation rotation = null;
                        if ("LEFT".equals(rotStr)) rotation = Rotation.LEFT;
                        else if ("RIGHT".equals(rotStr)) rotation = Rotation.RIGHT;
                        else {
                            System.out.println("Invalid rotation => 'left' or 'right' only");
                            break;
                        }
                        robot.sendJson(ExoPlanetProtocol.buildRotateCmd(rotation));
                        break;
                    case "c":
                        robot.sendJson(ExoPlanetProtocol.buildChargeCmd(5));
                        break;
                    case "gp":
                        robot.sendJson(ExoPlanetProtocol.buildGetPosCmd());
                        break;
                    case "x":
                        robot.sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    case "b":
                        running = false;
                        break;
                    default:
                        System.out.println("Unknown => " + cmd);
                }
            }
        }
    }
}
