package exo;

import java.io.PrintStream;

// Interface fuer eigene Plugin-Robot-Klassen, die per ExoBooster an einen ExoPlanet transferiert werden sollen
public interface Robot {
	
	// Ereignismethoden zur Benachrichtigung des Robotters
	
	// initRun wird aufgerufen, wenn Robot-Klasse geladen und ein Objekt erzeugt wurde
	// Hinweise:
	// - initRun wird aus einem eigenen Thread des ExoPlaneten heraus aufgerufen. 
	// - beim Aufruf von initRun wird die Kontrolle an den Robot uebergeben. Solange 
	//   der Robot arbeitet, darf die Methode nicht verlassen werden.
	// - Bei einem Interrupt, muss die initRun-Methode verlassen werden
	// - Am Anfang von initRun muss der Robot auf dem Planeten gelandet werden! (siehe Planet)
	public void initRun(Planet planet,		// Zielplanet fuer Landung 
						String lander,  	// Name des Roboters
						Position landPos, 	// geplante Landeposition
						String userData, 	// Benutzerdaten aus ExoBooster
						RobotStatus initStatus, // Zustand des Robots
						PrintStream out		// Ausgabe-Objekt fuer Nachrichten an den Absender
					 );
	
	// crash wird aufgerufen, wenn Robotter nicht mehr funktionsfaehig ist
	// z.B. nach einer Bewegung auf nicht befahrbares Gelaende 
	// Bei crash sollte dfür gesorgt werden, dass die initRun-Methode verlassen wird 
	public void crash();

	// statusChanged wird im Expertenmodus aufgerufen, wenn sich der Status
	// des Robots signifikant veraendert hat
	public void statusChanged(RobotStatus newStatus);
	
	// Robotter muss seinen Namen aus initRun bereitstellen
	public String getLanderName();
}
