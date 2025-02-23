package bodenstation;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Scanner;

/**
 * RemoteRobot: Verbindet sich entweder direkt zum ExoPlanet (Test Mode)
 * oder in Produktionsmodus -> Bodenstation <-> ExoPlanet.
 * <p>
 * Hauptmerkmale:
 * - TestMode: Direkte Eingaben in der Konsole werden an den ExoPlanet gesendet.
 * - ProdMode: Robot empfängt Kommandos von der Bodenstation und leitet sie an ExoPlanet weiter,
 * Antworten vom ExoPlanet an die Bodenstation (zwei Threads).
 */
public class RemoteRobot {

    private static final String EXOPLANET_HOST_DEFAULT = "localhost";
    private static final int EXOPLANET_PORT_DEFAULT = 8150;

    private static final String BODENSTATION_HOST_DEFAULT = "localhost";
    private static final int BODENSTATION_PORT_DEFAULT = 9999;

    public static void main(String[] args) {
        boolean isTestMode = args.length > 0;

        System.out.println("[RemoteRobot] Starting...");
        try (Scanner consoleScanner = new Scanner(System.in)) {

            if (isTestMode) {
                runTestMode(consoleScanner, EXOPLANET_HOST_DEFAULT, EXOPLANET_PORT_DEFAULT);
            } else {
                runProductionMode(consoleScanner, EXOPLANET_HOST_DEFAULT, EXOPLANET_PORT_DEFAULT, BODENSTATION_HOST_DEFAULT, BODENSTATION_PORT_DEFAULT);
            }
        }
        System.out.println("[RemoteRobot] Exiting main.");
    }

    // --------------------------------------------------------
    // TEST-MODE: Direkte Eingaben -> ExoPlanet
    // --------------------------------------------------------
    private static void runTestMode(Scanner console, String exoHost, int exoPort) {
        System.out.println("[RemoteRobot] Running in TEST mode. Connecting to ExoPlanet at " +
                exoHost + ":" + exoPort);

        try (Socket exoSocket = new Socket(exoHost, exoPort);
             PrintWriter exoOut = new PrintWriter(exoSocket.getOutputStream(), true);
             BufferedReader exoIn = new BufferedReader(new InputStreamReader(exoSocket.getInputStream()))) {

            exoSocket.setSoTimeout(2000);
            System.out.println("[RemoteRobot|TestMode] Connected to ExoPlanet. Type commands or 'exit' to quit.");

            Thread exoListener = new Thread(() -> {
                try {
                    String line;
                    while (!Thread.currentThread().isInterrupted() && !exoSocket.isClosed()) {
                        try {
                            line = exoIn.readLine();
                            if (line == null) {
                                System.out.println("[TestMode] ExoPlanet closed connection.");
                                break;
                            }
                            System.out.println("[ExoPlanet] " + line);
                        } catch (SocketTimeoutException e) {
                            // no data => ignore => loop
                        }
                    }
                } catch (IOException e) {
                    System.out.println("[TestMode|exoListener] Connection error => " + e);
                }
                System.out.println("[TestMode|exoListener] ended.");
            }, "ExoPlanetListener");
            exoListener.start();

            // Ensure the listener thread has actually started
            try {
                exoListener.join(100);
            } catch (InterruptedException ignored) {
            }

            // Main loop => read from console, send to exoOut
            while (exoListener.isAlive()) {
                System.out.print("> ");
                String cmd = console.nextLine().trim();
                if (cmd.equalsIgnoreCase("exit")) {
                    System.out.println("[TestMode] Exiting test mode...");
                    exoListener.interrupt();
                    break;
                }
                exoOut.println(cmd);
            }

        } catch (IOException e) {
            System.err.println("[RemoteRobot|TestMode] Connection to ExoPlanet failed: " + e);
        }
    }

