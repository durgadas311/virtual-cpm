// Copyright 2018 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Properties;

import z80core.*;

public class VirtualCpm implements Computer, Runnable {

	static final int passE = 0x01;
	static final int passDE = 0x02;
	static final int passUSR = 0x04;
	static final int passDRV = 0x04;

	private byte[] flags = {
		(byte)passUSR,	// 15 - OPEN
		(byte)passUSR,	// 16 - CLOSE
		(byte)passDRV,	// 17 - SEARCH FIRST
		(byte)0,	// 18 - SEARCH NEXT
		(byte)passUSR,	// 19 - DELETE
		(byte)passUSR,	// 20 - READ SEQ
		(byte)passUSR,	// 21 - WRITE SEQ
		(byte)passUSR,	// 22 - MAKE
		(byte)passUSR,	// 23 - RENAME
		(byte)0,	// 24 - GET LOGIN VEC
		(byte)0,	// 25 - N/A
		(byte)0,	// 26 - N/A
		(byte)passDRV,	// 27 - GET ALLOC VEC
		(byte)passDRV,	// 28 - SET R/O
		(byte)0,	// 29 - GET R/O VEC
		(byte)passUSR,	// 30 - SET ATTR
		(byte)passDRV,	// 31 - GET DPB
		(byte)0,	// 32 - N/A
		(byte)passUSR,	// 33 - READ RND
		(byte)passUSR,	// 34 - WRITE RND
		(byte)passUSR,	// 35 - COMP SIZE
		(byte)passUSR,	// 36 - SET RND REC
		(byte)passDE,	// 37 - RESET DRIVES
		(byte)passDE,	// 38 - ACCESS DRIVES
		(byte)passDE,	// 39 - FREE DRIVES
		(byte)passUSR,	// 40 - WRITE RND ZF
		(byte)0,	// 41 - N/A
		(byte)passUSR,	// 42 - LOCK REC
		(byte)passUSR,	// 43 - UNLOCK REC
		(byte)0,	// 44 - N/A
		(byte)0,	// 45 - N/A
		(byte)passE,	// 46 - GET FREE SPACE
		(byte)0,	// 47 - N/A
		(byte)passE,	// 48 - FLUSH BUFFERS
	};
	private byte[] flag3 = {
		0 // TODO: CP/M 3 functions...
	};

	private Z80 cpu;
	private Z80Disassembler disas;
	private long clock;
	private byte[] mem;
	private boolean running;
	private boolean stopped;
	private Semaphore stopWait;
	private ReentrantLock cpuLock;
	private Vector<String[]> cmds;

	private HostFileBdos hfb;
	private int dma;
	private int drv;
	private int usr;

	static final int wbootv = 0x0000;
	static final int bdosv = 0x0005;
	static final int fcb1 = 0x005c;
	static final int fcb2 = fcb1 + 16;
	static final int defdma = 0x0080;
	static final int tpa = 0x0100;
	static final int biose = 0xff00;
	static final int wboot = biose + 3;
	static final int memtop = biose - 512;	// need space for ALV
	static final int bdose = memtop + 6;
	static final int dpbuf = bdose + 3;
	static final int alvbf = dpbuf + 17;

	private static VirtualCpm vcpm;

	public static void main(String[] argv) {
		Properties props = new Properties();
		File f = new File("./vcpm.rc");
		if (!f.exists()) {
			f = new File(System.getProperty("user.home") + "/.vcpmrc");
		}
		if (f.exists()) {
			try {
				FileInputStream cfg = new FileInputStream(f);
				props.load(cfg);
				cfg.close();
			} catch(Exception ee) { }
		} else {
			// TODO: any alternate defaults?
		}
		vcpm = new VirtualCpm(props, argv);
		vcpm.start();
	}

