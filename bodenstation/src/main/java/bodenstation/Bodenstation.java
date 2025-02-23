package bodenstation;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import exo.Measure;
import exo.Ground;
import exo.Direction;
import exo.Position;

import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


enum ExoPlanetCmd {
    ORBIT, INIT, LAND, LANDED,
    SCAN, SCANED,
    MOVE, MOVED,
    MVSCAN, MVSCANED,
    ROTATE, ROTATED,
    CRASHED, EXIT, ERROR,
    GETPOS, POS,           // Neu für advanced
    CHARGE, CHARGED,
    STATUS,                // Hier kommen Warnungen wie WARN_LOW_ENERGY, COOLER_ON etc.
    // EXPERT-Spezifische Meldungen wie PART_STATUS=xxx => Lenkung, Motor usw.
    PARTSTATUS,            // Falls wir z.B. {"CMD":"partstatus","PARTS":{"MOTOR":90,"HEATER":100,...}}
    UNKNOWN
}

class Planet {
    private String name;
    private int width;
    private int height;

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

class ExoPlanetProtocol {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>(){}.getType();

    public static Map<String, Object> fromJson(String json) {
        if(json == null) return null;
        return GSON.fromJson(json, MAP_TYPE);
    }

    public static String toJson(Map<String,Object> map) {
        return GSON.toJson(map);
    }

    public static ExoPlanetCmd getCmd(Map<String,Object> msg) {
        if(msg == null) return ExoPlanetCmd.UNKNOWN;
        Object cmdObj = msg.get("CMD");
        if(!(cmdObj instanceof String)) return ExoPlanetCmd.UNKNOWN;
        String cmdStr = ((String)cmdObj).toUpperCase(Locale.ROOT);
        try {
            return ExoPlanetCmd.valueOf(cmdStr);
        } catch (Exception e) {
            return ExoPlanetCmd.UNKNOWN;
        }
    }

    // ----- Beispiel: Standardkommandos -----

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

    public static String buildRotateCmd(String rotation) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","rotate");
        cmd.put("ROTATION", rotation);  // "LEFT"/"RIGHT"
        return toJson(cmd);
    }

    public static String buildChargeCmd(int duration) {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD","charge");
        cmd.put("DURATION", duration);
        return toJson(cmd);
    }

    // ----- Neue Kommandos für advanced/expert -----

    public static String buildGetPosCmd() {
        Map<String,Object> cmd = new HashMap<>();
        cmd.put("CMD", "getpos");
        return toJson(cmd);
    }
    // PARTSTATUS-Änderungen würde eher der Robot verschicken => "CMD":"partstatus"

}

// --------------------------------------------------
// Hauptklasse: Bodenstation
// --------------------------------------------------
public class Bodenstation {
    // Weniger Magic Numbers
    private static final int DEFAULT_PLANET_W = 10;
    private static final int DEFAULT_PLANET_H = 10;
    private static final int SERVER_SOCKET_TIMEOUT_MS = 2000;

    // Robot-Sessions
    private final Map<String, RobotSession> sessions = new ConcurrentHashMap<>();

    // Erkundete Felder
    private final Map<Position, Measure> exploredFields = new ConcurrentHashMap<>();
    // Aktuelle Roboterpositionen
    private final Map<String, Position> robotPositions = new ConcurrentHashMap<>();

    // Planet
    private Planet planet = new Planet("DefaultPlanet", DEFAULT_PLANET_W, DEFAULT_PLANET_H);

    private ServerAcceptor serverAcceptor;
    private ConsoleUI consoleUI;

    public static void main(String[] args) {
        Bodenstation app = new Bodenstation();
        app.start(9000);
    }

    public void start(int port) {
        System.out.println("Bodenstation starting on port " + port);
        serverAcceptor = new ServerAcceptor(port, this);
        serverAcceptor.start();

        consoleUI = new ConsoleUI(this);
        consoleUI.start();
    }

    // ------------- Session-Management -------------
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

    // ------------- Planet Data -------------
    public Planet getPlanet() {
        return planet;
    }
    public synchronized void setPlanetSize(int w, int h) {
        planet.setSize(w,h);
    }

    // ------------- Positions / Fields -------------
    public Map<Position, Measure> getExploredFields() {
        return exploredFields;
    }
    public Map<String, Position> getRobotPositions() {
        return robotPositions;
    }

