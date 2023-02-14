// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.io.*;

public abstract class HdosOpenFile {
	public File file;
	public int mode; // 042, 043, 044, 045

	public HdosOpenFile(File path, int fnc) {
		file = path;
		mode = fnc;
	}

	public abstract boolean open();
	public abstract boolean close();
	public abstract int read(byte[] dat, int off, int cnt);
	public abstract int write(byte[] dat, int off, int cnt);
	public abstract boolean seek(int pos);
	public abstract int length();
	public abstract boolean closed();
}