	public VirtualCpm(Properties props, String[] argv) {
		String s;
		running = false;
		stopped = true;
		stopWait = new Semaphore(0);
		cpuLock = new ReentrantLock();
		cmds = new Vector<String[]>();
		cpu = new Z80(this);
		mem = new byte[65536];
		disas = new Z80DisassemblerMAC80(mem);
		HostFileBdos.initCfg('P', (byte)0x00, 1, null);
		HostFileBdos.initLsts(props, "vcpm");
		hfb = new HostFileBdos(props, "vcpm", new Vector<String>(), 0xfe);
		cmds.add(argv);
	}

	public void reset() {
		boolean wasRunning = running;
		clock = 0;
		stop();
		cpu.reset();
		if (wasRunning) {
			start();
		}
	}

	// These must NOT be called from the thread...
	public void start() {
		stopped = false;
		if (running) {
			return;
		}
		running = true;
		Thread t = new Thread(this);
		t.setPriority(Thread.MAX_PRIORITY);
		t.start();
	}
	public void stop() {
		stopWait.drainPermits();
		if (!running) {
			return;
		}
		running = false;
		// This is safer than spinning, but still stalls the thread...
		try {
			stopWait.acquire();
		} catch (Exception ee) {}
	}
	private void addTicks(int ticks) {
	}

	/////////////////////////////////////////
	/// Computer interface implementation ///

	public int peek8(int address) {
		int val = mem[address & 0xffff] & 0xff;
		return val;
	}
	public void poke8(int address, int value) {
		mem[address & 0xffff] = (byte)value;
	}

	// fetch Interrupt Response byte, IM0 (instruction bytes) or IM2 (vector).
	// Implementation must keep track of multi-byte instruction sequence,
	// and other possible state. For IM0, Z80 will call as long as 'intrFetch' is true.
	public int intrResp(Z80State.IntMode mode) {
		// Construct RST instruction form irq.
		int opCode = 0xc7 | (7 << 3);
		// TODO: prevent accidental subsequent calls?
		return opCode;
	}

	public void retIntr(int opCode) {
	}

	public int inPort(int port) {
		int val = 0;
		System.err.format("Undefined Input on port %02x\n", port);
		return val;
	}
	public void outPort(int port, int value) {
		System.err.format("Undefined Output on port %02x value %02x\n", port, value);
	}

	// No longer used...
	public void contendedStates(int address, int tstates) {
	}
	// not used?
	public long getTStates() {
		return clock;
	}

	public void breakpoint() {
	}
	public void execDone() {
	}

	private void setJMP(int adr, int vec) {
		mem[adr + 0] = (byte)0xc3;
		mem[adr + 1] = (byte)vec;
		mem[adr + 2] = (byte)(vec >> 8);
	}

	private void coldBoot() {
		setJMP(wbootv, wboot);
		setJMP(bdosv, bdose);
		setJMP(bdose, bdose);
		for (int x = 0; x < 33; ++x) {
			int v = biose + (x * 3);
			setJMP(v, v);
		}
	}

	private void warmBoot() {
		// we're done? or support SUBMIT? (requires a CCP)
		System.out.format("\n");
		running = false;
	}

	private void doPUSH(int val) {
		int sp = cpu.getRegSP();
		poke8(--sp, val >> 8);
		poke8(--sp, val);
		cpu.setRegSP(sp);
	}

	private int doPOP() {
		int val;
		int sp = cpu.getRegSP();
		val = (peek8(sp++) & 0xff);
		val |= ((peek8(sp++) & 0xff) << 8);
		cpu.setRegSP(sp);
		return val;
	}

	private void doRET() {
		int pc = doPOP();
		cpu.setRegPC(pc);
	}

