package bodenstation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import exo.Measure;     // <-- import your exo.Measure
import exo.Ground;     // <-- import your exo.Ground

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// --------------------------------------------------
// Minimal enums / classes from your references
// (If you have them in separate files, adapt imports.)
// --------------------------------------------------
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

// Additional "Direction" if needed for BFS
enum Direction {
    NORTH, EAST, SOUTH, WEST
}

// --------------------------------------------------
// Expand your Position class to store X, Y, direction
// --------------------------------------------------
class Position {
    public int x;
    public int y;
    public String direction; // "NORTH","EAST","SOUTH","WEST"

    public Position(int x, int y, String dir) {
        this.x = x;
        this.y = y;
        this.direction = dir;
    }

    @Override
    public String toString() {
        return "["+x+","+y+"] dir="+direction;
    }

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof Position)) return false;
        Position other = (Position)o;
        return this.x==other.x && this.y==other.y && Objects.equals(this.direction, other.direction);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x,y,direction);
    }
}

// --------------------------------------------------
// Minimal Planet class
// --------------------------------------------------
class Planet {
    private String name;
    private int width;
    private int height;
    // Could store known temperatures etc., but we often rely on the server for actual data

    public Planet(String name, int w, int h) {
        this.name = name;
        this.width = w;
        this.height = h;
    }

    public String getName() { return name; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void setSize(int w, int h) {
        this.width = w;
        this.height = h;
    }
}

// --------------------------------------------------
// Hilfsklasse: Protokoll
// --------------------------------------------------
class ExoPlanetProtocol {
    private static final Gson gson = new Gson();
    private static final Type mapType = new TypeToken<Map<String, Object>>(){}.getType();

    public static Map<String, Object> fromJson(String json) {
        if(json==null) return null;
        return gson.fromJson(json, mapType);
    }

    public static String toJson(Map<String,Object> map) {
        return gson.toJson(map);
    }

    public static ExoPlanetCmd getCmd(Map<String,Object> msg) {
        if(msg==null) return ExoPlanetCmd.UNKNOWN;
        Object cmdObj = msg.get("CMD");
        if(!(cmdObj instanceof String)) return ExoPlanetCmd.UNKNOWN;
        String cmdStr = ((String)cmdObj).toUpperCase();
        try {
            return ExoPlanetCmd.valueOf(cmdStr);
        } catch (Exception e) {
            return ExoPlanetCmd.UNKNOWN;
        }
    }

    // -- Examples of building commands:
    public static String buildOrbitCmd(String robotName) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","orbit");
        cmd.put("NAME",robotName);
        return toJson(cmd);
    }
    public static String buildLandCmd(int x, int y, String dir) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","land");
        Map<String,Object> pos = new HashMap<>();
        pos.put("X",x);
        pos.put("Y",y);
        pos.put("DIRECTION", dir);
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
    public static String buildRotateCmd(String rotation) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","rotate");
        cmd.put("ROTATION", rotation);
        return toJson(cmd);
    }
    public static String buildChargeCmd(int duration) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","charge");
        cmd.put("DURATION", duration);
        return toJson(cmd);
    }
    // etc.
}

// --------------------------------------------------
// Your Measure class from exo package, referencing Ground, etc.
// We'll just reference it by import exo.Measure
// --------------------------------------------------


// --------------------------------------------------
// Hauptklasse: Bodenstation
// --------------------------------------------------
public class Bodenstation {
    // Sessions for each Robot
    private final Map<String, RobotSession> sessions = new ConcurrentHashMap<>();

    // Speichert, welche Felder bereits erkundet (sprich: gemessen) wurden
    // Key: Position (x,y, direction optional), Value: gemessene Daten
    private final Map<Position, Measure> exploredFields = new ConcurrentHashMap<>();

    // Speichert, wo sich welcher Roboter gerade befindet (ohne gemessenes Ground/Temp)
    private final Map<String, Position> robotPositions = new ConcurrentHashMap<>();

    // Planet (Name, size), updated upon init
    private Planet planet = new Planet("DefaultPlanet", 10, 10);

    private ServerAcceptor serverAcceptor;
    private ConsoleUI consoleUI;

