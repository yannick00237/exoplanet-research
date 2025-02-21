package exo;

public interface RobotStatus {

	// Abfrage aktuelle Betriebstemperatur des Robot (in °C) 
	public float getWorkTemp();
	
	// Abfrage aktueller Akku-Zustand (in %)
	public int getEnergy();
	
	// Letzte Statusnachricht als Klartext
	public String getMessage();
	
}