	// Derived from HostFileBdos.copyOutDir()
	private void setupFCB(byte[] buf, int start, String arg) {
		int x = 0;
		int t = 0;
		if (arg.matches("[a-p]:.*")) {
			buf[start + x] = (byte)(arg.charAt(t) - 'a' + 1);
			t += 2;
		}
		++x;
		char b = ' ';
		char c;
		while (t < arg.length() && arg.charAt(t) != '.' && x < 9) {
			c = arg.charAt(t++);
			if (c == '*') {
				b = '?';
				break;
			}
			buf[start + x++] = (byte)Character.toUpperCase(c);
		}
		while (x < 9) {
			buf[start + x++] = (byte)b;
		}
		while (t < arg.length() && arg.charAt(t) != '.') {
			++t;
		}
		if (t < arg.length() && arg.charAt(t) == '.') {
			++t;
		}
		b = ' ';
		while (t < arg.length() && x < 12) {
			c = arg.charAt(t++);
			if (c == '*') {
				b = '?';
				break;
			}
			buf[start + x++] = (byte)Character.toUpperCase(c);
		}
		while (x < 12) {
			buf[start + x++] = (byte)b;
		}
	}

	private void setPage0(String[] argv) {
		Arrays.fill(mem, 0x0040, 0x0100, (byte)0);
		if (argv.length > 1) {
			setupFCB(mem, fcb1, argv[1].toLowerCase());
		} else {
			Arrays.fill(mem, fcb1 + 1, fcb1 + 12, (byte)' ');
		}
		if (argv.length > 2) {
			setupFCB(mem, fcb2, argv[2].toLowerCase());
		} else {
			Arrays.fill(mem, fcb2 + 1, fcb2 + 12, (byte)' ');
		}
		String s = "";
		for (int x = 1; x < argv.length; ++x) {
			s += ' ';
			s += argv[x];
		}
		if (s.length() > 127) {
			s = s.substring(0, 127);
		}
		mem[defdma] = (byte)s.length();
		if (s.length() > 0) {
			System.arraycopy(s.getBytes(), 0, mem, defdma + 1, s.length());
		}
	}

	private void logDrv(int d) {
		drv = d;
		mem[alvbf] = (byte)drv;
		hfb.bdosCall(14, mem, alvbf, 1, fcb1, dma);
	}

	private File search(int dr, int ur, String cmd) {
		boolean dot = (cmd.indexOf('.') > 0);
		int d = dr;
		if (d < 0) {
			d = drv;
		}
		File path = new File(hfb.cpmPath(d, ur, cmd));
		if (dot || path.exists()) {
			logDrv(d);
			return path;
		}
		path = new File(hfb.cpmPath(d, ur, cmd + ".com"));
		if (path.exists()) {
			logDrv(d);
			return path;
		}
		path = new File(hfb.cpmPath(d, ur, cmd + ".sub"));
		if (path.exists()) {
			logDrv(d);
			return path;
		}
		if (ur != 0) {
			return search(dr, 0, cmd);
		}
		if (dr < 0) {
			return search(0, 0, cmd);
		}
		return path; // will fail...
	}

	private boolean loadSUB(File path, String[] argv) {
		// TODO: or:
		// cmds.clear(); // ???
		try {
			String s;
			FileInputStream sub = new FileInputStream(path);
			BufferedReader br = new BufferedReader(new InputStreamReader(sub));
			while ((s = br.readLine()) != null)   {
				if (s.startsWith("#") || s.startsWith(";")) {
					continue;
				}
				for (int x = 1; x < argv.length; ++x) {
					String var = String.format("$%d", x);
					s = s.replace(var, argv[x]);
				}
				cmds.add(s.split("\\s"));
				// TODO: or:
				// cmds.add(?, s.split("\\s"));
			}
			br.close();
			return true;
		} catch (Exception ee) {
			System.out.println(ee.getMessage());
			return false;
		}
	}

	private boolean loadCOM(File path) {
		try {
			InputStream in = new FileInputStream(path);
			in.read(mem, tpa, memtop - tpa);
			in.close();
			return true;
		} catch (Exception ee) {
			System.out.println(ee.getMessage());
			return false;
		}
	}

