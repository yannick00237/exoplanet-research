package remoterobot;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Scanner;

/**
 * RemoteRobot: Connects to ExoPlanet in test mode or acts as a relay
 * between Bodenstation <-> ExoPlanet in production mode.
 */
public class RemoteRobot {

    private static final String EXOPLANET_HOST_DEFAULT = "localhost";
    private static final int    EXOPLANET_PORT_DEFAULT = 8150;

    private static final String BODENSTATION_HOST_DEFAULT = "localhost";
    private static final int    BODENSTATION_PORT_DEFAULT = 9999;

    public static void main(String[] args) {
        boolean isTestMode = (args.length > 0);

        System.out.println("[RemoteRobot] Starting...");
        try (Scanner consoleScanner = new Scanner(System.in)) {
            if (isTestMode) {
                runTestMode(consoleScanner, EXOPLANET_HOST_DEFAULT, EXOPLANET_PORT_DEFAULT);
            } else {
                runProductionMode(consoleScanner,
                        EXOPLANET_HOST_DEFAULT, EXOPLANET_PORT_DEFAULT,
                        BODENSTATION_HOST_DEFAULT, BODENSTATION_PORT_DEFAULT);
            }
        }
        System.out.println("[RemoteRobot] Exiting main.");
    }

    /**
     * TEST MODE: Direct user input -> ExoPlanet
     */
    private static void runTestMode(Scanner console, String exoHost, int exoPort) {
        System.out.println("[RemoteRobot|TestMode] Connecting to ExoPlanet at " + exoHost + ":" + exoPort);

        try (Socket exoSocket = new Socket(exoHost, exoPort);
             PrintWriter exoOut = new PrintWriter(exoSocket.getOutputStream(), true);
             BufferedReader exoIn = new BufferedReader(new InputStreamReader(exoSocket.getInputStream()))) {

            exoSocket.setSoTimeout(2000);
            System.out.println("[RemoteRobot|TestMode] Connected. Type commands or 'exit' to quit.");

            // Listener thread for ExoPlanet responses
            Thread exoListener = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted() && !exoSocket.isClosed()) {
                        try {
                            String line = exoIn.readLine();
                            if (line == null) {
                                System.out.println("[TestMode] ExoPlanet closed its socket. Bye!");
                                break;
                            }
                            System.out.println("[ExoPlanet] " + line);
                        } catch (SocketTimeoutException ignored) {}
                    }
                } catch (IOException e) {
                    System.out.println("[TestMode|exoListener] Connection error => " + e);
                }
                System.out.println("[TestMode|exoListener] ended.");
            }, "ExoPlanetListener");

            exoListener.start();

            while (exoListener.isAlive()) {
                try {
                    if (System.in.available() > 0) {
                        String cmd = console.nextLine().trim();
                        if (cmd.equalsIgnoreCase("exit")) {
                            System.out.println("[TestMode] Exiting test mode...");
                            exoListener.interrupt();
                            break;
                        }
                        exoOut.println(cmd);
                    } else {
                        Thread.sleep(100);
                    }
                } catch (IOException e) {
                    break;  // Exit if System.in fails
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            exoListener.join();
            System.out.println("[RemoteRobot|TestMode] Socket closed => returning.");

        } catch (IOException e) {
            System.err.println("[RemoteRobot|TestMode] Connection to ExoPlanet failed: " + e);
        } catch (InterruptedException e) {
            System.err.println("[RemoteRobot|TestMode] Interrupted => " + e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * PRODUCTION MODE: Bodenstation <-> RemoteRobot <-> ExoPlanet
     */
    private static void runProductionMode(Scanner console,
                                          String exoHost, int exoPort,
                                          String bsHost,  int bsPort) {
        System.out.println("[RemoteRobot|Prod] Attempting connections:\n"
                + "  ExoPlanet => " + exoHost + ":" + exoPort + "\n"
                + "  Bodenstation => " + bsHost + ":" + bsPort);

        try (Socket exoSocket = new Socket(exoHost, exoPort);
             Socket bsSocket  = new Socket(bsHost, bsPort)) {

            exoSocket.setSoTimeout(2000);
            bsSocket.setSoTimeout(2000);

            System.out.println("[RemoteRobot|Prod] Connected to both. Type 'exit' to quit.");

            // Relay exo -> bs
            RelayThread exoReceiver = new RelayThread("ExoReceiver",
                    exoSocket.getInputStream(),
                    bsSocket.getOutputStream(),
                    "[Exo→BS]");
            // Relay bs -> exo
            RelayThread bsReceiver  = new RelayThread("BSReceiver",
                    bsSocket.getInputStream(),
                    exoSocket.getOutputStream(),
                    "[BS→Exo]");

            exoReceiver.start();
            bsReceiver.start();

            while (exoReceiver.isAlive() && bsReceiver.isAlive()) {
                try {
                    if (System.in.available() > 0) {  // Non-blocking input check
                        String line = console.nextLine().trim();
                        if (line.equalsIgnoreCase("exit")) {
                            System.out.println("[RemoteRobot|Prod] Stopping relay threads...");
                            exoReceiver.interrupt();
                            bsReceiver.interrupt();
                            break;
                        }
                    } else {
                        Thread.sleep(100);
                    }
                } catch (IOException e) {
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Wait for relay threads to end
            exoReceiver.join();
            bsReceiver.join();
            System.out.println("[RemoteRobot|Prod] Sockets closed => returning.");

        } catch (IOException e) {
            System.err.println("[RemoteRobot|Prod] Connection error => " + e);
        } catch (InterruptedException e) {
            System.err.println("[RemoteRobot|Prod] Interrupted => " + e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * RelayThread: Reads from input stream -> writes to output stream
     */
    private static class RelayThread extends Thread {
        private final InputStream is;
        private final OutputStream os;
        private final String prefix;

        RelayThread(String name, InputStream in, OutputStream out, String prefix) {
            super(name);
            this.is = in;
            this.os = out;
            this.prefix = prefix;
        }

        @Override
        public void run() {
            System.out.println("[RelayThread:" + getName() + "] started.");
            try (BufferedReader in  = new BufferedReader(new InputStreamReader(is));
                 PrintWriter   out = new PrintWriter(os, true)) {

                while (!Thread.currentThread().isInterrupted()) {
                    String line;
                    try {
                        line = in.readLine();
                        if (line == null) {
                            System.out.println("[RelayThread:" + getName() + "] Remote closed => stopping.");
                            break;
                        }
                        out.println(line);
                        System.out.println(prefix + " " + line);
                    } catch (SocketTimeoutException ignored) {}
                }

            } catch (IOException e) {
                System.err.println("[RelayThread:" + getName() + "] IO error => " + e);
            }
            System.out.println("[RelayThread:" + getName() + "] ended.");
        }
    }
}
