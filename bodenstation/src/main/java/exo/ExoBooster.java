package exo;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

// Hilfsklasse um Robot-Klassen/-Objekte zum ExoPlanet zu transportieren  
public class ExoBooster implements Serializable{

	private static final long serialVersionUID = 3L;
	private String lander;		// Name des Landers
	private Position position;	// Zielposition zum Landen
	private String userData;	// frei belegbare Benutzerdaten
	private List<BoosterPart> partList;	// Teileliste
	
	// Konstruktor zur Erzeugung einer Traegerrakete 
	// lander: Name des Landemoduls (= des Robots)
	// position: anvisierte Landeposition auf ExoPlanet
	// userData: frei belegbare Benutzerdaten, die an den Plugin-Robot bei Aufruf initRun 1:1 weitergegeben werden
	// className: Name der Hauptklasse (muss Robot-Interface implementieren), von der auf dem ExoPlanet ein Objekt erzeugt werden soll
	// classData: Inhalt des class-Files der Klasse in einem byte-Array
	public ExoBooster(String lander, Position position, String userData, String className, byte[] classData) {
		super();
		this.lander = lander;
		this.position = position;
		this.userData = userData;
		this.partList = new LinkedList<BoosterPart>();
		partList.add(new BoosterPart(className, classData));
	}

	// Booster kann mit weiterern eigenen "Hilfs-Klassen" beladen werden, auf die die Hauptklasse des Plugin-Robots angewiesen ist.
	// Alle Klassen im exo-Package stehen im ExoPlanet bereits zur Verfügung und duerfen hier nicht angegeben werden.
	public void addPart(String className, byte[] classData){
		// Hilfsklassen am Anfang einfuegen
		partList.add(0, new BoosterPart(className, classData));
	}
	
	public String getLander() {
		return lander;
	}

	public Position getPosition() {
		return position;
	}

	public String getUserData() {
		return userData;
	}

	public int getPartCount(){
		return partList.size();
	}
	
	public String getClassName(int index) {
		if(index >=0 && index < partList.size()){
			return partList.get(index).getClassName();
		}
		return null;
	}

	public byte[] getClassData(int index) {
		if(index >=0 && index < partList.size()){
			return partList.get(index).getClassData();
		}
		return null;
	}
	
	// interne Hilfsklasse
	class BoosterPart implements Serializable{
		
		private static final long serialVersionUID = 2L;
		private String className;
		private byte[] classData;
		public BoosterPart(String className, byte[] classData) {
			this.className = className;
			this.classData = classData;
		}
		public String getClassName() {
			return className;
		}
		public byte[] getClassData() {
			return classData;
		}		
	}
	
}
