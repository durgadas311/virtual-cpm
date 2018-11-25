// Copyright (c) 2016 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.io.*;

public class CpnetServerConfig {
	static final int byteLength = 23;
	public byte tmp;
	public byte sts;
	public byte id;
	public byte max;
	public byte cur;
	public short vec;
	public byte[] rid = new byte[16];

	public CpnetServerConfig() {
		tmp = 0;
		sts = 0;
		id = (byte)0xff;
		max = 0;
		cur = 0;
		vec = 0;
		Arrays.fill(rid, (byte)0);
	}

	public void put(byte[] buf, int start) {
		buf[start++] = tmp;
		buf[start++] = sts;
		buf[start++] = id;
		buf[start++] = max;
		buf[start++] = cur;
		buf[start++] = (byte)(vec & 0xff);
		buf[start++] = (byte)((vec >> 8) & 0xff);
		for (int x = 0; x < rid.length; ++x) {
			buf[start++] = rid[x];
		}
	}
}
