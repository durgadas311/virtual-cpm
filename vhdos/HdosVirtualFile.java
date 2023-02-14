// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.io.*;

public class HdosVirtualFile extends HdosOpenFile {
	private RandomAccessFile fd;
	private String rw;
	private boolean closedf = true;

	public HdosVirtualFile(File path, int fnc) {
		super(path, fnc);
		switch (fnc) {
		case 042:	// .OPENR
			rw = "r";
			break;
		case 043:	// .OPENW
		case 044:	// .OPENU
		case 045:	// .OPENC
			rw = "rw";
			break;
		}
	}

	public boolean open() {
		try {
			fd = new RandomAccessFile(file, rw);
			closedf = false;
		} catch (Exception ee) {
			System.err.format("open: %s\n", ee.getMessage());
			return false;
		}
		return true;
	}

	public boolean close() {
		try {
			fd.close();
			closedf = true;
		} catch (Exception ee) {
			// ee.getMessage()
			return false;
		}
		return true;
	}

	public int read(byte[] dat, int off, int cnt) {
		try {
			int n = fd.read(dat, off, cnt);
			return n;
		} catch (Exception ee) {
			// ee.getMessage()
			return -1;
		}
	}

	public int write(byte[] dat, int off, int cnt) {
		try {
			fd.write(dat, off, cnt);
			return cnt;
		} catch (Exception ee) {
			// ee.getMessage()
			return -1;
		}
	}

	public boolean seek(int pos) {
		try {
			fd.seek(pos);
		} catch (Exception ee) {
			// ee.getMessage()
			return false;
		}
		return true;
	}

	public int length() {
		try {
			long len = fd.length();
			if (len > 0x7fffffff) len = 0x7fffffff;
			return (int)len;
		} catch (Exception ee) {
			// ee.getMessage()
			return 0;
		}
	}

	public boolean closed() {
		return closedf;
	}
}
