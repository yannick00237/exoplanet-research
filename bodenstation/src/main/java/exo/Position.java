package exo;
import java.io.Serializable;

// Positionsdaten

public class Position implements Serializable{

	private static final long serialVersionUID = -2782482662823778148L;
	
	private int x;
	private int y;
	private Direction dir;
	
	public Position(int x, int y) {
		this(x, y, Direction.NORTH);
	}
	
	public Position(int x, int y, Direction dir) {
		this.x = x;
		this.y = y;
		this.dir = dir;
	}
	
	public Position(Position pos){
		if(pos != null){
			this.x = pos.x;
			this.y = pos.y;
			this.dir = pos.dir;
		} else {
			this.dir = Direction.NORTH;
		}
	}
	
	public int getX() {
		return x;
	}
	public void setX(int x) {
		this.x = x;
	}
	public int getY() {
		return y;
	}
	public void setY(int y) {
		this.y = y;
	}

	public Direction getDir() {
		return dir;
	}

	public void setDir(Direction dir) {
		this.dir = dir;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + x;
		result = prime * result + y;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Position other = (Position) obj;
		if (x != other.x)
			return false;
		if (y != other.y)
			return false;
		return true;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("POSITION|");
		sb.append(x);
		sb.append("|");
		sb.append(y);
		sb.append("|");
		sb.append(dir.name());
		return sb.toString();
	}
	
	public static Position parse(String s){
		String[] token = s.trim().split("\\|");
		if(token.length == 4){
			if (token[0].equals("POSITION")){
				int x = Integer.parseInt(token[1]);
				int y = Integer.parseInt(token[2]);
				Direction d = Direction.valueOf(token[3]);
				return new Position(x, y, d);
			}
		}
		return null;
	}
}
