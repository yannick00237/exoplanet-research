package bodenstation;

import exo.Direction;
import exo.Ground;
import exo.Measure;
import exo.Position;
import exo.Rotation;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

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
    private static final Type MAP_TYPE = new TypeToken<Map<String,Object>>(){}.getType();

    public static Map<String,Object> fromJson(String json) {
        if(json == null) return null;
        return GSON.fromJson(json, MAP_TYPE);
    }
    public static String toJson(Map<String,Object> map) {
        return GSON.toJson(map);
    }

    public static ExoPlanetCmd getCmd(Map<String,Object> msg) {
        if(msg==null) return ExoPlanetCmd.UNKNOWN;
        Object cmdObj = msg.get("CMD");
        if(!(cmdObj instanceof String)) return ExoPlanetCmd.UNKNOWN;
        String cmdStr = ((String)cmdObj).toUpperCase(Locale.ROOT);
        try {
            return ExoPlanetCmd.valueOf(cmdStr);
        } catch(Exception e) {
            return ExoPlanetCmd.UNKNOWN;
        }
    }

    public static String buildOrbitCmd(String robotName) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","orbit");
        cmd.put("NAME", robotName);
        return toJson(cmd);
    }
    public static String buildLandCmd(int x, int y, Direction dir) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","land");
        Map<String,Object> pos = new HashMap<>();
        pos.put("X", x);
        pos.put("Y", y);
        pos.put("DIRECTION", dir.name());
        cmd.put("POSITION", pos);
        return toJson(cmd);
    }
    public static String buildMoveCmd() {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","move");
        return toJson(cmd);
    }
    public static String buildScanCmd() {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","scan");
        return toJson(cmd);
    }
    public static String buildMvScanCmd() {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","mvscan");
        return toJson(cmd);
    }
    public static String buildRotateCmd(Rotation rot) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","rotate");
        cmd.put("ROTATION", rot.name());
        return toJson(cmd);
    }
    public static String buildChargeCmd(int duration) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","charge");
        cmd.put("DURATION", duration);
        return toJson(cmd);
    }
    public static String buildGetPosCmd() {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD", "getpos");
        return toJson(cmd);
    }
    public static String buildExitCmd() {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD", "exit");
        return toJson(cmd);
    }
}

/**
 * Main Bodenstation application.
 */
public class Bodenstation {
    // Planet info (initially unknown => -1)
    private volatile String planetName    = "UnknownPlanet";
    private volatile int planetWidth     = -1;
    private volatile int planetHeight    = -1;

    // Robot sessions
    private final Map<String, RobotSession> sessions = new ConcurrentHashMap<>();
    // Explored fields (x,y => measure)
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
    public void setPlanetSize(int w, int h) {
        this.planetWidth  = w;
        this.planetHeight = h;
        System.out.println("[Bodenstation] Planet size => w="+w+" h="+h);
    }
    public int getPlanetWidth()  { return planetWidth; }
    public int getPlanetHeight() { return planetHeight; }

    // Field data
    public Map<Position, Measure> getExploredFields() {
        return exploredFields;
    }
    public boolean allFieldsExplored() {
        if(planetWidth<1 || planetHeight<1) return false;
        return exploredFields.size() >= (planetWidth * planetHeight);
    }
    public void updateFieldMeasurement(int x, int y, Measure measure) {
        exploredFields.put(new Position(x,y), measure);
    }

    // Robot positions
    public void setRobotPosition(String robot, int x, int y, Direction d) {
        robotPositions.put(robot, new Position(x,y,d));
    }
    public Position getRobotPosition(String robot) {
        return robotPositions.get(robot);
    }

