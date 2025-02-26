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
    private static final int BODENSTATION_PORT = 9999;
    private final Map<String, RobotSession> sessions = new ConcurrentHashMap<>();
    private final Map<Position, Measure> exploredFields = new ConcurrentHashMap<>();
    private final Map<String, Position> robotPositions = new ConcurrentHashMap<>();
    private final Set<Position> reservedPositions = ConcurrentHashMap.newKeySet();
    // Planet info (initially unknown => -1)
    private volatile String planetName = "UnknownPlanet";
    private volatile int planetWidth = -1;
    private volatile int planetHeight = -1;
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
    private void registerSession(String name, RobotSession session) {
        sessions.put(name, session);
    }

    private void unregisterSession(String name) {
        sessions.remove(name);
        robotPositions.remove(name);
    }

    public void setPlanetSize(int width, int height) {
        this.planetWidth = width;
        this.planetHeight = height;
        System.out.println("[Bodenstation] Planet size => w=" + width + " h=" + height);
    }

    public String getPlanetName() {
        return planetName;
    }

    public void setPlanetName(String name) {
        this.planetName = name;
    }

    public int getPlanetWidth() {
        return planetWidth;
    }

    public int getPlanetHeight() {
        return planetHeight;
    }

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

    public void setRobotPosition(String robotName, int x, int y, Direction d) {
        robotPositions.put(robotName, new Position(x, y, d));
    }

    public Position getRobotPosition(String robot) {
        return robotPositions.get(robot);
    }

    // Reservation-based collision avoidance
    public synchronized boolean reserveField(Position fieldPos) {
        if (reservedPositions.contains(fieldPos)) {
            return false;
        }
        // also check if any robot is physically there
        for (Position rp : robotPositions.values()) {
            if (rp.getX() == fieldPos.getX() && rp.getY() == fieldPos.getY()) {
                return false;
            }
        }
        reservedPositions.add(fieldPos);
        return true;
    }

    public synchronized void releaseField(Position fieldPos) {
        reservedPositions.remove(fieldPos);
    }

    public Map<String, RobotSession> getSessions() {
        return sessions;
    }

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
        private final int port;
        private final Bodenstation stationRef;
        private ServerSocket serverSocket;

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
                        // ignore, loop
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
        // The list of fields to explore in autopilot (row-major approach)
        private final Deque<Position> fieldsToExplore = new ArrayDeque<>();
        private PrintWriter out;
        private BufferedReader in;
        private boolean isCrashed = false;
        private boolean isAutonomous = false;
        private boolean hasEnteredOrbit = false;
        private boolean hasLanded = false;
        private float robotTemp = 20f;
        private int robotEnergy = 100;
        private Thread autoPilotThread;

        // If we get a "landed" measure but no position yet, store it here
        private Measure pendingLandingMeasure = null;

        // Movement re-attempt if we get a MOVE_STOP
        private Position lastMoveTarget = null;
        private boolean needReAttempt = false;

        // STUCK_IN_MUD => rotate left first, then right
        private boolean stuckLeftTried = false;

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

                sendJson(ExoPlanetProtocol.buildOrbitCmd(robotName));

                while (!Thread.currentThread().isInterrupted() && !isCrashed) {
                    try {
                        String line = in.readLine();
                        if (line == null) {
                            System.out.println("[" + robotName + "] Connection closed by remote.");
                            break;
                        }
                        Map<String, Object> msg = ExoPlanetProtocol.fromJson(line);
                        ExoPlanetCmd cmd = ExoPlanetProtocol.getCmd(msg);
                        handleIncoming(cmd, msg);
                    } catch (SocketTimeoutException ignored) {
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

        public void sendJson(String jsonCmd) {
            if (out != null) out.println(jsonCmd);
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

        public synchronized void setAutonomous(boolean auto) {
            if (isCrashed) {
                System.out.println("[" + robotName + "] can't go autonomous => crashed");
                return;
            }
            isAutonomous = auto;
            System.out.println("[" + robotName + "] Autonomy => " + auto);
            if (auto && hasEnteredOrbit) {
                startAutoPilot();
            } else if (!autoPilotThreadHasStopped()) {
                stopAutoPilot();
            }
        }

        public String getRobotName() {
            return robotName;
        }

        // ----------------------------------------------------------------
        // AUTOPILOT LOGIC
        // ----------------------------------------------------------------
        private void runAutoPilot() {
            System.out.println("[" + robotName + "] autopilot started...");
            try {
                if (!hasEnteredOrbit) {
                    System.out.println("[" + robotName + "] Autopilot paused: awaiting orbit entry. Resume once robot enters orbit.");
                    return;
                }
                if (!hasLanded) {
                    System.out.println("[" + robotName + "] Autopilot paused: awaiting landing on " + stationRef.getPlanetName() + ".");
                    attemptLanding();
                    return;
                }

                fillFieldsToExplore();

                // main exploration
                while (!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                    // Step (1) => ensure neighbor fields are scanned
                    scanSurroundingFieldsIfNeeded();

                    removeMeasuredFields();
                    if (stationRef.allFieldsExplored()) {
                        System.out.println("[" + robotName + "] All fields measured => exit");
                        sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    }

                    // check energy
                    if (robotEnergy < 20) {
                        System.out.println("[" + robotName + "] Low energy => charging");
                        sendJson(ExoPlanetProtocol.buildChargeCmd(CHARGE_DURATION_S));
                        Thread.sleep((CHARGE_DURATION_S + 1) * 1000L);
                        continue;
                    }

                    // re-attempt movement if needed
                    if (needReAttempt && lastMoveTarget != null) {
                        moveStepByStep(lastMoveTarget.getX(), lastMoveTarget.getY());
                        needReAttempt = false;
                        continue;
                    }

                    Position nextField = pickNextField();
                    if (nextField == null) {
                        System.out.println("[" + robotName + "] no more fields => exit");
                        sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    }
                    System.out.println("[" + robotName + "] Next field => " + nextField);
                    moveStepByStep(nextField.getX(), nextField.getY());
                    Thread.sleep(ACTION_SLEEP_MS);
                }

            } catch (InterruptedException e) {
                System.out.println("[" + robotName + "] autopilot interrupted.");
            }
            System.out.println("[" + robotName + "] autopilot ended.");
        }

        /**
         * Optional method for autopilot: attemptLanding.
         * Called automatically if you want the autopilot to handle land from code.
         * Otherwise used from the UI or not used at all.
         */
        private void attemptLanding() throws InterruptedException {
            Position landSpot = findLandingSpot();
            if (landSpot == null) {
                System.out.println("[" + robotName + "] No valid landing spot => skip");
                return;
            }
            if (!stationRef.reserveField(landSpot)) {
                System.out.println("[" + robotName + "] Cannot reserve landing => skip");
                return;
            }
            lastMoveTarget = landSpot;
            sendJson(ExoPlanetProtocol.buildLandCmd(landSpot.getX(), landSpot.getY(), Direction.EAST));
            System.out.println("[" + robotName + "] Attempting to land at => " + landSpot);
            Thread.sleep(ACTION_SLEEP_MS);
        }

        /**
         * Find a free spot for landing, skipping known NICHTS fields or occupied fields.
         */
        private Position findLandingSpot() {
            int w = stationRef.getPlanetWidth();
            int h = stationRef.getPlanetHeight();
            if (w < 1 || h < 1) return null;
            int startRow = Math.min(1, h - 1);
            for (int y = startRow; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    Position p = new Position(x, y);
                    if (!isFieldOccupied(p) && !isFieldNichts(p)) {
                        return p;
                    }
                }
            }
            return null;
        }

        private void scanSurroundingFieldsIfNeeded() throws InterruptedException {
            Position cur = stationRef.getRobotPosition(robotName);
            if (cur == null) return;

            List<Position> neighbors = getNeighborFields(cur);
            for (Position nb : neighbors) {
                if (!stationRef.getExploredFields().containsKey(nb)) {
                    // rotate so we're facing that neighbor
                    Direction needed = directionFromTo(cur, nb);
                    if (cur.getDir() != needed) {
                        Rotation r = computeRequiredRotation(cur.getDir(), needed);
                        sendJson(ExoPlanetProtocol.buildRotateCmd(r));
                        Thread.sleep(ACTION_SLEEP_MS);
                        // update local dir
                        cur.setDir(rotateDirection(cur.getDir(), r));
                    }
                    sendJson(ExoPlanetProtocol.buildScanCmd());
                    Thread.sleep(ACTION_SLEEP_MS);
                }
            }
        }

        private List<Position> getNeighborFields(Position pos) {
            List<Position> result = new ArrayList<>();
            int px = pos.getX();
            int py = pos.getY();
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue; // skip center
                    Position np = new Position(px + dx, py + dy);
                    if (inBounds(np)) result.add(np);
                }
            }
            return result;
        }

        private Direction directionFromTo(Position from, Position to) {
            int dx = to.getX() - from.getX();
            int dy = to.getY() - from.getY();
            // We'll pick a single direction (N, S, E, or W) if dx/dy bigger
            if (Math.abs(dx) > Math.abs(dy)) {
                return (dx > 0) ? Direction.EAST : Direction.WEST;
            } else {
                return (dy > 0) ? Direction.SOUTH : Direction.NORTH;
            }
        }

        // fill fieldsToExplore (row-major)
        private void fillFieldsToExplore() {
            fieldsToExplore.clear();
            int w = stationRef.getPlanetWidth();
            int h = stationRef.getPlanetHeight();
            if (w < 1 || h < 1) return;
            // row-major from second row if possible
            int startRow = Math.min(1, h - 1);
            for (int y = startRow; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    fieldsToExplore.addLast(new Position(x, y));
                }
            }
        }

        private void removeMeasuredFields() {
            fieldsToExplore.removeIf(p -> stationRef.getExploredFields().containsKey(p));
        }

        private Position pickNextField() {
            if (fieldsToExplore.isEmpty()) return null;
            return fieldsToExplore.pollFirst();
        }

        private boolean isFieldOccupied(Position p) {
            for (Position rp : stationRef.robotPositions.values()) {
                if (rp.getX() == p.getX() && rp.getY() == p.getY()) {
                    return true;
                }
            }
            return false;
        }

        private boolean isFieldNichts(Position p) {
            Measure m = stationRef.getExploredFields().get(p);
            return (m != null && m.getGround() == Ground.NICHTS);
        }

        private boolean inBounds(Position p) {
            return p.getX() >= 0 && p.getX() < stationRef.getPlanetWidth() && p.getY() >= 0 && p.getY() < stationRef.getPlanetHeight();
        }

        // ----------------------------------------------------------------
        // Movement logic
        // ----------------------------------------------------------------
        private void moveStepByStep(int tx, int ty) throws InterruptedException {
            Position cur = stationRef.getRobotPosition(robotName);
            if (cur == null) {
                System.out.println("[" + robotName + "] No known pos => skip move");
                return;
            }
            lastMoveTarget = new Position(tx, ty);

            while (!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                int dx = tx - cur.getX();
                int dy = ty - cur.getY();
                if (dx == 0 && dy == 0) break;

                Direction neededDir = null;
                if (Math.abs(dx) > 0) {
                    neededDir = (dx > 0) ? Direction.EAST : Direction.WEST;
                } else if (Math.abs(dy) > 0) {
                    neededDir = (dy > 0) ? Direction.SOUTH : Direction.NORTH;
                }

                if (neededDir != null && cur.getDir() != neededDir) {
                    Rotation r = computeRequiredRotation(cur.getDir(), neededDir);
                    sendJson(ExoPlanetProtocol.buildRotateCmd(r));
                    // update local direction after rotating
                    cur.setDir(rotateDirection(cur.getDir(), r));
                    Thread.sleep(ACTION_SLEEP_MS);
                    continue;
                }

                Position forward = nextField(cur);
                if (!inBounds(forward)) {
                    System.out.println("[" + robotName + "] forward out-of-bounds => turn right");
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                    cur.setDir(rotateDirection(cur.getDir(), Rotation.RIGHT));
                    Thread.sleep(ACTION_SLEEP_MS);
                    continue;
                }

                if (isFieldNichts(forward)) {
                    System.out.println("[" + robotName + "] forward is NICHTS => turn right");
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                    cur.setDir(rotateDirection(cur.getDir(), Rotation.RIGHT));
                    Thread.sleep(ACTION_SLEEP_MS);
                    continue;
                }

                if (!stationRef.reserveField(forward)) {
                    System.out.println("[" + robotName + "] forward reserved => check alt path => turn right");
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                    cur.setDir(rotateDirection(cur.getDir(), Rotation.RIGHT));
                    Thread.sleep(ACTION_SLEEP_MS);
                    continue;
                }

                doMoveOrMvScan(forward);
                Thread.sleep(ACTION_SLEEP_MS * 2);

                cur = stationRef.getRobotPosition(robotName);
                if (cur == null) break;
            }
        }

        private void doMoveOrMvScan(Position forward) {
            if (!stationRef.getExploredFields().containsKey(forward)) {
                System.out.println("[" + robotName + "] Using MVSCAN for => " + forward);
                sendJson(ExoPlanetProtocol.buildMvScanCmd());
            } else {
                System.out.println("[" + robotName + "] Move => " + forward);
                sendJson(ExoPlanetProtocol.buildMoveCmd());
            }
        }

        private Position nextField(Position pos) {
            int x = pos.getX(), y = pos.getY();
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
            return new Position(x, y);
        }

        private Rotation computeRequiredRotation(Direction currentDir, Direction targetDir) {
            List<Direction> circle = Arrays.asList(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
            int ci = circle.indexOf(currentDir);
            int ti = circle.indexOf(targetDir);
            int diff = ti - ci;
            if (diff < 0) diff += 4;
            if (diff == 1) return Rotation.RIGHT;
            if (diff == 3) return Rotation.LEFT;
            return Rotation.RIGHT; // fallback
        }

        private Direction rotateDirection(Direction dir, Rotation rot) {
            List<Direction> circle = Arrays.asList(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
            int idx = circle.indexOf(dir);
            if (rot == Rotation.RIGHT) {
                idx = (idx + 1) % 4;
            } else {
                idx = (idx + 3) % 4;
            }
            return circle.get(idx);
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
                    // if we crashed on NICHTS => record it
                    if (lastMoveTarget != null && !hasLanded) {
                        stationRef.updateFieldMeasurement(lastMoveTarget.getX(), lastMoveTarget.getY(),
                                new Measure(Ground.NICHTS, -999.9f));
                        stationRef.releaseField(lastMoveTarget);
                        System.out.println("[" + robotName + "] => recorded NICHTS for crashed field => " + lastMoveTarget);
                    }
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
            Map<String, Object> sizeMap = (Map<String, Object>) msg.get("SIZE");
            if (sizeMap != null) {
                int w = asInt(sizeMap.get("WIDTH"), -1);
                int h = asInt(sizeMap.get("HEIGHT"), -1);
                stationRef.setPlanetSize(w, h);
                hasEnteredOrbit = true;
                System.out.println("[" + robotName + "] => Entered orbit of " + stationRef.getPlanetName() + ": w=" + w + " h=" + h);
                if (isAutonomous && autoPilotThreadHasStopped()) {
                    startAutoPilot();
                }
            }
        }

        private void handleLanded(Map<String, Object> msg) {
            Map<String, Object> measure = (Map<String, Object>) msg.get("MEASURE");
            if (measure != null) {
                pendingLandingMeasure = parseMeasurement(measure);
                System.out.println("[" + robotName + "] => LANDED on " + stationRef.getPlanetName() + " with measure => " + measure);
            }
            sendJson(ExoPlanetProtocol.buildGetPosCmd());
        }

        private void handleScaned(Map<String, Object> msg) {
            Map<String, Object> measureMap = (Map<String, Object>) msg.get("MEASURE");
            if (measureMap != null) {
                Position cur = stationRef.getRobotPosition(robotName);
                if (cur != null) {
                    // we measure forward
                    Position fwd = nextField(cur);
                    handleMeasurement(measureMap, fwd.getX(), fwd.getY());
                    System.out.println("[" + robotName + "] => SCANED => field ahead (" + fwd + ").");
                }
            }
        }

        private void handleMoved(Map<String, Object> msg) {
            Map<String, Object> posMap = (Map<String, Object>) msg.get("POSITION");
            if (posMap != null) {
                updatePosition(posMap);
                System.out.println("[" + robotName + "] => MOVED => new position => " + posMap);
            }
            if (lastMoveTarget != null) {
                stationRef.releaseField(lastMoveTarget);
            }
        }

        private void handleRotated(Map<String, Object> msg) {
            if (msg.containsKey("DIRECTION")) {
                String dStr = (String) msg.get("DIRECTION");
                Direction nd = parseDir(dStr, Direction.NORTH);
                Position cur = stationRef.getRobotPosition(robotName);
                if (cur != null) {
                    cur.setDir(nd);
                    System.out.println("[" + robotName + "] => ROTATED => now facing => " + nd);
                }
            }
        }

        private void handleCharged(Map<String, Object> msg) {
            Map<String, Object> st = (Map<String, Object>) msg.get("STATUS");
            if (st != null) handleStatusData(st);
            System.out.println("[" + robotName + "] => CHARGED => energy=" + robotEnergy + ", temp=" + robotTemp);
        }

        private void handleStatus(Map<String, Object> msg) {
            Map<String, Object> st = (Map<String, Object>) msg.get("STATUS");
            if (st != null) handleStatusData(st);
        }

        private void handlePos(Map<String, Object> msg) {
            Map<String, Object> posMap = (Map<String, Object>) msg.get("POSITION");
            if (posMap != null) {
                updatePosition(posMap);
                System.out.println("[" + robotName + "] => GETPOS => updated => " + posMap);
                if (pendingLandingMeasure != null) {
                    Position cur = stationRef.getRobotPosition(robotName);
                    if (cur != null) {
                        handleMeasurementInternal(pendingLandingMeasure, cur.getX(), cur.getY());
                        stationRef.releaseField(cur);
                        hasLanded = true;
                        System.out.println("[" + robotName + "] => Landed measure recorded => " + cur);
                        if (isAutonomous && autoPilotThreadHasStopped()) {
                            startAutoPilot();
                        }
                    }
                    pendingLandingMeasure = null;
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
                    System.out.println("[" + robotName + "] => WARN_LOW_ENERGY => charging if autopilot.");
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
                    System.out.println("[" + robotName + "] => MOVE_STOP => re-attempt next iteration");
                    needReAttempt = true;
                    break;
                case "MOVE_DIRECTION_CHANGED":
                    System.out.println("[" + robotName + "] => direction changed => getpos");
                    sendJson(ExoPlanetProtocol.buildGetPosCmd());
                    break;
                default:
                    System.out.println("[" + robotName + "] => unknown status => " + event);
            }
        }

        private Measure parseMeasurement(Map<String, Object> measureMap) {
            try {
                String gstr = (String) measureMap.get("GROUND");
                float tmpVal = asFloat(measureMap.get("TEMP"), 0f);
                Ground g = Ground.valueOf(gstr.toUpperCase(Locale.ROOT));
                return new Measure(g, tmpVal);
            } catch (Exception e) {
                System.out.println("[" + robotName + "] measure parse error => " + measureMap);
                return null;
            }
        }

        private void handleMeasurement(Map<String, Object> measureMap, int x, int y) {
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
    // CONSOLE UI
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
                        showStats();
                        break;
                    case "map":
                        drawMap();
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
            System.out.println("[stats] => show exploration stats (including percentage explored)");
            System.out.println("[map]   => draw a simple map of the planet");
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

        private void showStats() {
            int totalFields = stationRef.getPlanetWidth() * stationRef.getPlanetHeight();
            if (totalFields <= 0) {
                System.out.println("[Stats] Planet size not set or invalid. Explored: " + stationRef.getExploredFields().size());
                return;
            }
            int exploredCount = stationRef.getExploredFields().size();
            float perc = (exploredCount / (float) totalFields) * 100f;
            System.out.printf("[Stats] Planet: %s => Explored %d of %d fields => %.2f%%\n",
                    stationRef.getPlanetName(), exploredCount, totalFields, perc);
        }

        private void drawMap() {
            int w = stationRef.getPlanetWidth();
            int h = stationRef.getPlanetHeight();
            if (w < 1 || h < 1) {
                System.out.println("[Map] Planet size not known, cannot draw map.");
                return;
            }
            System.out.println("[Map] Drawing planet " + stationRef.getPlanetName() + ": w=" + w + ", h=" + h);
            for (int y = 0; y < h; y++) {
                StringBuilder row = new StringBuilder();
                for (int x = 0; x < w; x++) {
                    Position p = new Position(x, y);
                    // check if a robot is here
                    boolean isRobot = false;
                    for (Map.Entry<String, Position> e : stationRef.robotPositions.entrySet()) {
                        Position rp = e.getValue();
                        if (rp.getX() == x && rp.getY() == y) {
                            row.append("R"); // robot
                            isRobot = true;
                            break;
                        }
                    }
                    if (isRobot) continue;

                    // else check explored
                    Measure m = stationRef.exploredFields.get(p);
                    if (m != null) {
                        switch (m.getGround()) {
                            case NICHTS:
                                row.append("X");
                                break; // crash or empty
                            case SAND:
                                row.append(".");
                                break;
                            case GEROELL:
                                row.append("g");
                                break;
                            case FELS:
                                row.append("F");
                                break;
                            case WASSER:
                                row.append("~");
                                break;
                            case PFLANZEN:
                                row.append("P");
                                break;
                            case MORAST:
                                row.append("M");
                                break;
                            case LAVA:
                                row.append("L");
                                break;
                        }
                    } else {
                        row.append("?"); // unknown
                    }
                }
                System.out.println(row.toString());
            }
        }

        private void robotSubMenu(RobotSession robot) {
            boolean running = true;
            while (running && !Thread.currentThread().isInterrupted() && robot.isAlive()) {
                showSubMenu(robot);
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
                    case "l":
                        manualLand(robot);
                        break;
                    case "s":
                        showRobotStatus(robot);
                        break;
                    case "b":
                        running = false;
                        break;
                    default:
                        System.out.println("Unknown => " + cmd);
                }
            }
        }

        private void showSubMenu(RobotSession robot) {
            System.out.println("\n-- SubMenu for " + robot.getRobotName() + " --");
            System.out.println("[1] toggle autonomy");
            System.out.println("[2] manual move");
            System.out.println("[3] manual scan");
            System.out.println("[4] mvscan");
            System.out.println("[5] rotate left/right");
            System.out.println("[c] charge(5s)");
            System.out.println("[gp] getpos");
            System.out.println("[l] land (manual land command)");
            System.out.println("[s] get status of robot");
            System.out.println("[x] exit command");
            System.out.println("[b] back");
            System.out.print("> ");
        }

        private void manualLand(RobotSession robot) {
            System.out.println("Enter landing coords => x y direction:");
            if (!consoleScanner.hasNextLine()) return;
            String line = consoleScanner.nextLine().trim();
            String[] parts = line.split("\\s+");
            if (parts.length < 3) {
                System.out.println("Usage: x y [NORTH|EAST|SOUTH|WEST]");
                return;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                Direction dir = Direction.valueOf(parts[2].toUpperCase(Locale.ROOT));
                robot.sendJson(ExoPlanetProtocol.buildLandCmd(x, y, dir));
            } catch (Exception e) {
                System.out.println("Invalid land input => " + e);
            }
        }

        private void showRobotStatus(RobotSession robot) {
            System.out.println("\n-- Robot Status for " + robot.getRobotName() + " --");
            System.out.println("Autonomous: " + robot.isAutonomous);
            Position p = getRobotPosition(robot.getRobotName());
            if (p != null) {
                System.out.println("Position: (" + p.getX() + "," + p.getY() + ") Dir=" + p.getDir());
            } else {
                System.out.println("Position: unknown");
            }
            System.out.println("Energy: " + robot.robotEnergy);
            System.out.println("Temp: " + robot.robotTemp);
        }
    }
}