	private void doCCP(String[] argv) {
		String cmd = argv[0].toLowerCase();
		// TODO: support user number designation...
		if (cmd.matches("[a-p]:")) {
			drv = cmd.charAt(0) - 'a';
			running = false;
			return;
		}
		// TODO: pre-init default drive... user?
		int d = -1;
		int u = 0;
		if (cmd.matches("[a-p]:.*")) {
			d = cmd.charAt(0) - 'a';
			cmd = cmd.substring(2);
		}
		File path = search(d, u, cmd);
		boolean ok = false;
		if (path.getName().endsWith(".sub")) {
			ok = loadSUB(path, argv);
		} else {
			ok = loadCOM(path);
		}
		if (!ok) {
			// TODO: take more-direct action?
			cpu.setRegPC(wboot);
			return;
		}
		setPage0(argv);
		cpu.setRegSP(memtop);
		doPUSH(wboot);
		cpu.setRegPC(tpa);
	}

	private void biosTrap(int pc) {
		int v = (pc & 0xff) / 3;
		switch (v) {
		case 0:	// cold boot
		case 1:	// warm boot
			warmBoot();
			break;
		case 2:	// const
			// TODO: support conin...
			cpu.setRegA(0);
			doRET();
			break;
		case 3:	// conin
			// TODO: support conin...
			cpu.setRegA(0);
			doRET();
			break;
		case 4:	// conout
			System.out.append((char)cpu.getRegC());
			System.out.flush();
			doRET();
			break;
		case 17: // conost
			cpu.setRegA(0xff);
			doRET();
			break;
		case 5:	// list
		case 6:	// auxout
		case 7:	// auxin
		case 8:	// home
		case 9:	// seldsk
		case 10: // settrk
		case 11: // setsec
		case 12: // setdma
		case 13: // read
		case 14: // write
		case 15: // listst
		case 16: // sectrn
		case 18: // auxist
		case 19: // auxost
		case 20: // devtbl
		case 21: // devini
		case 22: // drvtbl
		case 23: // multio
		case 24: // flush
		case 25: // move
		case 26: // time
		case 27: // selmem
		case 28: // setbnk
		case 29: // xmove
		case 30: // userf
		case 31: // resv1
		case 32: // resv2
		default:
			System.err.format("Unsupported BIOS call to %04x\n", pc);
			running = false;
			break;
		}
	}

	private int bdosChar(int fnc) {
		int hl = 0;
		int e;
		switch (fnc) {
		case 1:	// conin
			break;
		case 2:	// conout
			System.out.append((char)cpu.getRegE());
			System.out.flush();
			break;
		case 6:	// dircon
			e = cpu.getRegE();
			if (e == 0xff) {
				// TODO: conin/const
			} else if (e == 0xfe) {
				// TODO: const
			} else if (e == 0xfd) {
				// TODO: conin
			} else {
				System.out.append((char)e);
				System.out.flush();
			}
			break;
		case 9:	// print
			break;
		case 10: // conlin
			break;
		case 3:	// auxin
		case 4:	// auxout
		case 7:	// auxist
		case 8:	// auxost
		default:
			System.err.format("Unsupport char I/O %d %02x\n", fnc,
				cpu.getRegE());
		}
		return hl;
	}

	// Only called for fnc: 5, 14..25, 27..31, 33..
	private int bdosDisk(int fnc) {
		int de = cpu.getRegDE();	// fcb or param...
		int param = alvbf;
		int hl = 0;
		int len = 1;	// most don't care...
		int flg = 0;
		if (fnc == 5) {	// list output
			// TODO: support CP/M 3 "list buffer"
			mem[param] = (byte)0;	// LST: id
			mem[param + 1] = (byte)de;
			++len;	// the only case where 'len' matters
		} else if (fnc == 14) {	// select drive
			drv = de & 0x0f;
			mem[param] = (byte)drv;
		} else {
			flg = flags[fnc - 15] & 0xff;
		}
		if (fnc == 31) {	// get DPB
			param = dpbuf;
			hl = dpbuf;
		}
		if ((flg & passE) != 0) {
			mem[param] = (byte)de;
		} else if ((flg & passDE) != 0) {
			mem[param] = (byte)de;
			mem[param + 1] = (byte)(de >> 8);
			++len;
		} else if ((flg & passUSR) != 0) {
			mem[param] = (byte)usr;
		} else if ((flg & passDRV) != 0) {
			mem[param] = (byte)drv;
		}
		int rsp = hfb.bdosCall(fnc, mem, param, len, de, dma);
		if (rsp <= 0) { // ???
			hl = 0xffff;
		} else if (rsp == 1) {
			hl = (mem[param] & 0xff);
		} else if (rsp == 2) {
			hl = (mem[param] & 0xff) |
				((mem[param + 1] & 0xff) << 8);
		} else if (fnc == 17 || fnc == 18) {	// SEARCH
			hl = (mem[param] & 0xff);
			if (rsp >= 128) {
				System.arraycopy(mem, param + 1, mem, dma, 128);
			} else {	// must be 32...
				int ix = (hl << 5);
				System.arraycopy(mem, param + 1, mem, dma + ix, 32);
			}
		} else if (fnc == 27) {	// get ALV
			hl = alvbf;
		} else if (fnc == 46) {
			mem[dma] = mem[param];
			mem[dma + 1] = mem[param + 1];
			mem[dma + 2] = mem[param + 2];
		}
		return hl;
	}