    // Reservation-based collision avoidance
    public synchronized boolean reserveCell(Position cell) {
        // If already reserved or occupied => fail
        if(reservedPositions.contains(cell)) {
            return false;
        }
        for(Position rp : robotPositions.values()) {
            if(rp.getX()==cell.getX() && rp.getY()==cell.getY()) {
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
        if(serverAcceptor!=null) {
            serverAcceptor.interrupt();
            try {
                serverAcceptor.closeServer();
            } catch(IOException e) {
                e.printStackTrace();
            }
        }
        for(RobotSession rs : sessions.values()) {
            rs.interrupt();
            rs.closeSocket();
        }
        if(consoleUI!=null) {
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
                System.out.println("[ServerAcceptor] Listening on port "+port);

                while(!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket client = serverSocket.accept();
                        String robotName = "Robot-"+UUID.randomUUID().toString().substring(0,8);
                        RobotSession rs = new RobotSession(client, robotName, stationRef);
                        stationRef.registerSession(robotName, rs);
                        rs.start();
                        System.out.println("New Robot => "+robotName);
                    } catch(SocketTimeoutException e) {
                        // no connection => loop
                    }
                }
            } catch(IOException e) {
                System.out.println("[ServerAcceptor] Could not listen on port "+port+": " + e);
            }
            System.out.println("[ServerAcceptor] ends");
        }

        public void closeServer() throws IOException {
            if(serverSocket!=null) {
                serverSocket.close();
            }
        }
    }

    // ------------------------------------------------------------------------------
    // ROBOT SESSION
    // ------------------------------------------------------------------------------
    class RobotSession extends Thread {
        private static final int SOCKET_TIMEOUT_MS = 2000;
        private static final int ACTION_SLEEP_MS   = 500;
        private static final int CHARGE_DURATION_S = 5;

        private final Socket socket;
        private final String robotName;
        private final Bodenstation stationRef;

        private PrintWriter writer;
        private BufferedReader reader;

        private boolean isCrashed          = false;
        private boolean isAutonomous       = false;
        private boolean planetInitReceived = false;

        // Robot states updated only by `status` or `charged`
        private float robotTemp   = 20f;
        private float robotEnergy = 100f;

        private Thread autoPilotThread;

        // BFS: store all unvisited positions
        private final Set<Position> toExplore = ConcurrentHashMap.newKeySet();

        // Movement re-attempt if we get a MOVE_STOP
        private Position lastMoveTarget = null;    // Where we were trying to go
        private boolean   needReAttempt = false;   // If we get MOVE_STOP

        // Attempt to handle STUCK_IN_MUD => left first, then right
        private boolean stuckLeftTried = false; // if we do left rotation for STUCK_IN_MUD, next time do right

        public RobotSession(Socket client, String name, Bodenstation station) {
            this.socket     = client;
            this.robotName  = name;
            this.stationRef = station;
        }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                writer = new PrintWriter(socket.getOutputStream(), true);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

                // 1) Send orbit
                sendJson(ExoPlanetProtocol.buildOrbitCmd(robotName));

