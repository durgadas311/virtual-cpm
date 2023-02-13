// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.io.*;

public class HdosDirectoryFile extends HdosOpenFile {
	private byte[] dir;
	private int pos;

	public HdosDirectoryFile(File path, int fnc) {
		super(path, fnc);
		// assert(fnc == 042);
		// assert(path.isDirectory());
		pos = 0;
	}

	private int nextFile(String[] l, int e, int ent) {
		int t = 0;
		int x = 0;
		for (; e < l.length; ++e) {
			if (l[e].startsWith(".")) continue;
			t = l[e].indexOf('.');
			if (t > 8) continue;
			if (t > 0 && l[e].length() - t > 3) continue;
			if (!l[e].equals(l[e].toLowerCase())) continue;
			// TODO: more sanity checks
			break;
		}
		if (e >= l.length) return -1;
		t = 0;
		x = 0;
		while (t < l[e].length() && l[e].charAt(t) != '.' && x < 8) {
			dir[ent + x++] = (byte)Character.toUpperCase(l[e].charAt(t++));
		}
		while (x < 8) {
			dir[ent + x++] = 0;
		}
		// TODO: reject invalid names?
		while (t < l[e].length() && l[e].charAt(t) != '.') {
			++t;
		}
		if (t < l[e].length() && l[e].charAt(t) == '.') {
			++t;
		}
		while (t < l[e].length() && l[e].charAt(t) != '.' && x < 11) {
			dir[ent + x++] = (byte)Character.toUpperCase(l[e].charAt(t++));
		}
		while (x < 11) {
			dir[ent + x++] = 0;
		}
		// TODO: date stamps? fake size info?
		return e;
	}

	public boolean open() {
		int x, y;
		String[] l = file.list();
		int z = ((l.length + 21) / 22) * 512;
		dir = new byte[z];
		y = -1;
		for (x = 0; x < z;) {
			y = nextFile(l, y + 1, x);
			if (y < 0) break;
			x += 23;
			if (((x & 0x1ff) >= 0x1fa)) {
				x = (x + 6) & ~0x1ff;
			}
		}
		while (x < z) {
			dir[x] = (byte)0xfe;
			x += 23;
			if (((x & 0x1ff) >= 0x1fa)) {
				x = (x + 6) & ~0x1ff;
			}
		}
		return true;
	}

	public boolean close() {
		dir = null;
		return true;
	}

	public int read(byte[] dat, int off, int cnt) {
		if (dir == null || pos < 0 || pos > dir.length) {
			return -1;
		}
		if (cnt > dir.length - pos) {
			cnt = dir.length - pos;
		}
		System.arraycopy(dir, pos, dat, off, cnt);
		pos += cnt;
		return cnt;
	}

	public int write(byte[] dat, int off, int cnt) {
		return -1;
	}

	public boolean seek(int pos) {
		// TODO: error/bounds check?
		this.pos = pos;
		return true;
	}

	public int length() {
		return dir.length;
	}
}
