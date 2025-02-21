package exo;

import java.io.Serializable;

// Planetengroesse

public class Size  implements Serializable {

	private static final long serialVersionUID = 2L;
	private int width;
	private int height;

	public Size(int width, int height) {
		this.width = width;
		this.height = height;
	}
	
	public Size(){
		this(0,0);
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("SIZE|");
		sb.append(width);
		sb.append("|");
		sb.append(height);
		return sb.toString();
	}
	
	public static Size parse(String s){
		String[] token = s.trim().split("\\|");
		if(token.length == 3){
			if (token[0].equals("SIZE")){
				int w = Integer.parseInt(token[1]);
				int h = Integer.parseInt(token[2]);
				return new Size(w,h);
			}
		}
		return null;
	}
	
}
