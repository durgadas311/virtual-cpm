// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.io.*;
import java.nio.*;
import java.nio.channels.FileLock;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Calendar;

public class HdosDirectoryFile extends HdosOpenFile {
	static final int DIF_SYS = 0x80;
	static final int DIF_LOC = 0x40;
	static final int DIF_WP = 0x20;

	private byte[] dir;
	private int pos;
	private boolean nosys;
	private boolean y2k;

	public HdosDirectoryFile(File path, int fnc,
			boolean nosys, boolean y2k) {
		super(path, fnc);
		this.nosys = nosys;
		this.y2k = y2k;
		// assert(fnc == 042);
		// assert(path.isDirectory());
		pos = 0;
	}

	private void setDate(File file, byte[] buf, int off) {
		try {
			Calendar cal = Calendar.getInstance();
			BasicFileAttributes attr;
			Path path = file.toPath();
			attr = Files.readAttributes(path, BasicFileAttributes.class);
			long tm = attr.lastModifiedTime().toMillis();
			cal.setTimeInMillis(tm);
			int yr = cal.get(Calendar.YEAR);
			if (!y2k && yr > 1999) yr = 1999; // HACK!
			int dt = cal.get(Calendar.DAY_OF_MONTH) |
				((cal.get(Calendar.MONTH) + 1) << 5) |
				((yr - 1970) << 9);
			buf[off] = (byte)dt;
			buf[off + 1] = (byte)(dt >> 8);
		} catch (Exception ee) {
			return;
		}
	}

	private int nextFile(String[] l, int e, int ent) {
		int t = 0;
		int x = 0;
		for (; e < l.length; ++e) {
			if (l[e].startsWith(".")) continue;
			t = l[e].indexOf('.');
			if (t > 8) continue;
			if (t > 0 && l[e].length() - t > 4) continue;
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
		File f = new File(file, l[e]);
		int flg = 0;
		if (!f.canWrite()) flg |= DIF_WP;
		if (!nosys && f.canExecute()) flg |= DIF_SYS;
		dir[ent + 14] = (byte)flg;
		int len = (int)((f.length() + 255) / 256);
		dir[ent + 16] = (byte)len;
		dir[ent + 17] = (byte)(len >> 8);
		setDate(f, dir, ent + 19);
		return e;
	}

	private void hexDump(byte[] buf, int adr, int len) {
		int x;
		while (len > 0) {
			System.err.format("%04x:", adr);
			for (x = 0; x < 16 && x < len; ++x) {
				System.err.format(" %02x", buf[adr + x] & 0xff);
			}
			System.err.format("\n");
			adr += 16;
			len -= 16;
		}
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

	public boolean closed() {
		return (dir == null);
	}
}
