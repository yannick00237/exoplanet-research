package exo;

// Planet beschreibt alles, was man auf einem ExoPlanet mit seinem 
// PlugIn-Robot tun kann...

public interface Planet {

	// Landen des Robots an angegebener Position
	// Rueckgabe Messungsobjekt der tatsaechlichen Landeposition
	public Measure land(Robot robot, Position landPos);
	
	// Abfrage der aktuellen Position des Robots
	public Position getPosition(Robot robot);
	
	// Bewege den Robot ein Feld weiter in der aktuellen Richtung
	public Position move(Robot robot);
	
	// Drehe den Roboter 90� nach Links oder Rechts
	public Direction rotate(Robot robot, Rotation rotation);
	
	// Abfrage Messdaten, des vor dem Robot liegenden Feldes 
	public Measure scan(Robot robot);

	// Bewegen in aktuelle Richtung mit anschliessender Messung
	// in newPos wird die neue Position abgelegt 
	public Measure moveScan(Robot robot, Position newPos);
	
	// Abfrage Planetengroesse
	public Size getSize();

	// Robotter soll vom Planet entfernt werden
	public void remove(Robot robot);
	
	// Wird nur im Expertenmodus unterstuetzt:
	// Versuche den Robot-Akku ueber Sonnenkollektoren wieder aufzuladen. 
	// Die Methode kehrt erst nach Ablauf der uebergebenen Zeitspanne [in s] 
	// wieder zurueck und liefert den aktuellen RobotStatus zurueck
	public RobotStatus charge(Robot robot, int duration);
	
}