    public static void main(String[] args) {
        Bodenstation app = new Bodenstation();
        app.start(9000);
    }

    public void start(int port) {
        System.out.println("Bodenstation starting on port "+port);
        serverAcceptor = new ServerAcceptor(port, this);
        serverAcceptor.start();

        consoleUI = new ConsoleUI(this);
        consoleUI.start();
    }

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

    public Map<Position, Measure> getExploredFields() {
        return exploredFields;
    }

    public Map<String, Position> getRobotPositions() {
        return robotPositions;
    }

    public Planet getPlanet() {
        return planet;
    }

    /** Wenn ein Robot scan/landed/mvscan => wir erhalten Measure => update in exploredFields */
    public synchronized void updateExploredField(int x, int y, Measure measure) {
        // direction optional -> we treat (x,y,null) as the "field"
        // or we store direction from the robot's perspective
        Position p = new Position(x,y,null);
        exploredFields.put(p, measure);
    }

    public synchronized void setRobotPosition(String robotName, int x, int y, String dir) {
        robotPositions.put(robotName, new Position(x,y,dir));
    }

    public synchronized Position getRobotPosition(String robotName) {
        return robotPositions.get(robotName);
    }

    public synchronized void setPlanetSize(int w, int h) {
        planet.setSize(w,h);
    }

    public synchronized boolean allFieldsExplored() {
        // if we want to check if we've measured every field in w*h
        // disclaimers: you might have water or invalid fields
        // For a simple approach, if exploredFields.size() == w*h => done
        return exploredFields.size() >= (planet.getWidth() * planet.getHeight());
    }

    public void shutdown() {
        System.out.println("Bodenstation shutting down...");
        if(serverAcceptor != null) {
            serverAcceptor.interrupt();
            try {
                serverAcceptor.closeServer();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        for(RobotSession rs : sessions.values()) {
            rs.interrupt();
            rs.closeSocket();
        }
        if(consoleUI != null) {
            consoleUI.interrupt();
        }
        System.out.println("Goodbye.");
        System.exit(0);
    }
}

// --------------------------------------------------
// ServerAcceptor - listens for incoming robot connections
// --------------------------------------------------
class ServerAcceptor extends Thread {
    private ServerSocket serverSocket;
    private final int port;
    private final Bodenstation appRef;

    public ServerAcceptor(int port, Bodenstation appRef) {
        this.port = port;
        this.appRef = appRef;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(2000);
            System.out.println("[ServerAcceptor] Listening on port "+port);

            while(!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
                    String robotName = "Robot-"+UUID.randomUUID().toString().substring(0,8);
                    RobotSession session = new RobotSession(client, robotName, appRef);
                    appRef.registerSession(robotName, session);
                    session.start();
                    System.out.println("New Robot => "+robotName);
                } catch(SocketTimeoutException e) {
                    // do nothing, loop again
                }
            }
        } catch (IOException e) {
            System.out.println("Could not listen on port "+port);
        }
        System.out.println("[ServerAcceptor] ends");
    }

    public void closeServer() throws IOException {
        if(serverSocket!=null) {
            serverSocket.close();
        }
    }
}

// --------------------------------------------------
// RobotSession: manages a single Robot from the vantage
// of the Bodenstation. Contains advanced autopilot logic
// to systematically explore the planet.
// --------------------------------------------------
class RobotSession extends Thread {
    private final Socket socket;
    private final String robotName;
    private final Bodenstation appRef;

    private PrintWriter out;
    private BufferedReader in;

    private volatile boolean isAutonomous = false;
    private Thread autoPilotThread; // We'll manage a separate thread or approach for BFS logic

    // Basic status simulation
    private float currentEnergy = 100f;
    private float currentTemp   = 20f;