                while(!Thread.currentThread().isInterrupted() && !isCrashed) {
                    String line;
                    try {
                        line = reader.readLine();
                        if(line==null) {
                            System.out.println("["+robotName+"] Connection closed by remote.");
                            break;
                        }
                        Map<String,Object> msg = ExoPlanetProtocol.fromJson(line);
                        ExoPlanetCmd cmd = ExoPlanetProtocol.getCmd(msg);
                        handleIncoming(cmd, msg);

                    } catch(SocketTimeoutException e) {
                        // continue
                    }
                }
            } catch(IOException e) {
                System.out.println("["+robotName+"] Connection lost => "+e);
            } finally {
                stopAutoPilot();
                closeSocket();
                stationRef.unregisterSession(robotName);
                System.out.println("["+robotName+"] Session ended.");
            }
        }

        // Send JSON command
        public void sendJson(String cmd) {
            if(writer!=null) writer.println(cmd);
        }

        // Autonomy
        public synchronized void setAutonomous(boolean auto) {
            if(isCrashed) {
                System.out.println("["+robotName+"] can't go autonomous => crashed");
                return;
            }
            isAutonomous = auto;
            System.out.println("["+robotName+"] Autonomy => "+auto);
            if(auto && planetInitReceived) {
                startAutoPilot();
            } else if(!autoPilotThreadHasStopped()) {
                stopAutoPilot();
            }
        }
        private boolean autoPilotThreadHasStopped() {
            return (autoPilotThread==null || !autoPilotThread.isAlive());
        }

        private void startAutoPilot() {
            if(autoPilotThread!=null && autoPilotThread.isAlive()) {
                autoPilotThread.interrupt();
            }
            autoPilotThread = new Thread(this::runAutoPilot, "AutoPilot-"+robotName);
            autoPilotThread.start();
        }
        private void stopAutoPilot() {
            if(autoPilotThread!=null) {
                autoPilotThread.interrupt();
                autoPilotThread=null;
            }
        }

        public boolean isAutonomous() { return isAutonomous; }
        public String getRobotName()  { return robotName; }

        // ------------------------------------------------------------------
        // BFS-based autopilot
        // ------------------------------------------------------------------
        private void runAutoPilot() {
            System.out.println("["+robotName+"] autopilot started...");
            try {
                fillToExploreSet(); // fill BFS set with all coords

                // Attempt landing
                attemptLanding();

                // BFS main loop
                while(!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                    // Remove any newly measured from "toExplore"
                    removeExploredFromSet();

                    // If fully explored => exit
                    if(stationRef.allFieldsExplored()) {
                        System.out.println("["+robotName+"] Planet fully explored => exit");
                        sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    }

                    // pick next unvisited
                    Position next = pickUnvisited();
                    if(next==null) {
                        System.out.println("["+robotName+"] no unvisited => exit");
                        sendJson(ExoPlanetProtocol.buildExitCmd());
                        break;
                    }
                    // If we have low energy => immediate charge
                    if(robotEnergy < 10f) {
                        System.out.println("["+robotName+"] Low energy => immediate charge");
                        sendJson(ExoPlanetProtocol.buildChargeCmd(CHARGE_DURATION_S));
                        // wait for "charged" or status
                        Thread.sleep((CHARGE_DURATION_S+1)*1000L);
                        continue; // BFS re-check
                    }

                    // If we need to re-attempt a move due to a prior "MOVE_STOP"
                    if(needReAttempt && lastMoveTarget!=null) {
                        System.out.println("["+robotName+"] Re-attempting last move to => "+ lastMoveTarget);
                        moveStepByStep(lastMoveTarget.getX(), lastMoveTarget.getY());
                        needReAttempt = false;
                        continue; // then do a mvscan
                    }

                    // normal BFS => move to "next"
                    moveStepByStep(next.getX(), next.getY());
                    // do mvscan => combined move+scan
                    sendJson(ExoPlanetProtocol.buildMvScanCmd());
                    Thread.sleep(ACTION_SLEEP_MS);
                }

            } catch(InterruptedException e) {
                System.out.println("["+robotName+"] autopilot interrupted");
            }
            System.out.println("["+robotName+"] autopilot ended.");
        }

        private void fillToExploreSet() {
            toExplore.clear();
            int w = stationRef.getPlanetWidth();
            int h = stationRef.getPlanetHeight();
            if(w<1 || h<1) return; // not known
            for(int y=0; y<h; y++){
                for(int x=0; x<w; x++){
                    toExplore.add(new Position(x,y));
                }
            }
        }
        private void removeExploredFromSet() {
            for(Position p: stationRef.getExploredFields().keySet()) {
                toExplore.remove(new Position(p.getX(), p.getY()));
            }
        }
        private Position pickUnvisited() {
            for(Position p: toExplore) {
                return p;
            }
            return null;
        }

        private void attemptLanding() throws InterruptedException {
            // find any free cell => default (0,0)
            Position landing = new Position(0,0, Direction.NORTH);
            // You could do more logic to find truly free cell
            sendJson(ExoPlanetProtocol.buildLandCmd(landing.getX(), landing.getY(), landing.getDir()));
            Thread.sleep(ACTION_SLEEP_MS);
        }

        private void moveStepByStep(int tx, int ty) throws InterruptedException {
            Position cur = stationRef.getRobotPosition(robotName);
            if(cur==null) {
                System.out.println("["+robotName+"] We have no known position => skip BFS move");
                return;
            }
            lastMoveTarget = new Position(tx,ty); // store for reattempt if needed

            while(!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                int dx = tx - cur.getX();
                int dy = ty - cur.getY();
                if(dx==0 && dy==0) break; // reached

                Direction neededDir = null;
                if(Math.abs(dx)>0) {
                    neededDir = (dx>0)? Direction.EAST: Direction.WEST;
                } else if(Math.abs(dy)>0) {
                    neededDir = (dy>0)? Direction.SOUTH: Direction.NORTH;
                }
                if(neededDir!=null && cur.getDir()!=neededDir) {
                    Rotation rot = computeRotation(cur.getDir(), neededDir);
                    sendJson(ExoPlanetProtocol.buildRotateCmd(rot));
                    Thread.sleep(ACTION_SLEEP_MS);
                } else {
                    // Attempt to reserve nextCell
                    Position nextCell = nextPosition(cur);
                    if(!stationRef.reserveCell(nextCell)) {
                        // Can't move => maybe rotate or skip
                        System.out.println("["+robotName+"] nextCell is reserved/occupied => rotate or skip");
                        sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                        Thread.sleep(ACTION_SLEEP_MS);
                        continue;
                    }
                    // now do move
                    sendJson(ExoPlanetProtocol.buildMoveCmd());
                    Thread.sleep(ACTION_SLEEP_MS*2);
                    stationRef.releaseCell(nextCell);
                }
                cur = stationRef.getRobotPosition(robotName);
                if(cur==null) break; // might have crashed
            }
        }

        private Position nextPosition(Position pos) {
            int x = pos.getX();
            int y = pos.getY();
            switch(pos.getDir()) {
                case NORTH: y--; break;
                case SOUTH: y++; break;
                case EAST:  x++; break;
                case WEST:  x--; break;
            }
            return new Position(x,y);
        }

        private Rotation computeRotation(Direction currentDir, Direction targetDir) {
            List<Direction> circle = Arrays.asList(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
            int ci = circle.indexOf(currentDir);
            int ti = circle.indexOf(targetDir);
            int diff = ti - ci;
            if(diff<0) diff+=4;
            if(diff==1) return Rotation.RIGHT;
            if(diff==3) return Rotation.LEFT;
            // fallback => 2 => do RIGHT
            return Rotation.RIGHT;
        }

        // ----------------------------------------------------------------
        // handleIncoming
        // ----------------------------------------------------------------
        private void handleIncoming(ExoPlanetCmd cmd, Map<String,Object> msg) {
            switch(cmd) {
                case INIT:
                    handleInit(msg);
                    break;
                case LANDED:
                    handleLanded(msg);
                    break;
                case SCANED:
                case MVSCANED:
                    handleScanned(cmd, msg);
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
                    System.out.println("["+robotName+"] CRASHED => end session");
                    isCrashed=true;
                    break;
                case ERROR:
                    System.out.println("["+robotName+"] ERROR => "+msg);
                    break;
                case EXIT:
                    System.out.println("["+robotName+"] Robot says exit => end session");
                    isCrashed=true;
                    break;
                default:
                    System.out.println("["+robotName+"] unhandled => "+cmd+" => "+msg);
            }
        }

        private void handleInit(Map<String,Object> msg) {
            Map<String,Object> sizeMap = (Map<String,Object>) msg.get("SIZE");
            if(sizeMap!=null) {
                int w = asInt(sizeMap.get("WIDTH"), -1);
                int h = asInt(sizeMap.get("HEIGHT"),-1);
                stationRef.setPlanetSize(w,h);
                planetInitReceived=true;
                if(isAutonomous) {
                    startAutoPilot();
                }
            }
        }
        private void handleLanded(Map<String,Object> msg) {
            Map<String,Object> measure = (Map<String,Object>) msg.get("MEASURE");
            if(measure!=null) {
                handleMeasurement(measure, getPosX(), getPosY());
            }
        }
        private void handleScanned(ExoPlanetCmd c, Map<String,Object> msg) {
            Map<String,Object> measure = (Map<String,Object>) msg.get("MEASURE");
            if(measure!=null) {
                handleMeasurement(measure, getPosX(), getPosY());
            }
            if(msg.containsKey("POSITION")) {
                Map<String,Object> posMap = (Map<String,Object>) msg.get("POSITION");
                updatePosition(posMap);
            }
        }
        private void handleMoved(Map<String,Object> msg) {
            Map<String,Object> posMap = (Map<String,Object>) msg.get("POSITION");
            if(posMap!=null) {
                updatePosition(posMap);
            }
        }
        private void handleRotated(Map<String,Object> msg) {
            if(msg.containsKey("DIRECTION")) {
                String dStr= (String) msg.get("DIRECTION");
                Direction nd= parseDir(dStr, Direction.NORTH);
                Position cur= stationRef.getRobotPosition(robotName);
                if(cur!=null) {
                    cur.setDir(nd);
                }
            }
        }
        private void handleCharged(Map<String,Object> msg) {
            Map<String,Object> st = (Map<String,Object>) msg.get("STATUS");
            if(st!=null) handleStatusData(st);
            System.out.println("["+robotName+"] CHARGED => energy="+robotEnergy+", temp="+robotTemp);
        }
        private void handleStatus(Map<String,Object> msg) {
            Map<String,Object> st = (Map<String,Object>) msg.get("STATUS");
            if(st!=null) handleStatusData(st);
        }

        private void handleStatusData(Map<String,Object> stMap) {
            robotTemp   = asFloat(stMap.get("TEMP"), robotTemp);
            robotEnergy = asFloat(stMap.get("ENERGY"), robotEnergy);

            String message = (String) stMap.get("MESSAGE");
            if(message==null || message.isEmpty()) return;
            String[] tokens = message.split("\\|");
            for(String t: tokens) {
                t = t.trim();
                if(t.contains("=")) {
                    // e.g. "MOTOR=50"
                    parsePartStatus(t);
                } else {
                    handleStatusEvent(t);
                }
            }
        }

        private void parsePartStatus(String eq) {
            // e.g. "ROT_R=70"
            String[] parts = eq.split("=");
            if(parts.length==2) {
                String comp= parts[0].trim();
                int val   = asInt(parts[1],100);
                System.out.println("["+robotName+"] PART "+comp+" => "+val+"%");
            }
        }

        private void handleStatusEvent(String event) {
            switch(event) {
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
                    System.out.println("["+robotName+"] status => "+event);
                    break;

                case "WARN_LOW_ENERGY":
                    System.out.println("["+robotName+"] => WARN_LOW_ENERGY => immediate charge if autopilot");
                    if(isAutonomous) {
                        sendJson(ExoPlanetProtocol.buildChargeCmd(CHARGE_DURATION_S));
                    }
                    break;

                case "STUCK_IN_MUD":
                    System.out.println("["+robotName+"] => STUCK_IN_MUD => rotate left first, next time right");
                    if(isAutonomous) {
                        if(!stuckLeftTried) {
                            sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.LEFT));
                            stuckLeftTried=true;
                        } else {
                            sendJson(ExoPlanetProtocol.buildRotateCmd(Rotation.RIGHT));
                            stuckLeftTried=false;
                        }
                    }
                    break;

                case "MOVE_STOP":
                    System.out.println("["+robotName+"] => MOVE_STOP => re-attempt next BFS iteration");
                    needReAttempt = true;
                    break;

                case "MOVE_DIRECTION_CHANGED":
                    System.out.println("["+robotName+"] => MOVE_DIRECTION_CHANGED => do getpos, recalc BFS if needed");
                    sendJson(ExoPlanetProtocol.buildGetPosCmd());
                    break;

                default:
                    System.out.println("["+robotName+"] => unknown status => "+event);
            }
        }

        private void handleMeasurement(Map<String,Object> measureMap, int x, int y) {
            try {
                String gstr= (String) measureMap.get("GROUND");
                float tmp = asFloat(measureMap.get("TEMP"),0f);
                Ground gr= Ground.valueOf(gstr.toUpperCase(Locale.ROOT));
                Measure meas= new Measure(gr, tmp);

                stationRef.updateFieldMeasurement(x,y, meas);
                System.out.println("["+robotName+"] measured => ("+x+","+y+") => "+meas);
            } catch(Exception e) {
                System.out.println("["+robotName+"] measure parse error => "+measureMap);
            }
        }

        private void updatePosition(Map<String,Object> posMap) {
            int x = asInt(posMap.get("X"),0);
            int y = asInt(posMap.get("Y"),0);
            String dStr = (String) posMap.get("DIRECTION");
            Direction nd= parseDir(dStr, Direction.NORTH);
            stationRef.setRobotPosition(robotName, x,y, nd);
        }

        // I/O
        public void closeSocket() {
            try {
                if(socket!=null && !socket.isClosed()) {
                    socket.close();
                }
            } catch(IOException e) {
                e.printStackTrace();
            }
        }

        // Helpers
        private int asInt(Object val, int fallback) {
            if(val instanceof Number) return ((Number)val).intValue();
            return fallback;
        }
        private float asFloat(Object val, float fallback) {
            if(val instanceof Number) return ((Number)val).floatValue();
            return fallback;
        }
        private Direction parseDir(String s, Direction def) {
            if(s==null) return def;
            try {
                return Direction.valueOf(s.toUpperCase());
            } catch(Exception e) {
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
            while(!Thread.currentThread().isInterrupted()) {
                showMenu();
                if(!consoleScanner.hasNextLine()) break;
                String line = consoleScanner.nextLine().trim();
                if(line.isEmpty()) continue;

                switch(line.toLowerCase()) {
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
                        System.out.println("Explored fields => "+ stationRef.getExploredFields().size());
                        System.out.println("Planet => width="+stationRef.getPlanetWidth()
                                +", height="+stationRef.getPlanetHeight());
                        break;
                    default:
                        System.out.println("Unknown command => "+line);
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
            if(sess.isEmpty()) {
                System.out.println("No robots connected.");
                return;
            }
            for(String robotKey : sess.keySet()) {
                RobotSession rs = sess.get(robotKey);
                System.out.println(" * "+robotKey+" => autonomous="+rs.isAutonomous());
            }
        }

        private void selectRobot() {
            System.out.println("Enter Robot Name:");
            if(!consoleScanner.hasNextLine()) return;
            String rName = consoleScanner.nextLine().trim();
            RobotSession rs = stationRef.getSessions().get(rName);
            if(rs==null) {
                System.out.println("Robot not found => "+rName);
                return;
            }
            robotSubMenu(rs);
        }

        private void robotSubMenu(RobotSession robot) {
            boolean running=true;
            while(running && !Thread.currentThread().isInterrupted()) {
                System.out.println("\n-- SubMenu for "+robot.getRobotName()+" --");
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

                if(!consoleScanner.hasNextLine()) break;
                String cmd = consoleScanner.nextLine().trim().toLowerCase();
                switch(cmd) {
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
                        if(!consoleScanner.hasNextLine()) break;
                        String rotStr = consoleScanner.nextLine().trim().toUpperCase(Locale.ROOT);
                        Rotation rotation=null;
                        if("LEFT".equals(rotStr)) rotation=Rotation.LEFT;
                        else if("RIGHT".equals(rotStr)) rotation=Rotation.RIGHT;
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
                        running=false;
                        break;
                    default:
                        System.out.println("Unknown => "+cmd);
                }
            }
        }
    }
}