    /** Aktueller Planet hat w*h Felder => falls exploredFields >= w*h, alle Felder erforscht */
    public synchronized boolean allFieldsExplored() {
        return exploredFields.size() >= (planet.getWidth() * planet.getHeight());
    }

    public synchronized void setRobotPosition(String robotName, int x, int y, Direction dir) {
        robotPositions.put(robotName, new Position(x, y, dir));
    }

    public synchronized Position getRobotPosition(String robotName) {
        return robotPositions.get(robotName);
    }

    /** Speichert ein gemessenes Feld in exploredFields. */
    public synchronized void updateExploredField(int x, int y, Measure measure) {
        // direction optional -> (x,y,dir) or just (x,y,null)
        Position p = new Position(x,y, Direction.NORTH); // or null if you prefer
        exploredFields.put(p, measure);
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

class ServerAcceptor extends Thread {
    private static final int SOCKET_ACCEPT_TIMEOUT_MS = 2000;

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
            serverSocket.setSoTimeout(SOCKET_ACCEPT_TIMEOUT_MS);
            System.out.println("[ServerAcceptor] Listening on port "+port);

            while(!Thread.currentThread().isInterrupted()) {
                try {
                    Socket client = serverSocket.accept();
                    String robotName = "Robot-"+UUID.randomUUID().toString().substring(0,8);
                    RobotSession rs = new RobotSession(client, robotName, appRef);
                    appRef.registerSession(robotName, rs);
                    rs.start();
                    System.out.println("New Robot => "+robotName);
                } catch(SocketTimeoutException e) {
                    // ignore, loop
                }
            }
        } catch(IOException e) {
            System.out.println("Could not listen on port "+port);
        }
        System.out.println("[ServerAcceptor] ends");
    }

    public void closeServer() throws IOException {
        if(serverSocket != null) {
            serverSocket.close();
        }
    }
}

// --------------------------------------------------
// RobotSession: hier Advanced/Expert-Logik
// --------------------------------------------------
class RobotSession extends Thread {
    // Magic constants
    private static final int SLEEP_BETWEEN_ACTIONS_MS = 500;
    private static final int AP_CHARGE_DURATION_SEC = 5; // e.g. default for charging
    private static final float ENERGY_CRITICAL = 20.0f;  // WarnLowEnergy threshold
    private static final float MOTOR_LOW_PERCENT = 50.0f;

    private final Socket socket;
    private final String robotName;
    private final Bodenstation appRef;

    private PrintWriter out;
    private BufferedReader in;

    // Expert-level part statuses
    private float motorStatus   = 100f;
    private float rotLeftStatus = 100f;
    private float rotRightStatus= 100f;
    private float coolerStatus  = 100f;
    private float heaterStatus  = 100f;
    private float sensorStatus  = 100f;

    private volatile boolean isAutonomous = false;
    private Thread autoPilotThread;

    // Basic Robot-Status
    private float currentEnergy   = 100f;
    private float currentTemp     = 20f;
    private boolean isCrashed     = false;