	private void bdosRESET() {
		dma = defdma;
		drv = 0;
		// more?
	}

	private void bdosTrap(int pc) {
		if (pc != bdose) {
			System.err.format("Invalid BDOS entry at %04x\n", pc);
			running = false;
			return;
		}
		int fnc = cpu.getRegC();
		int de = cpu.getRegDE();
		int hl = 0;
		if (fnc == 0) {
			warmBoot();
			return;
		}
		if (fnc < 12 && fnc != 5) {
			hl = bdosChar(fnc);
		} else if (fnc == 12) {	// get version
			// TODO: what version to report?
			hl = 0x0022;
		} else if (fnc == 13) {	// reset BDOS
			bdosRESET();
		} else if (fnc == 25) {	// get cur drv
			hl = drv;
		} else if (fnc == 26) {	// set DMA
			dma = de;
		} else if (fnc == 32) {	// set/get USER
			if ((de & 0xff) == 0xff) {
				hl = usr;
			} else {
				usr = (de & 0x1f);
			}
		} else if (fnc <= 48) {
			hl = bdosDisk(fnc);
		} else {
			hl = 0xffff;
		}
		cpu.setRegHL(hl);
		cpu.setRegA(cpu.getRegL());
		cpu.setRegB(cpu.getRegH());
		doRET();
	}

	private void cpuDump() {
		cpuDump(cpu.getRegPC());
	}

	private void cpuDump(int pc) {
		System.err.format("%04x: %02x %02x %02x %02x : " +
				"%02x %04x %04x %04x [%04x] %s\n",
			pc,
			mem[pc], mem[pc + 1], mem[pc + 2], mem[pc + 3],
			cpu.getRegA(),
			cpu.getRegBC(),
			cpu.getRegDE(),
			cpu.getRegHL(),
			cpu.getRegSP(),
			disas.disas(pc)
			);
	}

	private void coreDump() {
		try {
			FileOutputStream f = new FileOutputStream("vcpm.core");
			f.write(mem);
			f.close();
			System.err.format("CP/M core dumped\n");
		} catch (Exception ee) {
			ee.printStackTrace();
		}
	}

	private void osTrap(int pc) {
		if (pc >= biose) {
			biosTrap(pc);
		} else {
			bdosTrap(pc);
		}
	}

	//////// Runnable /////////
	public void run() {
		int clk = 0;
		while (cmds.size() > 0) {
			coldBoot(); // always do this?
			bdosRESET();
			running = true;
			String[] cmd = cmds.remove(0);
			doCCP(cmd); // parse command... setup execution...
			while (running) {
				int PC = cpu.getRegPC();
				if (PC >= memtop) {
					osTrap(PC);
					if (!running) {
						break;
					}
				}
				clk = cpu.execute();
//cpuDump(PC);
			}
		}
//coreDump();
		stopped = true;
		stopWait.release();
	}

	public String dumpDebug() {
		String ret = "";
		return ret;
	}
}