    // --------------------------------------------------------
    // PROD MODE: Bodenstation <-> (this) <-> ExoPlanet
    // --------------------------------------------------------
    private static void runProductionMode(Scanner console,
                                          String exoHost, int exoPort,
                                          String bsHost, int bsPort) {
        System.out.println("[RemoteRobot] Running in PRODUCTION mode. \n"
                + "ExoPlanet: " + exoHost + ":" + exoPort + "  Bodenstation: " + bsHost + ":" + bsPort);

        // In Prod mode => 2 sockets:
        // 1) toExoPlanet
        // 2) toBodenstation
        // We'll spin up two threads:
        //  - exoReceiver: reads from ExoPlanet, writes to Bodenstation
        //  - bsReceiver: reads from Bodenstation, writes to ExoPlanet

        try (Socket exoSocket = new Socket(exoHost, exoPort);
             Socket bsSocket = new Socket(bsHost, bsPort)) {

            exoSocket.setSoTimeout(2000);
            bsSocket.setSoTimeout(2000);

            System.out.println("[RemoteRobot|Prod] Connected to ExoPlanet and Bodenstation. Type 'exit' to quit.");

            RelayThread exoReceiver = new RelayThread("ExoReceiver",
                    exoSocket.getInputStream(),
                    bsSocket.getOutputStream(),
                    "[Exo→BS]");
            RelayThread bsReceiver = new RelayThread("BSReceiver",
                    bsSocket.getInputStream(),
                    exoSocket.getOutputStream(),
                    "[BS→Exo]");

            exoReceiver.start();
            bsReceiver.start();

            // Ensure the relay threads have actually started
            try {
                exoReceiver.join(100);
                bsReceiver.join(100);
            } catch (InterruptedException ignored) {
            }

            // Wait user input -> just exit if "exit"
            while (exoReceiver.isAlive() && bsReceiver.isAlive()) {
                System.out.print("(prod) > ");
                String line = console.nextLine().trim();
                if (line.equalsIgnoreCase("exit")) {
                    System.out.println("[RemoteRobot|Prod] Stopping...");
                    exoReceiver.interrupt();
                    bsReceiver.interrupt();
                    break;
                } else {
                    System.out.println("[ProdMode] (No direct commands in production mode, type 'exit' to quit.)");
                }
            }

            // Wait for threads to stop
            exoReceiver.join();
            bsReceiver.join();
            System.out.println("[RemoteRobot|Prod] All threads ended. Closing sockets.");

        } catch (IOException e) {
            System.err.println("[RemoteRobot|Prod] Connection error => " + e);
        } catch (InterruptedException e) {
            System.out.println("[RemoteRobot|Prod] Interrupted => " + e);
            Thread.currentThread().interrupt();
        }
    }

    // --------------------------------------------------------
    // RelayThread: leitet Daten von inStream -> outStream
    // --------------------------------------------------------
    private static class RelayThread extends Thread {
        private final InputStream inStream;
        private final OutputStream outStream;
        private final String prefix;

        RelayThread(String threadName, InputStream in, OutputStream out, String prefix) {
            super(threadName);
            this.inStream = in;
            this.outStream = out;
            this.prefix = prefix;
        }

        @Override
        public void run() {
            System.out.println("[RelayThread:" + getName() + "] started.");
            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(inStream));
                    PrintWriter out = new PrintWriter(outStream, true)
            ) {
                while (!Thread.currentThread().isInterrupted()) {
                    String line;
                    try {
                        line = in.readLine();
                        if (line == null) {
                            System.out.println("[RelayThread:" + getName() + "] Stream closed => stop.");
                            break;
                        }
                        // Relay
                        out.println(line);
                        System.out.println(prefix + " " + line);
                    } catch (SocketTimeoutException e) {
                        // Timed out => check interrupt => loop
                    }
                }
            } catch (IOException e) {
                System.err.println("[RelayThread:" + getName() + "] IO error => " + e);
            }
            System.out.println("[RelayThread:" + getName() + "] ended.");
        }
    }
}

