// Copyright (c) 2023 Douglas Miller <durgadas311@gmail.com>

import java.io.*;

class Memory {
	private byte[] mem;
	private int msk;

	public Memory(int size) {
		msk = size - 1;	// must be power of 2
		mem = new byte[size];
	}
	public Memory(byte[] mem) {
		this.mem = mem;
		msk = mem.length - 1;	// must be power of 2
	}

	public int read(boolean rom, int bank, int address) { // debugger interface
		return read(address);
	}
	public int read(int address) {
		return mem[address & msk] & 0xff;
	}
	public void write(int address, int value) { }
	public void reset() { }
	public void dumpCore(String file) {
		try {
			FileOutputStream f = new FileOutputStream(file);
			f.write(mem);
			f.close();
			System.err.format("CP/M core dumped to \"%s\"\n", file);
		} catch (Exception ee) {
			ee.printStackTrace();
		}
	}
	public String dumpDebug() { return ""; }
}