    public RobotSession(Socket s, String robotName, Bodenstation app) {
        this.socket = s;
        this.robotName = robotName;
        this.appRef = app;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Sende ORBIT
            sendJson(ExoPlanetProtocol.buildOrbitCmd(robotName));

            while(!Thread.currentThread().isInterrupted()) {
                String line = in.readLine();
                if(line == null) break;

                Map<String,Object> msg = ExoPlanetProtocol.fromJson(line);
                ExoPlanetCmd cmd = ExoPlanetProtocol.getCmd(msg);
                handleIncoming(cmd, msg);
                if(isCrashed) {
                    // no further actions if crashed
                    break;
                }
            }
        } catch(IOException e) {
            System.out.println("["+robotName+"] Connection lost");
        } finally {
            stopAutoPilot();
            closeSocket();
            appRef.unregisterSession(robotName);
            System.out.println("["+robotName+"] Session ended.");
        }
    }

    public void sendJson(String json) {
        if(out!=null) out.println(json);
    }

    public boolean isAutonomous() {
        return isAutonomous;
    }
    public String getRobotName() {
        return robotName;
    }

    public synchronized void setAutonomous(boolean auto) {
        if(isCrashed) {
            System.out.println("["+robotName+"] cannot setAutonomous: Robot crashed");
            return;
        }
        this.isAutonomous = auto;
        System.out.println("["+robotName+"] isAutonomous => "+auto);
        if(auto) {
            startAutoPilot();
        } else {
            stopAutoPilot();
        }
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
            autoPilotThread = null;
        }
    }

    // -------------------------
    // Advanced/Expert BFS-Logik
    // -------------------------
    private void runAutoPilot() {
        System.out.println("["+robotName+"] AutoPilot START...");
        try {
            // Warte, bis Planet init
            while(!Thread.currentThread().isInterrupted()) {
                int w = appRef.getPlanet().getWidth();
                int h = appRef.getPlanet().getHeight();
                if(w>0 && h>0) break;
                Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS);
            }

            // Land bei (0,0,NORTH)
            sendJson(ExoPlanetProtocol.buildLandCmd(0,0, Direction.NORTH));
            Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS);

            // BFS Queue
            Set<String> visited = new HashSet<>();
            Queue<int[]> toVisit = new LinkedList<>();

            int w = appRef.getPlanet().getWidth();
            int h = appRef.getPlanet().getHeight();
            for(int y=0; y<h; y++) {
                for(int x=0; x<w; x++) {
                    toVisit.offer(new int[]{x,y});
                }
            }

            while(!Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
                // Check if all fields explored
                if(appRef.allFieldsExplored()) {
                    System.out.println("["+robotName+"] All fields explored => exit");
                    sendJson("{\"CMD\":\"exit\"}");
                    break;
                }
                if(toVisit.isEmpty()) {
                    System.out.println("["+robotName+"] BFS done => exit");
                    sendJson("{\"CMD\":\"exit\"}");
                    break;
                }

                int[] target = toVisit.poll();
                String key = target[0]+"_"+target[1];
                if(visited.contains(key)) continue;
                visited.add(key);

                // Move step by step
                moveTowards(target[0], target[1]);
                if(isCrashed) break;

                // Now scan
                sendJson(ExoPlanetProtocol.buildScanCmd());
                Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS);

                // Check energy => if below threshold => charge
                if(currentEnergy < ENERGY_CRITICAL) {
                    System.out.println("["+robotName+"] Low energy => charging...");
                    sendJson(ExoPlanetProtocol.buildChargeCmd(AP_CHARGE_DURATION_SEC));
                    Thread.sleep((AP_CHARGE_DURATION_SEC+1)*1000L); // wait for charge
                }

                // Randomly degrade parts each BFS step for demonstration
                degradeRobotParts();
            }

        } catch(InterruptedException e) {
            System.out.println("["+robotName+"] Autopilot interrupted");
        }
        System.out.println("["+robotName+"] AutoPilot END");
    }

    /**
     * Je Move-Kommando:
     * - Falls motorStatus < 50 => höhere Chance zu "stuck" (könnte man hier simulieren).
     * - Falls sensorStatus < 50 => SCAN könnte fehlschlagen => Bodenstation erhält falsche measure.
     * - Etc.
     */
    private void moveTowards(int tx, int ty) throws InterruptedException {
        Position pos = appRef.getRobotPosition(robotName);
        if(pos==null) {
            pos = new Position(0,0, Direction.NORTH);
            appRef.setRobotPosition(robotName,0,0, Direction.NORTH);
        }
        while((pos.getX()!=tx || pos.getY()!=ty) && !Thread.currentThread().isInterrupted() && isAutonomous && !isCrashed) {
            int dx = tx - pos.getX();
            int dy = ty - pos.getY();

            // Rotate if needed
            Direction neededDir = null;
            if(Math.abs(dx)>0) {
                neededDir = (dx>0) ? Direction.EAST : Direction.WEST;
            } else if(Math.abs(dy)>0) {
                neededDir = (dy>0) ? Direction.SOUTH : Direction.NORTH;
            }
            if(neededDir!=null && pos.getDir()!=neededDir) {
                // rotate
                String rotation = computeRotation(pos.getDir(), neededDir);
                sendJson(ExoPlanetProtocol.buildRotateCmd(rotation));
                Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS);
            } else {
                // check collision
                if(isCollisionAhead(pos)) {
                    // rotate random
                    sendJson(ExoPlanetProtocol.buildRotateCmd(Math.random()>0.5 ? "LEFT":"RIGHT"));
                    Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS);
                } else {
                    // do move
                    if(motorStatus< MOTOR_LOW_PERCENT) {
                        // Maybe we have a random chance to get stuck
                        if(Math.random()<0.2) {
                            System.out.println("["+robotName+"] Motor < 50% => stuck! trying rotate...");
                            sendJson(ExoPlanetProtocol.buildRotateCmd(Math.random()>0.5 ? "LEFT":"RIGHT"));
                            Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS);
                            continue;
                        }
                    }
                    sendJson(ExoPlanetProtocol.buildMoveCmd());
                    Thread.sleep(SLEEP_BETWEEN_ACTIONS_MS*2);
                }
            }
            pos = appRef.getRobotPosition(robotName);
            if(pos==null) break;
        }
    }

    /**
     * Simple collision check: see if next step is occupied by another robot
     */
    private boolean isCollisionAhead(Position pos) {
        int nx = pos.getX();
        int ny = pos.getY();
        switch(pos.getDir()) {
            case NORTH: ny--; break;
            case EAST:  nx++; break;
            case SOUTH: ny++; break;
            case WEST:  nx--; break;
        }
        for(Map.Entry<String, Position> e : appRef.getRobotPositions().entrySet()) {
            if(e.getKey().equals(robotName)) continue;
            Position otherPos = e.getValue();
            if(otherPos.getX()==nx && otherPos.getY()==ny) {
                return true;
            }
        }
        return false;
    }

    /** Helper to decide LEFT/RIGHT rotation from currentDir to targetDir */
    private String computeRotation(Direction currentDir, Direction targetDir) {
        // We do a basic approach with an ordered list
        List<Direction> circle = Arrays.asList(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
        int cIdx = circle.indexOf(currentDir);
        int tIdx = circle.indexOf(targetDir);
        int diff = tIdx - cIdx;
        if(diff<0) diff += 4;
        // diff=1 => RIGHT, diff=3 => LEFT
        if(diff==1) return "RIGHT";
        if(diff==3) return "LEFT";
        return "RIGHT";
    }

    /**
     * degradeRobotParts: random degrade each part by up to 5%
     */
    private void degradeRobotParts() {
        motorStatus   = degradeOne(motorStatus);
        rotLeftStatus = degradeOne(rotLeftStatus);
        rotRightStatus= degradeOne(rotRightStatus);
        coolerStatus  = degradeOne(coolerStatus);
        heaterStatus  = degradeOne(heaterStatus);
        sensorStatus  = degradeOne(sensorStatus);

        // Optional: send a parted-status message to Bodenstation
        // so we can see e.g. {"CMD":"partstatus","PARTS":{"MOTOR":90,"HEATER":100,...}}
        // But typically it's the Robot that notifies. For demonstration:
        Map<String,Object> partsMsg = new HashMap<>();
        partsMsg.put("CMD","partstatus");
        Map<String,Object> pvals = new HashMap<>();
        pvals.put("MOTOR",   motorStatus);
        pvals.put("ROT_L",   rotLeftStatus);
        pvals.put("ROT_R",   rotRightStatus);
        pvals.put("COOLER",  coolerStatus);
        pvals.put("HEATER",  heaterStatus);
        pvals.put("SENSOR",  sensorStatus);
        partsMsg.put("PARTS", pvals);
        // In a real app, the Robot would send this to the server.
        // Here, we handle it internally for demonstration:
        handleIncoming(ExoPlanetCmd.PARTSTATUS, partsMsg);
    }

    private float degradeOne(float val) {
        if(val<=0) return 0;
        float degrade = (float) (Math.random()*5.0); // up to 5%
        val -= degrade;
        if(val<0) val=0;
        return val;
    }

    private void handleIncoming(ExoPlanetCmd cmd, Map<String,Object> msg) {
        switch(cmd) {
            case INIT:
                // {"CMD":"init","SIZE":{"WIDTH":20,"HEIGHT":15}}
                Map<String,Object> sizeMap = (Map<String,Object>) msg.get("SIZE");
                if(sizeMap!=null) {
                    int w = ((Double)sizeMap.get("WIDTH")).intValue();
                    int h = ((Double)sizeMap.get("HEIGHT")).intValue();
                    appRef.setPlanetSize(w,h);
                    System.out.println("["+robotName+"] Planet size => w="+w+" h="+h);
                }
                break;

            case LANDED:
                // {"CMD":"landed","MEASURE":{"GROUND":"SAND","TEMP":22.5}}
                Map<String,Object> measureMap = (Map<String,Object>) msg.get("MEASURE");
                if(measureMap!=null) {
                    handleMeasure(measureMap, getPosX(), getPosY());
                }
                break;

            case SCANED:
                // {"CMD":"scaned","MEASURE":{"GROUND":"FELS","TEMP":12.0}}
                Map<String,Object> meas = (Map<String,Object>) msg.get("MEASURE");
                if(meas!=null) {
                    handleMeasure(meas, getPosX(), getPosY());
                }
                break;

            case MOVED:
                // {"CMD":"moved","POSITION":{"X":..., "Y":..., "DIRECTION":"..."}}
                Map<String,Object> posObj = (Map<String,Object>) msg.get("POSITION");
                if(posObj!=null) {
                    int x = ((Double)posObj.get("X")).intValue();
                    int y = ((Double)posObj.get("Y")).intValue();
                    String dirStr = (String) posObj.get("DIRECTION");
                    Direction dir = Direction.valueOf(dirStr.toUpperCase(Locale.ROOT));
                    appRef.setRobotPosition(robotName, x,y, dir);
                    // Possibly adjust energy usage
                    handleEnergyForMove(x,y);
                }
                break;

            case ROTATED:
                // {"CMD":"rotated","DIRECTION":"EAST"}
                String dStr = (String) msg.get("DIRECTION");
                if(dStr!=null) {
                    Direction nd = Direction.valueOf(dStr.toUpperCase());
                    Position cpos = appRef.getRobotPosition(robotName);
                    if(cpos!=null) {
                        cpos.setDir(nd);
                    }
                }
                break;

            case CHARGED:
                // {"CMD":"charged","STATUS":{"TEMP":..., "ENERGY":..., "MESSAGE":"..."}}
                Map<String,Object> st = (Map<String,Object>) msg.get("STATUS");
                if(st!=null) handleStatusUpdate(st);
                break;

            case STATUS:
                // {"CMD":"status","STATUS":{"TEMP":..., "ENERGY":..., "MESSAGE":"WARN_MAX_TEMP|HEATER_ON"}}
                Map<String,Object> st2 = (Map<String,Object>) msg.get("STATUS");
                if(st2!=null) handleStatusUpdate(st2);
                break;

            case PARTSTATUS:
                // => Expert level status e.g. {"CMD":"partstatus","PARTS":{"MOTOR":90,...}}
                Map<String,Object> parts = (Map<String,Object>) msg.get("PARTS");
                if(parts!=null) {
                    // We might just store them
                    motorStatus   = asFloat(parts.get("MOTOR"),   motorStatus);
                    rotLeftStatus = asFloat(parts.get("ROT_L"),   rotLeftStatus);
                    rotRightStatus= asFloat(parts.get("ROT_R"),   rotRightStatus);
                    coolerStatus  = asFloat(parts.get("COOLER"),  coolerStatus);
                    heaterStatus  = asFloat(parts.get("HEATER"),  heaterStatus);
                    sensorStatus  = asFloat(parts.get("SENSOR"),  sensorStatus);
                    System.out.println("["+robotName+"] PARTSTATUS => M="+motorStatus+" Rl="+rotLeftStatus+" Rr="+rotRightStatus
                            +" Cool="+coolerStatus+" Heat="+heaterStatus+" Sens="+sensorStatus);
                }
                break;

            case CRASHED:
                System.out.println("["+robotName+"] CRASHED => session ends");
                isCrashed = true;
                break;

            case ERROR:
                System.out.println("["+robotName+"] ERROR => "+msg);
                break;

            case EXIT:
                System.out.println("["+robotName+"] Robot requested exit => close session");
                isCrashed = true;  // or set isAutonomous=false
                break;

            default:
                // GETPOS, POS, MVSCAN, ...
                System.out.println("["+robotName+"] CMD => "+cmd+" not specifically handled => "+msg);
        }
    }


    private void handleMeasure(Map<String,Object> measureMap, int x, int y) {
        try {
            String gstr = (String) measureMap.get("GROUND");
            float temp = ((Double)measureMap.get("TEMP")).floatValue();
            Ground ground = Ground.valueOf(gstr.toUpperCase(Locale.ROOT));
            Measure m = new Measure(ground, temp);
            appRef.updateExploredField(x,y,m);
            System.out.println("["+robotName+"] measure => ("+x+","+y+") => "+m);
            // Possibly adjust Robot temperature based on ground temp
            handleTempEffect(temp);
        } catch(Exception e) {
            System.out.println("["+robotName+"] measure parse error => "+measureMap);
        }
    }

    private void handleStatusUpdate(Map<String,Object> stMap) {
        float t = asFloat(stMap.get("TEMP"), currentTemp);
        float e = asFloat(stMap.get("ENERGY"), currentEnergy);
        currentTemp   = t;
        currentEnergy = e;
        String msg = (String) stMap.get("MESSAGE");
        if(msg!=null && !msg.isEmpty()) {
            // parse messages like "WARN_LOW_ENERGY|COOLER_ON" ...
            System.out.println("["+robotName+"] status => temp="+t+", energy="+e+", msg="+msg);
        }
    }

    private void handleEnergyForMove(int nx, int ny) {
        // each MOVE uses some energy, e.g. base 2
        float usage = 2.0f;
        // if motor < 80 => usage +1
        if(motorStatus<80) usage+=1;
        // etc.
        currentEnergy -= usage;
        if(currentEnergy<0) currentEnergy=0;
        // If energy <10 => maybe WARN_LOW_ENERGY
        if(currentEnergy<10) {
            System.out.println("["+robotName+"] local check => WARN_LOW_ENERGY");
        }
    }

    private void handleTempEffect(float groundTemp) {
        // Suppose each measure or standing => Robot tries to match ground temp
        // if groundTemp < 0 => Robot uses heater => more energy usage
        // if groundTemp > 50 => Robot uses cooler => more energy usage
        // This is a simplified example
        if(groundTemp<0) {
            // use heater
            if(heaterStatus>0) {
                currentEnergy -= 1.5;
                currentTemp += 1;
                System.out.println("["+robotName+"] heater on => energy down -1.5");
            }
            if(currentEnergy<=0) {
                System.out.println("["+robotName+"] CRASH => froze => session ends");
                isCrashed=true;
            }
        } else if(groundTemp>50) {
            // use cooler
            if(coolerStatus>0) {
                currentEnergy -= 1.5;
                currentTemp -= 1;
                System.out.println("["+robotName+"] cooler on => energy down -1.5");
            }
            if(currentEnergy<=0) {
                System.out.println("["+robotName+"] CRASH => overheated => session ends");
                isCrashed=true;
            }
        }
    }

    private float asFloat(Object obj, float fallback) {
        if(obj instanceof Double) {
            return ((Double)obj).floatValue();
        } else if(obj instanceof Number) {
            return ((Number)obj).floatValue();
        }
        return fallback;
    }

    private int getPosX() {
        Position pos = appRef.getRobotPosition(robotName);
        return (pos!=null)? pos.getX() : 0;
    }
    private int getPosY() {
        Position pos = appRef.getRobotPosition(robotName);
        return (pos!=null)? pos.getY() : 0;
    }

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
// Console-UI
// --------------------------------------------------
class ConsoleUI extends Thread {
    private final Scanner sc = new Scanner(System.in);
    private final Bodenstation appRef;

    private static final String MENU = "\n--- BODENSTATION MENU ---\n"
            + "[ls]    -> list robots\n"
            + "[sel]   -> select a robot\n"
            + "[stats] -> how many fields explored so far?\n"
            + "[exit]  -> exit application\n"
            + "> ";

    public ConsoleUI(Bodenstation app) {
        this.appRef = app;
    }

    @Override
    public void run() {
        while(!Thread.currentThread().isInterrupted()) {
            System.out.print(MENU);
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

    private void listRobots() {
        Map<String,RobotSession> sess = appRef.getSessions();
        if(sess.isEmpty()) {
            System.out.println("No robots connected.");
            return;
        }
        for(Map.Entry<String,RobotSession> e : sess.entrySet()) {
            System.out.println(" - "+ e.getKey()+" (auto="+ e.getValue().isAutonomous()+")");
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
            System.out.println("\n--- SubMenu for "+rs.getRobotName()+" ---\n"
                    + "[1] toggle autonomy\n"
                    + "[2] manual move\n"
                    + "[3] manual scan\n"
                    + "[c]  charge(5s)\n"
                    + "[gp] getpos\n"
                    + "[b]  back");
            System.out.print("> ");
            String cmd = sc.nextLine().trim().toLowerCase();

            switch(cmd) {
                case "1":
                    rs.setAutonomous(!rs.isAutonomous());
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
                case "gp":
                    // e.g. send "getpos" => might result in "pos":{"X":..,"Y":..,"DIRECTION":".."}
                    rs.sendJson(ExoPlanetProtocol.buildGetPosCmd());
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