    public RobotSession(Socket s, String rn, Bodenstation app) {
        this.socket = s;
        this.robotName = rn;
        this.appRef = app;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Maybe we do an 'orbit' automatically
            sendJson(ExoPlanetProtocol.buildOrbitCmd(robotName));

            // Listen for incoming messages
            while(!Thread.currentThread().isInterrupted()) {
                String line = in.readLine();
                if(line == null) break;

                Map<String,Object> msg = ExoPlanetProtocol.fromJson(line);
                ExoPlanetCmd cmd = ExoPlanetProtocol.getCmd(msg);
                handleIncoming(cmd, msg);
            }
        } catch(IOException e) {
            System.out.println("["+robotName+"] Connection lost");
        } finally {
            closeSocket();
            appRef.unregisterSession(robotName);
            stopAutoPilot();
            System.out.println("["+robotName+"] Session ended.");
        }
    }

    public void sendJson(String json) {
        if(out!=null) {
            out.println(json);
        }
    }

    public boolean getIsAutonomous() { return isAutonomous; }

    // Switch to/from Autonomous
    public synchronized void setAutonomous(boolean auto) {
        this.isAutonomous = auto;
        System.out.println("["+robotName+"] setAutonomous => "+auto);
        if(auto) {
            startAutoPilot();
        } else {
            stopAutoPilot();
        }
    }

    private void startAutoPilot() {
        if(autoPilotThread != null && autoPilotThread.isAlive()) {
            autoPilotThread.interrupt();
        }
        autoPilotThread = new Thread(this::runAutoPilot,"AutoPilot-"+robotName);
        autoPilotThread.start();
    }

    private void stopAutoPilot() {
        if(autoPilotThread != null) {
            autoPilotThread.interrupt();
            autoPilotThread = null;
        }
    }

    /**
     * Hier steckt die Logik, um den Planeten zu erkunden.
     * Wir machen exemplarisch eine BFS-ähnliche Vorgehensweise.
     * 1) Der Roboter muss sich im Planeten "init"-Status befinden (d.h. Planet is known).
     * 2) Landen, falls nicht geschehen
     * 3) Systematisch jedes Feld abklappern:
     *    - Move
     *    - Scan
     *    - Check Kollisionen
     *    - Check Energie/Temperatur -> ggf. charge
     * 4) Stop, wenn alle Felder in Bodenstation 'exploredFields' gemessen sind
     *    oder Crash/Exit
     */
    private void runAutoPilot() {
        System.out.println("["+robotName+"] AutoPilot started...");
        try {
            // Warte evtl. bis wir Planetgröße kennen (init)
            while(!Thread.currentThread().isInterrupted()) {
                // Prüfe planet init
                int w = appRef.getPlanet().getWidth();
                int h = appRef.getPlanet().getHeight();
                if(w>0 && h>0) break;
                Thread.sleep(500);
            }

            // Lande standardmäßig bei (0,0,NORTH)
            // (In der echten Welt könnte man sich eine Startposition aussuchen)
            sendJson(ExoPlanetProtocol.buildLandCmd(0,0,"NORTH"));
            // Warte bis 'landed' reinkommt -> Ggf. poll position

            // BFS: wir legen alle Koordinaten in eine Queue, klappern sie ab
            Set<String> visited = new HashSet<>(); // "x_y"
            Queue<int[]> toVisit = new LinkedList<>();

            int w = appRef.getPlanet().getWidth();
            int h = appRef.getPlanet().getHeight();
            for(int y=0; y<h; y++){
                for(int x=0; x<w; x++){
                    toVisit.offer(new int[]{x,y});
                }
            }

            while(!Thread.currentThread().isInterrupted() && isAutonomous) {
                // Check if everything explored
                if(appRef.allFieldsExplored()) {
                    System.out.println("["+robotName+"] All fields explored! Exiting planet...");
                    sendJson("{\"CMD\":\"exit\"}");
                    break;
                }
                if(toVisit.isEmpty()) {
                    System.out.println("["+robotName+"] BFS done, but maybe didn't measure all? Exiting anyway...");
                    sendJson("{\"CMD\":\"exit\"}");
                    break;
                }

                int[] target = toVisit.poll();
                String key = target[0]+"_"+target[1];
                if(visited.contains(key)) {
                    // already visited
                    continue;
                }
                visited.add(key);

                // Move from current pos to that pos step by step
                // (In real code you'd do pathfinding or a simpler step approach)
                moveTowards(target[0], target[1]);

                // Now scan
                sendJson(ExoPlanetProtocol.buildScanCmd());
                // Wait a bit for response
                Thread.sleep(1000);

                // Energy / Temp checks => if too low energy => charge
                if(currentEnergy < 20) {
                    System.out.println("["+robotName+"] Low energy => charging...");
                    sendJson(ExoPlanetProtocol.buildChargeCmd(5)); // e.g. 5 seconds
                    Thread.sleep(6000); // Wait for "charged" message
                }
                // If we detect collisions or stuck, we might do rotate etc.
            }

        } catch(InterruptedException e) {
            System.out.println("["+robotName+"] AutoPilot interrupted.");
        }
        System.out.println("["+robotName+"] AutoPilot ends.");
    }

    /**
     * Minimal "moveTowards" approach:
     * Move step by step until we reach target (x,y).
     * Check for collisions, etc.
     */
    private void moveTowards(int tx, int ty) throws InterruptedException {
        // We'll do a naive approach: move in x direction, then in y
        Position currentPos = appRef.getRobotPosition(robotName);
        if(currentPos==null) {
            currentPos = new Position(0,0,"NORTH");
            appRef.setRobotPosition(robotName,0,0,"NORTH");
        }

        while((currentPos.x != tx || currentPos.y != ty) && !Thread.currentThread().isInterrupted() && isAutonomous) {
            // Plan next step
            int dx = tx - currentPos.x;
            int dy = ty - currentPos.y;

            // If dx != 0, move horizontally
            if(dx>0 && !"EAST".equals(currentPos.direction)) {
                // rotate to EAST
                // we assume "rotate:RIGHT" or "LEFT" in steps
                sendJson(ExoPlanetProtocol.buildRotateCmd(findRotation(currentPos.direction, "EAST")));
                Thread.sleep(700);
            } else if(dx<0 && !"WEST".equals(currentPos.direction)) {
                sendJson(ExoPlanetProtocol.buildRotateCmd(findRotation(currentPos.direction, "WEST")));
                Thread.sleep(700);
            } else if(dy>0 && !"SOUTH".equals(currentPos.direction)) {
                sendJson(ExoPlanetProtocol.buildRotateCmd(findRotation(currentPos.direction, "SOUTH")));
                Thread.sleep(700);
            } else if(dy<0 && !"NORTH".equals(currentPos.direction)) {
                sendJson(ExoPlanetProtocol.buildRotateCmd(findRotation(currentPos.direction, "NORTH")));
                Thread.sleep(700);
            } else {
                // direction is correct, let's move
                // but first check collision
                if(isCollisionAhead(currentPos)) {
                    // Try rotate left or right randomly
                    System.out.println("["+robotName+"] Potential collision => rotate random");
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Math.random()>0.5 ? "LEFT":"RIGHT"));
                    Thread.sleep(500);
                    continue;
                }

                sendJson(ExoPlanetProtocol.buildMoveCmd());
                // Warte auf Moved => handleIncoming updates position
                Thread.sleep(1000);
            }
            currentPos = appRef.getRobotPosition(robotName);
            if(currentPos==null) break; // e.g. crashed
        }
    }

    /**
     * Prüft, ob an der nächsten Position (1 Feld in currentPos.direction) bereits ein Roboter steht.
     * Falls ja => Kollision droht => true
     */
    private boolean isCollisionAhead(Position currentPos) {
        // compute next cell in direction
        int nx = currentPos.x;
        int ny = currentPos.y;
        switch(currentPos.direction) {
            case "NORTH": ny--; break;
            case "SOUTH": ny++; break;
            case "EAST":  nx++; break;
            case "WEST":  nx--; break;
        }
        // Check if any other robot has that position
        for(Map.Entry<String,Position> e : appRef.getRobotPositions().entrySet()) {
            if(e.getKey().equals(robotName)) continue; // skip self
            Position p = e.getValue();
            if(p.x==nx && p.y==ny) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hilfsfunktion: Bestimmt, ob wir LEFT oder RIGHT drehen müssen,
     * um von currentDir nach targetDir zu kommen (naive approach).
     */
    private String findRotation(String currentDir, String targetDir) {
        // Just some logic table
        List<String> dirs = Arrays.asList("NORTH","EAST","SOUTH","WEST");
        int cIdx = dirs.indexOf(currentDir);
        int tIdx = dirs.indexOf(targetDir);
        if(cIdx<0 || tIdx<0) return "RIGHT"; // fallback
        // Distance in the ring
        int diff = tIdx - cIdx;
        if(diff<0) diff += 4; // mod 4
        // if diff is 1 => rotate RIGHT
        // if diff is 3 => rotate LEFT
        // if diff is 2 => rotate RIGHT twice (we do one rotate, wait, etc.)
        if(diff == 1) return "RIGHT";
        if(diff == 3) return "LEFT";
        return "RIGHT"; // fallback
    }

    /**
     * handleIncoming() - parse responses from the Robot
     */
    private void handleIncoming(ExoPlanetCmd cmd, Map<String,Object> msg) {
        switch(cmd) {
            case INIT:
                // e.g. {"CMD":"init","SIZE":{"WIDTH":20,"HEIGHT":15}}
                Map<String,Object> size = (Map<String,Object>) msg.get("SIZE");
                if(size!=null) {
                    int w = ((Double)size.get("WIDTH")).intValue();
                    int h = ((Double)size.get("HEIGHT")).intValue();
                    appRef.setPlanetSize(w,h);
                    System.out.println("["+robotName+"] Planet size => w="+w+" h="+h);
                }
                break;
            case LANDED:
                // e.g. {"CMD":"landed","MEASURE":{"GROUND":"SAND","TEMP":22.5}}
                // store measure in exploredFields
                Map<String,Object> measureMap = (Map<String,Object>) msg.get("MEASURE");
                if(measureMap!=null) {
                    handleMeasure(measureMap, getCurrentX(), getCurrentY());
                }
                break;
            case SCANED:
                // e.g. {"CMD":"scaned","MEASURE":{"GROUND":"FELS","TEMP":12.0}}
                Map<String,Object> meas2 = (Map<String,Object>) msg.get("MEASURE");
                if(meas2!=null) {
                    handleMeasure(meas2, getCurrentXInApp(), getCurrentYInApp());
                }
                break;
            case MOVED:
                // e.g. {"CMD":"moved","POSITION":{"X":10,"Y":5,"DIRECTION":"EAST"}}
                Map<String,Object> posMap = (Map<String,Object>) msg.get("POSITION");
                if(posMap!=null) {
                    int x = ((Double)posMap.get("X")).intValue();
                    int y = ((Double)posMap.get("Y")).intValue();
                    String dir = (String)posMap.get("DIRECTION");
                    appRef.setRobotPosition(robotName, x,y,dir);
                }
                break;
            case CHARGED:
                // e.g. {"CMD":"charged","STATUS":{"TEMP":..., "ENERGY":..., "MESSAGE":...}}
                Map<String,Object> st = (Map<String,Object>) msg.get("STATUS");
                if(st!=null) {
                    handleStatusUpdate(st);
                }
                break;
            case STATUS:
                // e.g. {"CMD":"status","STATUS":{"TEMP":..., "ENERGY":..., "MESSAGE":...}}
                Map<String,Object> st2 = (Map<String,Object>) msg.get("STATUS");
                if(st2!=null) {
                    handleStatusUpdate(st2);
                }
                break;
            case ROTATED:
            case MVSCANED:
                // handle similarly, store positions, measures etc.
                break;
            case CRASHED:
                System.out.println("["+robotName+"] CRASHED => session ends");
                this.interrupt();
                break;
            case ERROR:
                System.out.println("["+robotName+"] ERROR => "+msg);
                break;
            case EXIT:
                System.out.println("["+robotName+"] Robot requested exit => close session");
                this.interrupt();
                break;
            default:
                // do nothing / unknown
                System.out.println("["+robotName+"] Unknown CMD => "+cmd);
        }
    }

    /**
     * handleMeasure: Convert measureMap to exo.Measure and store in exploredFields
     */
    private void handleMeasure(Map<String,Object> measureMap, int x, int y) {
        try {
            String gstr = (String) measureMap.get("GROUND");
            float temp = ((Double)measureMap.get("TEMP")).floatValue();
            Ground g = Ground.valueOf(gstr.toUpperCase(Locale.ROOT));
            Measure m = new Measure(g, temp);
            appRef.updateExploredField(x,y,m);
            System.out.println("["+robotName+"] => measure on ("+x+","+y+") => "+m);
        } catch(Exception e) {
            System.out.println("["+robotName+"] measure parse error => "+measureMap);
        }
    }

    private void handleStatusUpdate(Map<String,Object> stMap) {
        float temp = ((Double)stMap.get("TEMP")).floatValue();
        float energy = ((Double)stMap.get("ENERGY")).floatValue();
        currentTemp = temp;
        currentEnergy = energy;
        String msg = (String) stMap.get("MESSAGE");
        System.out.println("["+robotName+"] status => temp="+temp+" energy="+energy+" msg="+msg);
    }

    private int getCurrentX() {
        Position p = appRef.getRobotPosition(robotName);
        return (p!=null)? p.x : 0;
    }
    private int getCurrentY() {
        Position p = appRef.getRobotPosition(robotName);
        return (p!=null)? p.y : 0;
    }
    // sometimes we prefer up-to-date from Bodenstation
    private int getCurrentXInApp() { return getCurrentX(); }
    private int getCurrentYInApp() { return getCurrentY(); }

    public void closeSocket() {
        try {
            if(socket!=null && !socket.isClosed()) {
                socket.close();
            }
        } catch(IOException e) {
            e.printStackTrace();
        }
    }
}

// --------------------------------------------------
// ConsoleUI for user commands, toggling autonomy, etc.
// --------------------------------------------------
class ConsoleUI extends Thread {
    private final Scanner sc = new Scanner(System.in);
    private final Bodenstation appRef;

    public ConsoleUI(Bodenstation app) {
        this.appRef = app;
    }

    @Override
    public void run() {
        while(!Thread.currentThread().isInterrupted()) {
            showMenu();
            String line = sc.nextLine().trim();
            if(line.isEmpty()) continue;

            switch(line.toLowerCase()) {
                case "exit":
                    appRef.shutdown();
                    return;
                case "ls":
                    listRobots();
                    break;
                case "sel":
                    selectRobot();
                    break;
                case "stats":
                    System.out.println("exploredFields size: "+appRef.getExploredFields().size());
                    break;
                default:
                    System.out.println("Unknown cmd: "+line);
            }
        }
    }

    private void showMenu() {
        System.out.println("\n--- BODENSTATION MENU ---");
        System.out.println("[ls]     -> list robots");
        System.out.println("[sel]    -> select a robot");
        System.out.println("[stats]  -> how many fields explored so far?");
        System.out.println("[exit]   -> exit application");
        System.out.print("> ");
    }

    private void listRobots() {
        Map<String,RobotSession> sess = appRef.getSessions();
        if(sess.isEmpty()) {
            System.out.println("No robots connected.");
            return;
        }
        for(Map.Entry<String,RobotSession> e : sess.entrySet()) {
            System.out.println(" - "+ e.getKey()+" (auto="+ e.getValue().getIsAutonomous() +")");
        }
    }

    private void selectRobot() {
        System.out.println("Enter RobotName:");
        String rName = sc.nextLine().trim();
        RobotSession rs = appRef.getSessions().get(rName);
        if(rs==null) {
            System.out.println("Robot not found: "+rName);
            return;
        }
        subMenu(rs);
    }

    private void subMenu(RobotSession rs) {
        boolean run = true;
        while(run && !Thread.currentThread().isInterrupted()) {
            System.out.println("\n--- SubMenu for "+rs.getName()+" ---");
            System.out.println("[1] toggle autonomy");
            System.out.println("[2] manual move");
            System.out.println("[3] manual scan");
            System.out.println("[c]  charge(5s) ");
            System.out.println("[b]  back");
            System.out.print("> ");
            String cmd = sc.nextLine().trim().toLowerCase();
            switch(cmd) {
                case "1":
                    rs.setAutonomous(!rs.getIsAutonomous());
                    break;
                case "2":
                    rs.sendJson(ExoPlanetProtocol.buildMoveCmd());
                    break;
                case "3":
                    rs.sendJson(ExoPlanetProtocol.buildScanCmd());
                    break;
                case "c":
                    rs.sendJson(ExoPlanetProtocol.buildChargeCmd(5));
                    break;
                case "b":
                    run=false;
                    break;
                default:
                    System.out.println("Unknown cmd: "+cmd);
            }
        }
    }
}
