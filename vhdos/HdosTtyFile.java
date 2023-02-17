// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.io.*;

public class HdosTtyFile extends HdosOpenFile {
	private boolean closedf = true;

	public HdosTtyFile(File path, int fnc) {
		super(path, fnc);
		closedf = false;
	}

	public boolean open() {
		closedf = false;
		return true;
	}

	public boolean close() {
		closedf = true;
		return true;
	}

	public int read(byte[] dat, int off, int cnt) {
		// TODO: what's the best way?
		return 0;
	}

	public int write(byte[] dat, int off, int cnt) {
		if (cnt > 0) {
			System.out.write(dat, off, cnt);
		}
		return cnt;
	}

	public boolean seek(int pos) {
		return true;
	}

	public int length() {
		return 0;
	}

	public boolean closed() {
		return closedf;
	}
}
