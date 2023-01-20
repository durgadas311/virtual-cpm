// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Properties;

abstract class CPUTracer {
	private Z80Disassembler disas;
	private int traceLow = 0;
	private int traceHigh = 0;
	private int traceCount = 0;
	private boolean traceOnce = false;

	private int count = 0;
	private boolean tracing = false;

	// TODO: support changing tracing after ctor?
	protected CPUTracer(Properties props, String args) {
		String[] argv = args.split("\\s");
		int x;

		if (argv.length < 1) {
			return;
		}
		if (argv[0].indexOf(":") < 0) {
			traceLow = Integer.valueOf(argv[0], 16);
			traceHigh = traceLow + 1;
		} else {
			String[] range = argv[0].split(":");
			if (range.length == 0 || range[0].length() == 0) {
				traceLow = 0;
			} else {
				traceLow = Integer.valueOf(range[0], 16);
			}
			if (range.length <= 1 || range[1].length() == 0) {
				traceHigh = 0x10000;
			} else {
				traceHigh = Integer.valueOf(range[1], 16);
			}
		}
		for (x = 1; x < argv.length; ++x) {
			if (argv[x].equalsIgnoreCase("oneshot")) {
				traceOnce = true;
			} else {
				traceCount = Integer.valueOf(argv[x]);
			}
		}
		// System.err.format("trace %04x %04x %d %s\n",
		//	traceLow, traceHigh, traceCount, traceOnce);
	}

	protected boolean shouldTrace(int pc) {
		boolean trace = (pc >= traceLow && pc < traceHigh);
		if (tracing && !trace) {
			count = traceCount;
			if (traceOnce) {
				traceHigh = traceLow = 0;
			}
		}
		tracing = trace;
		return tracing || (count > 0);
	}

	protected void didTrace(int pc, int cy) {
		if (count > 0) {
			--count;
		}
	}

	// before cpu.execute()...
	public abstract boolean preTrace(int pc, long clk);
	// after cpu.execute()...
	public abstract void postTrace(int pc, int cy);
}
