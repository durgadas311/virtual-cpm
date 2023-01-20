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
	private int traceLow = -1;
	private int traceHigh = -1;
	private int traceCycles = 0;

	// TODO: support changing tracing after ctor?
	protected CPUTracer(Properties props, String args) {
		String[] argv = args.split("\\s");

		if (argv.length < 1) {
			return;
		}
		if (argv[0].equalsIgnoreCase("pc") && argv.length > 1) {
			traceLow = Integer.valueOf(argv[1], 16);
			if (argv.length > 2) {
				traceHigh = Integer.valueOf(argv[2], 16);
			} else {
				traceHigh = 0x10000;
			}
		} else if (argv[0].equalsIgnoreCase("trigger") && argv.length > 1) {
			traceLow = Integer.valueOf(argv[1], 16);
			if (argv.length > 2) {
				traceCycles = Integer.valueOf(argv[2]);
			} else {
				traceCycles = -1; // infinity
			}
		// TODO: others? "on"? "oneshot"?
		}
	}

	protected boolean shouldTrace(int pc) {
		if (traceCycles == 0 && pc >= traceLow && pc < traceHigh) {
			return true;
		}
		return (traceCycles > 0);
	}

	protected void didTrace(int pc, int cy) {
		if (traceCycles > 0) {
			traceCycles -= cy;
			if (traceCycles < 0) {
				traceCycles = 0;
			}
		}
		// TODO: oneshot? if pc outside range, turn off?
	}

	// before cpu.execute()...
	public abstract boolean preTrace(int pc, long clk);
	// after cpu.execute()...
	public abstract void postTrace(int pc, int cy);
}
