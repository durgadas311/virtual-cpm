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
	static final int passDRV = 0x08;
	static final int useMSC = 0x10;

	private byte[] flags = {
		(byte)passUSR,		// 15 - OPEN
		(byte)passUSR,		// 16 - CLOSE
		(byte)passDRV,		// 17 - SEARCH FIRST
		(byte)0,		// 18 - SEARCH NEXT
		(byte)passUSR,		// 19 - DELETE
		(byte)(passUSR|useMSC),	// 20 - READ SEQ
		(byte)(passUSR|useMSC),	// 21 - WRITE SEQ
		(byte)passUSR,		// 22 - MAKE
		(byte)passUSR,		// 23 - RENAME
		(byte)0,		// 24 - GET LOGIN VEC
		(byte)0,		// 25 - N/A
		(byte)0,		// 26 - N/A
		(byte)passDRV,		// 27 - GET ALLOC VEC
		(byte)passDRV,		// 28 - SET R/O
		(byte)0,		// 29 - GET R/O VEC
		(byte)passUSR,		// 30 - SET ATTR
		(byte)passDRV,		// 31 - GET DPB
		(byte)0,		// 32 - N/A
		(byte)(passUSR|useMSC),	// 33 - READ RND
		(byte)(passUSR|useMSC),	// 34 - WRITE RND
		(byte)passUSR,		// 35 - COMP SIZE
		(byte)passUSR,		// 36 - SET RND REC
		(byte)passDE,		// 37 - RESET DRIVES
		(byte)passDE,		// 38 - ACCESS DRIVES
		(byte)passDE,		// 39 - FREE DRIVES
		(byte)(passUSR|useMSC),	// 40 - WRITE RND ZF
		(byte)0,		// 41 - N/A
		(byte)passUSR,		// 42 - LOCK REC
		(byte)passUSR,		// 43 - UNLOCK REC
		(byte)0,		// 44 - N/A
		(byte)0,		// 45 - N/A
		(byte)passE,		// 46 - GET FREE SPACE
		(byte)0,		// 47 - N/A
		(byte)passE,		// 48 - FLUSH BUFFERS
	};
	private byte[] flag3 = {
		(byte)0,		// 98 - FREE BLOCKS
		(byte)passUSR,		// 99 - TRUNC FILE
		(byte)-1,		// 100 - SET DIR LABEL *
		(byte)0,		// 101 - GET DIR LABEL
		(byte)passUSR,		// 102 - GET FILE STAMPS
		(byte)-1,		// 103 - WRITE XFCB *
		(byte)-1,		// 104 - SET DATE/TIME *
		(byte)0,		// 105 - GET DATE/TIME
		(byte)0,		// 106 - SET DEF PASSWORD
	};
	static String fdelim = " \t\r\000;=<>.:,|[]";

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
	private BufferedReader lin;

	static final int wbootv = 0x0000;
	static final int bdosv = 0x0005;
	static final int fcb1 = 0x005c;
	static final int fcb2 = fcb1 + 16;
	static final int defdma = 0x0080;
	static final int tpa = 0x0100;
	static final int biose = 0xff00;
	static final int nbios = 33;
	static final int wboot = biose + 3;
	static final int memtop = biose - 512;	// need space for ALV
	static final int bdose = memtop + 6;
	static final int dpbuf = bdose + 3;
	static final int alvbf = dpbuf + 17;
	static final int scb = biose + (nbios * 3);	// requires 100 bytes

	// SCB addresses used internally
	static final int SCB_VER = scb + 0x05;
	static final int SCB_PGMRET = scb + 0x10;
	static final int SCB_CMODE = scb + 0x33;
	static final int SCB_OUTDLM = scb + 0x37;
	static final int SCB_SCBADD = scb + 0x3a; // base address of SCB
	static final int SCB_DMAAD = scb + 0x3c;
	static final int SCB_DRV = scb + 0x3e;
	static final int SCB_USER = scb + 0x44;
	static final int SCB_MULTCNT = scb + 0x4a;
	static final int SCB_ERRMD = scb + 0x4b;
	static final int SCB_DSO = scb + 0x4c;
	static final int SCB_TMP = scb + 0x50;
	static final int SCB_DATE = scb + 0x58; // date,hours,min,sec

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
		String s = System.getenv("CPMDrives");
		if (s != null) {
			int x = 0;
			for (String ss : s.split(",")) {
				if (ss == null || ss.length() == 0) {
					++x;
					continue;
				}
				String p = String.format("vcpm_drive_%c", (char)('a' + x));
				props.setProperty(p, ss);
				++x;
			}
		}
		for (int x = 0; x < 16; ++x) {
			String v = String.format("CPMDrive_%c", (char)('A' + x));
			s = System.getenv(v);
			if (s == null || s.length() == 0) {
				continue;
			}
			String p = String.format("vcpm_drive_%c", (char)('a' + x));
			props.setProperty(p, s);
		}
		s = System.getenv("CPMDefault");
		if (s == null) {
			s = "0A:";
		}
		vcpm = new VirtualCpm(props, argv, s.toLowerCase());
		vcpm.start();
	}

	public VirtualCpm(Properties props, String[] argv, String defdrv) {
		String s;
		running = false;
		stopped = true;
		stopWait = new Semaphore(0);
		cpuLock = new ReentrantLock();
		cmds = new Vector<String[]>();
		cpu = new Z80(this);
		mem = new byte[65536];
		disas = new Z80DisassemblerMAC80(mem);
		lin = new BufferedReader(new InputStreamReader(System.in));
		HostFileBdos.initCfg('P', (byte)0x00, 1, null);
		HostFileBdos.initLsts(props, "vcpm");
		hfb = new HostFileBdos(props, "vcpm", new Vector<String>(), 0xfe);
		cmds.add(argv);
		if (!chkSetDef(defdrv)) {
			// Message already printed...
			setDrv(0);
			mem[SCB_USER] = 0;
		}
		mem[SCB_MULTCNT] = 1;
		// TODO: implement BDOS error modes
		mem[SCB_ERRMD] = (byte)0;
		setWORD(SCB_DMAAD, defdma);
		setWORD(SCB_PGMRET, 0);	// TODO: when, if ever, is this cleared?
		s = props.getProperty("vcpm_dso");
		if (s != null) {
			int x = 0;
			for (String ss : s.split(",")) {
				if (x >= 4) {
					break;
				}
				if (ss.equalsIgnoreCase("def")) {
					mem[SCB_DSO + x++] = 0;
				} else if (ss.matches("[a-pA-P]:?")) {
					mem[SCB_DSO + x++] = (byte)(Character.toUpperCase(ss.charAt(0)) - 'A' + 1);
				} else {
					System.err.format("Invalid drive search order: %s\n", ss);
					// TODO: fatal? at least stop parsing here.
					break;
				}
			}
			while (x < 4) {
				mem[SCB_DSO + x++] = (byte)-1;
			}
		} else {
			mem[SCB_DSO + 0] = (byte)0;	// cur drv
			mem[SCB_DSO + 1] = (byte)1;	// A:
			mem[SCB_DSO + 2] = (byte)-1;	// end
			mem[SCB_DSO + 3] = (byte)-1;	// end
		}
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

	private int getWORD(int adr) {
		int val = mem[adr + 0] & 0xff;
		val |= ((mem[adr + 1] & 0xff) << 8);
		return val;
	}

	private void setWORD(int adr, int vec) {
		mem[adr + 0] = (byte)vec;
		mem[adr + 1] = (byte)(vec >> 8);
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
		for (int x = 0; x < nbios; ++x) {
			int v = biose + (x * 3);
			setJMP(v, v);
		}
		// Setup SCB as needed...
		mem[SCB_VER] = (byte)0x31;	// TODO: allow custom?
		setWORD(SCB_SCBADD, scb);
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
	// TODO: stop on invalid CP/M file chars...
	private boolean setupFCB(byte[] buf, int start, String arg) {
		boolean afn = false;
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
				afn = true;
				b = '?';
				break;
			}
			if (c == '?') afn = true;
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
				afn = true;
				b = '?';
				break;
			}
			if (c == '?') afn = true;
			buf[start + x++] = (byte)Character.toUpperCase(c);
		}
		while (x < 12) {
			buf[start + x++] = (byte)b;
		}
		return afn;
	}

	private int scanFn(int str, int ref) {
		// TODO: scan filespec
		return str;
	}

	private int parseFCB(int de) {
		int str = (mem[de] & 0xff) |
				((mem[de + 1] & 0xff) << 8);
		int fcb = (mem[de + 2] & 0xff) |
				((mem[de + 3] & 0xff) << 8);
		int ist = str;
		// TODO: no fail-safe limit? (aside from FFFF)
		while (fdelim.indexOf((char)(mem[str] & 0xff)) < 0) {
			++str;
			if (str > 0xffff) {
				return 0xffff;
			}
		}
		String fn = new String(mem, ist, str - ist);
		boolean afn = setupFCB(mem, fcb, fn.toLowerCase());
		ist = str;
		int c = 0;
		do {
			c = mem[str++];
		} while (str <= 0xffff && (c == ' ' || c == '\t'));
		if (str > 0xffff) {
			return 0xffff;
		}
		if (c == 0 || c == '\r') {
			return 0;
		}
		if (fdelim.indexOf(c) < 0) {
			return ist;
		} else {
			return str;
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
			s += argv[x].toUpperCase();
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
		int v = (1 << (d & 0x0f));
		mem[alvbf] = (byte)v;
		mem[alvbf + 1] = (byte)(v >> 8);
		hfb.bdosCall(38, mem, alvbf, 2, fcb1, defdma);
	}

	private void setDrv(int d) {
		mem[SCB_DRV] = (byte)(d & 0x0f);
		mem[alvbf] = mem[SCB_DRV];
		hfb.bdosCall(14, mem, alvbf, 1, fcb1, defdma);
	}

	private File search(int dr, int ur, String cmd) {
		boolean dot = (cmd.indexOf('.') > 0);
		int d = dr;
		// dr < 0: -1.. = drive search order [0..]
		if (dr < 0) {
			d = mem[SCB_DSO + (0 - dr) - 1] - 1;
			if (d < 0) {
				d = mem[SCB_DRV];
			}
		}
		File path;
		if (dot) {
			path = new File(hfb.cpmPath(d, ur, cmd));
			if (path.exists()) {
				logDrv(d);
				return path;
			}
		} else {
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
		}
		if (ur != 0) {
			return search(dr, 0, cmd);
		}
		if (dr < 0) {	// no drive specified
			--dr;
			d = (0 - dr) - 1;
			if (d < 4 && mem[SCB_DSO + d] >= 0) {
				return search(dr, ur, cmd);
			}
		}
		return path; // will fail...
	}

	private boolean loadSUB(File path, String[] argv) {
		// TODO: or: cmds.clear(); // ???
		try {
			String s;
			FileInputStream sub = new FileInputStream(path);
			BufferedReader br = new BufferedReader(new InputStreamReader(sub));
			while ((s = br.readLine()) != null)   {
				s = s.trim();
				// TODO: allow continuation?
				if (s.length() == 0) {
					continue;
				}
				// TODO: allow comments at mid-line?
				if (s.startsWith("#") || s.startsWith(";")) {
					continue;
				}
				for (int x = 1; x < 10; ++x) {
					String var = String.format("$%d", x);
					if (x < argv.length) {
						s = s.replace(var, argv[x]);
					} else {
						s = s.replace(var, "");
					}
				}
				if (s.startsWith("<")) {
					cmds.add(new String[]{s});
				} else {
					cmds.add(s.split("\\s"));
				}
				// TODO: or insert in-place:
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

	private void doERA(String[] argv) {
		// TODO: use hfb.cpmPath() instead?
		if (setupFCB(mem, fcb1, argv[1].toLowerCase())) {
			// if (mem[fcb1 + 1] == '?' && mem[fcb1 + 9] == '?') {
			//	TODO: prompt "ALL (Y/N)?"
			System.out.format("ERA %s (Y/N)?", argv[1]);
			String s = getconlin();
			if (s == null || !s.equalsIgnoreCase("Y")) {
				return;
			}
		}
		mem[alvbf] = mem[SCB_USER];
		int rsp = hfb.bdosCall(19, mem, alvbf, 1, fcb1, defdma);
		int ent = mem[alvbf] & 0xff;
		if (ent > 3) { // assume FF
			// TODO: specific failure?
			System.out.println("Not erased");
		}
	}

	// Get string from FCB (dir entry) name fields.
	private String getFileName(byte[] buf, int start) {
		String n = "";
		int x = 0;
		for (; x < 8; ++x) {
			char c = (char)(buf[start + x] & 0x7f);
			if (c == ' ') {
				x = 8;
				break;
			}
			n += c;
		}
		for (; x < 11; ++x) {
			char c = (char)(buf[start + x] & 0x7f);
			if (c == ' ') break;
			if (x == 8) {
				n += '.';
			}
			n += c;
		}
		return n;
	}

	private String getFileDrive(String fn) {
		int d = mem[SCB_DRV];
		if (fn.matches("[a-p]:.*")) {
			d = fn.charAt(0) - 'a';
			fn = fn.substring(2);
		}
		return hfb.cpmPath(d, mem[SCB_USER], fn);
	}

	private void doREN(String[] argv) {
		if (argv.length < 2 || argv.length > 3) {
			System.out.println("REN syntax error");
			return;
		}
		int eq = argv[1].indexOf('=');
		File fn;
		File fo;
		if (eq > 0) {
			if (argv.length > 2) {
				System.out.println("REN syntax error");
				return;
			}
			String[] f = argv[1].split("=");
			fn = new File(getFileDrive(f[0]));
			fo = new File(getFileDrive(f[1]));
		} else {
			fn = new File(getFileDrive(argv[1]));
			fo = new File(getFileDrive(argv[2]));
		}
		// TODO: need to check for same drive?
		fo = new File(fn.getParent() + "/" + fo.getName());
		if (fn.exists()) {
			System.out.println("File exists");
			return;
		}
		if (!fo.exists()) {
			System.out.println("No file");
			return;
		}
		try {
			fo.renameTo(fn);
		} catch (Exception ee) {
			System.out.println(ee.getMessage());
		}
	}

	private void doDIR(String[] argv) {
		if (argv.length > 2) {
			System.out.println("DIR syntax error");
			return;
		}
		if (argv.length == 1) {
			setupFCB(mem, fcb1, "*.*");
		} else if (argv[1].matches(".:")) {
			setupFCB(mem, fcb1, argv[1] + "*.*");
		} else {
			setupFCB(mem, fcb1, argv[1]);
		}
		String d = "";
		if (mem[fcb1] != 0) {
			d += (char)((mem[fcb1] & 0xff) - 1 + 'A');
		} else {
			d += (char)(mem[SCB_DRV] + 'A');
		}
		d += ':';
		d += ' ';
		int fnc = 17;
		int cnt = 0;
		int col = 0;
		do {
			mem[alvbf] = mem[SCB_DRV];
			mem[alvbf + 1] = mem[SCB_USER];
			int rsp = hfb.bdosCall(fnc, mem, alvbf, 2, fcb1, defdma);
			int ent = mem[alvbf] & 0xff;
			if (ent > 3) { // assume FF
				break;
			}
			fnc = 18;
			// TODO: allow selecting SYS...
			if ((mem[alvbf + 11] & 0x80) != 0) { // SYS
				continue;
			}
			++cnt;
			if (col == 0) {
				System.out.format(d);
			}
			System.out.format("%-15s", getFileName(mem, alvbf + 2));
			++col;
			if (col >= 5) {
				System.out.format("\n");
				col = 0;
			}
		} while (true);
		if (col != 0) {
			System.out.format("\n");
		}
		if (cnt == 0) {
			System.out.println("No file");
		}
	}

	private void doTYPE(String[] argv) {
		if (argv.length != 2) {
			System.out.println("TYPE syntax error");
			return;
		}
		File f = new File(getFileDrive(argv[1]));
		if (!f.exists()) {
			System.out.println("No file" + f.getAbsolutePath());
			return;
		}
		try {
			String s;
			FileInputStream tf = new FileInputStream(f);
			BufferedReader br = new BufferedReader(new InputStreamReader(tf));
			while ((s = br.readLine()) != null)   {
				System.out.println(s);
			}
		} catch (Exception ee) {
			System.out.println(ee.getMessage());
		}
	}

	private void doSAVE(String[] argv) {
		if (argv.length != 3) {
			System.out.println("SAVE syntax error");
			return;
		}
		int npg = 0;
		File f = new File(getFileDrive(argv[2]));
		try {
			npg = Integer.valueOf(argv[1]);
			if (npg <= 0 || npg > 254) {
				return;
			}
			FileOutputStream fo = new FileOutputStream(f);
			fo.write(mem, tpa, npg * 256);
		} catch (Exception ee) {
			System.out.println(ee.getMessage());
			return;
		}
	}

	private boolean ccpBuiltin(String cmd, String[] argv) {
		if (cmd.equals("era")) {
			doERA(argv);
		} else if (cmd.equals("ren")) {
			doREN(argv);
		} else if (cmd.equals("dir")) {
			doDIR(argv);
		} else if (cmd.equals("type")) {
			doTYPE(argv);
		} else if (cmd.equals("save")) {
			doSAVE(argv);
		} else {
			return false;
		}
		return true;
	}

	private boolean chkSetDef(String cmd) {
		if (cmd.matches("[0-9a-p]+:")) {
			int ix = 0;
			int u = 0;
			int d = 0;
			boolean gotu = false;
			boolean gotd = false;
			boolean err = false;
			if (Character.isDigit(cmd.charAt(ix))) {
				while (Character.isDigit(cmd.charAt(ix))) {
					u = (u * 10) + cmd.charAt(ix++) - '0';
				}
				gotu = true;
				err = (u > 31);	// TODO: limit 15?
			}
			if (!err && Character.isLetter(cmd.charAt(ix))) {
				d = cmd.charAt(ix++) - 'a';
				gotd = true;
				err = (d > 15);
			}
			if (!err && !gotu && Character.isDigit(cmd.charAt(ix))) {
				while (Character.isDigit(cmd.charAt(ix))) {
					u = (u * 10) + cmd.charAt(ix++) - '0';
				}
				gotu = true;
				err = (u > 31);	// TODO: limit 15?
			}
			if (!err) {
				err = (cmd.charAt(ix) != ':');
			}
			if (err) {
				System.err.format("Syntax error: \"%s\"\n", cmd);
				// TODO: abort everything?
			} else {
				if (gotd) setDrv(d);
				if (gotu) mem[SCB_USER] = (byte)u;
				return true;
			}
		}
		return false;
	}

	private void doCCP(String[] argv) {
		String cmd = "";
		cmd += (char)(mem[SCB_DRV] + 'A');
		if (mem[SCB_USER] > 0) {
			cmd += String.format("%d", mem[SCB_USER]);
		}
		cmd += '>';
		cmd += argv[0];
		for (int x = 1; x < argv.length; ++x) {
			cmd += ' ';
			cmd += argv[x];
		}
		System.out.format("%s\n", cmd);
		cmd = argv[0].toLowerCase();
		if (chkSetDef(cmd)) {
			running = false;
			return;
		}
		if (ccpBuiltin(cmd, argv)) {
			running = false; // skip to next command
			return;
		}
		int d = -1;
		int u = 0;
		// TODO: can prefix include user number?
		if (cmd.matches("[a-p]:.*")) {
			d = cmd.charAt(0) - 'a';
			cmd = cmd.substring(2);
		}
		File path = search(d, u, cmd);
		boolean ok = false;
		if (path.getName().endsWith(".sub")) {
			ok = loadSUB(path, argv);
			// nothing to run, yet...
			running = false; // skip to next command
			return;
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
		doBIOS(v);
	}

	private int constat() {
		int a = 0;
		try {
			if (lin.ready()) {
				a = 255;
			}
		} catch (Exception ee) {}
		return a;
	}

	private int conin() {
		int a = 0;
		try {
			a = lin.read();
			// TODO: how to pass a real ^J/LF?
			if (a == '\n') a = '\r';
		} catch (Exception ee) {}
		return a;
	}

	private String getconlin() {
		String s = null;
		String[] ss;
		ss = cmds.get(0);
		if (ss.length == 1 && ss[0].startsWith("<")) {
			cmds.remove(0);
			s = ss[0].substring(1);
			System.out.format("%s", s);
		} else try {
			// TODO: prevent echo of LF?
			s = lin.readLine();
		} catch(Exception ee) {}
		return s;
	}

	private void conlin(int de) {
		int mx;
		int x;
		if (de == 0) {
			de = getWORD(SCB_DMAAD);
		}
		String s = getconlin();
		if (s == null) {
			return;
		}
		mx = mem[de] & 0xff;
		for (x = 0; x < mx && x < s.length(); ++x) {
			mem[de + 2 + x] = (byte)s.charAt(x);
		}
		mem[de + 1] = (byte)x;
	}

	private void doBIOS(int v) {
		int a;
		switch (v) {
		case 0:	// cold boot
		case 1:	// warm boot
			warmBoot();
			break;
		case 2:	// const
			a = constat();
			cpu.setRegA(a);
			doRET();
			break;
		case 3:	// conin
			a = conin();
			cpu.setRegA(a);
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
		case 26: // time
			// We don't support time set.
			// We also don't support "continuous" mode,
			// or any other "watching" of the SCB values.
			// (users MUST call this every time)
			a = cpu.getRegC();
			if (a == 0) { // get time
				hfb.bdosCall(105, mem, SCB_DATE, 1, fcb1, defdma);
			}
			break;
		case 5:	// list
			// TODO: implement bdosCall
		case 6:	// auxout
		case 7:	// auxin
		// TODO: simulate disk I/O on boot tracks...
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
		case 27: // selmem
		case 28: // setbnk
		case 29: // xmove
		case 30: // userf
		case 31: // resv1
		case 32: // resv2
		default:
			System.err.format("Unsupported BIOS call %d\n", v);
			running = false;
			break;
		}
	}

	private int bdosChar(int fnc, int de) {
		int hl = 0;
		int e = de & 0xff;
		switch (fnc) {
		case 1:	// conin
			hl = conin();
			break;
		case 2:	// conout
			System.out.append((char)e);
			System.out.flush();
			break;
		case 6:	// dircon
			if (e == 0xff) {
				hl = constat();
				if (hl != 0) {
					hl = conin();
				}
			} else if (e == 0xfe) {
				hl = constat();
			} else if (e == 0xfd) {
				hl = conin();
			} else {
				System.out.append((char)e);
				System.out.flush();
			}
			break;
		case 9:	// print string
			e = de;
			while ((mem[e] & 0xff) != mem[SCB_OUTDLM]) {
				System.out.append((char)(mem[e] & 0xff));
				++e;
				if (e > 0xffff) {
					break;
				}
			}
			System.out.flush();
			break;
		case 10: // conlin
			conlin(de);
			break;
		case 11: // const
			hl = constat();
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

	private int getRR(byte[] mem, int fcb) {
		int rr = ((mem[fcb + 35] & 0xff) << 16) |
			((mem[fcb + 34] & 0xff) << 8) |
			(mem[fcb + 33] & 0xff);
		return rr;
	}

	private void putRR(byte[] mem, int fcb, int rr) {
		mem[fcb + 35] = (byte)(rr >> 16);
		mem[fcb + 34] = (byte)(rr >> 8);
		mem[fcb + 33] = (byte)rr;
	}

	private int mscCall(int fnc, byte[] mem, int param, int len, int fcb, int dma) {
		int cnt = mem[SCB_MULTCNT] & 0xff;
		int rsp = 0;
		int rr = getRR(mem, fcb);
		while (cnt > 0) {
			// all these pass USER
			mem[param] = mem[SCB_USER];
			rsp = hfb.bdosCall(fnc, mem, param, len, fcb, dma);
			if (mem[param] != 0) {
				cnt = (mem[SCB_MULTCNT] & 0xff) - cnt;
				break;
			}
			--cnt;
			dma += 128;
			if (fnc >= 33) {
				putRR(mem, fcb, getRR(mem, fcb) + 1);
			}
		}
		if (fnc >= 33) {
			rsp = mem[param];
			putRR(mem, fcb, rr);
			// TODO: reset file pointer! need to point dma to unused space!
			mem[param] = mem[SCB_USER];
			hfb.bdosCall(33, mem, param, len, fcb, 0xff80);
			mem[param] = (byte)rsp;
		}
		// put 'cnt' into H...
		mem[param + 1] = (byte)cnt;
		return 2;
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
			// same as setDrv()...
			mem[SCB_DRV] = (byte)(de & 0x0f);
			mem[param] = mem[SCB_DRV];
		} else if (fnc == 17) {	// search first
			if ((mem[de] & 0xff) == '?' && mem[SCB_VER] >= 0x30) {
				// TODO: must un-do this later?
				mem[de] |= 0x80;
			}
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
			mem[param] = mem[SCB_USER];
		} else if ((flg & passDRV) != 0) {
			mem[param] = mem[SCB_DRV];
		}
		int rsp;
		if ((flg & useMSC) != 0) {
			rsp = mscCall(fnc, mem, param, len, de, getWORD(SCB_DMAAD));
		} else {
			rsp = hfb.bdosCall(fnc, mem, param, len, de, getWORD(SCB_DMAAD));
		}
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
				System.arraycopy(mem, param + 1, mem, getWORD(SCB_DMAAD), 128);
			} else {	// must be 32...
				int ix = (hl << 5);
				System.arraycopy(mem, param + 1, mem, getWORD(SCB_DMAAD) + ix, 32);
			}
		} else if (fnc == 27) {	// get ALV
			hl = alvbf;
		} else if (fnc == 46) {
			int dma = getWORD(SCB_DMAAD);
			mem[dma] = mem[param];
			mem[dma + 1] = mem[param + 1];
			mem[dma + 2] = mem[param + 2];
		}
		return hl;
	}

	private void bdosRESET() {
		setWORD(SCB_DMAAD, defdma);
		mem[SCB_MULTCNT] = (byte)1;
		mem[SCB_ERRMD] = (byte)0;
		mem[SCB_OUTDLM] = '$';
		setWORD(SCB_CMODE, 0);
		//drv = 0; // No?
		// more?
	}

	// fnc >= 98...
	private int bdos3Ext(int fnc, int de) {
		int param = alvbf;
		if (fnc == 152) {	// parse FCB
			return parseFCB(de);
		} else if (fnc > 112) {
			return 0xffff;
		}
		int hl = 0;
		if (fnc == 112) {	// LIST block
			int str = (mem[de] & 0xff) |
				((mem[de + 1] & 0xff) << 8);
			int len = (mem[de + 2] & 0xff) |
				((mem[de + 3] & 0xff) << 8);
			mem[param] = (byte)0;	// LST: id
			int x = 0;
			while (len > 0) {
				int n = 0;
				// TODO: can this be done w/o copying?
				while (len > 0 && n < 255) {
					mem[param + ++n] = mem[str + x++];
					--len;
				}
				// DE,DMA must not be used...
				hfb.bdosCall(fnc, mem, param, n, de, defdma);
			}
		} else if (fnc == 111) { // PRINT block (to console)
			// TODO: echo to LST:?
			int str = (mem[de] & 0xff) |
				((mem[de + 1] & 0xff) << 8);
			int len = (mem[de + 2] & 0xff) |
				((mem[de + 3] & 0xff) << 8);
			String out = new String(mem, str, len);
			System.out.format("%s", out);
			System.out.flush();
		} else if (fnc == 110) { // set/get delim
			if (de == 0xffff) {
				hl = mem[SCB_OUTDLM];
			} else {
				mem[SCB_OUTDLM] = (byte)(de & 0xff);
			}
		} else if (fnc == 109) { // set/get cons mode
			if (de == 0xffff) {
				hl = getWORD(SCB_CMODE);
			} else {
				setWORD(SCB_CMODE, de);
			}
		} else if (fnc == 108) { // set/get ret code
			if (de == 0xffff) {
				hl = getWORD(SCB_PGMRET);
			} else {
				setWORD(SCB_PGMRET, de);
			}
		} else if (fnc == 107) { // get serial num
			// TODO: implement something?
		} else {
			int flg = flag3[fnc - 98];
			if (flg < 0) {
				return hl;
			}
			if ((flg & passUSR) != 0) {
				mem[param] = mem[SCB_USER];
			}
			int rsp = hfb.bdosCall(fnc, mem, param, 1, de, getWORD(SCB_DMAAD));
			if (rsp <= 0) { // ???
				hl = 0xffff;
			} else if (rsp == 1) {
				hl = (mem[param] & 0xff);
			} else if (rsp == 2) {
				hl = (mem[param] & 0xff) |
					((mem[param + 1] & 0xff) << 8);
			}
		}
		return hl;
	}

	private int doSCB(int de) {
		int hl = 0;
		int off = mem[de] & 0xff;
		if (off >= 100) {
			return hl;
		}
		boolean get = (mem[de + 1] == 0);
		if (get) {
			hl = mem[scb + off] & 0xff;
			if ((mem[de + 1] & 1) == 0) {
				hl |= ((mem[scb + off + 1] & 0xff) << 8);
			}
			return hl;
		}
		int val = (mem[de + 2] & 0xff) |
			((mem[de + 3] & 0xff) << 8);
		mem[scb + off] = (byte)val;
		if ((mem[de + 1] & 1) == 0) {
			mem[scb + off + 1] = (byte)(val >> 8);
		}
		return hl;
	}

	private int dirBIOS(int de) {
		if (de > 0xfff8) {
			return 0xffff;
		}
		int v = mem[de++] & 0xff;
		cpu.setRegA(mem[de++] & 0xff);
		int rp = (mem[de++] & 0xff);
		rp |= ((mem[de++] & 0xff) << 8);
		cpu.setRegBC(rp);
		rp = (mem[de++] & 0xff);
		rp |= ((mem[de++] & 0xff) << 8);
		cpu.setRegDE(rp);
		rp = (mem[de++] & 0xff);
		rp |= ((mem[de++] & 0xff) << 8);
		cpu.setRegHL(rp);
		doBIOS(v);
		return 0;
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
			hl = bdosChar(fnc, de);
		} else if (fnc == 12) {	// get version
			hl = mem[SCB_VER] & 0xff;
		} else if (fnc == 13) {	// reset BDOS
			bdosRESET();
		} else if (fnc == 25) {	// get cur drv
			hl = mem[SCB_DRV];
		} else if (fnc == 26) {	// set DMA
			setWORD(SCB_DMAAD, de);
		} else if (fnc == 32) {	// set/get USER
			if ((de & 0xff) == 0xff) {
				hl = mem[SCB_USER];
			} else {
				mem[SCB_USER] = (byte)(de & 0x1f);
			}
		} else if (fnc == 44) {
			de &= 0xff;
			if (de < 1 || de > 128) de = 1;
			mem[SCB_MULTCNT] = (byte)de;
		} else if (fnc == 45) {
			mem[SCB_ERRMD] = (byte)de;
		} else if (fnc <= 48) {
			hl = bdosDisk(fnc);
		} else if (fnc == 49) {	// SCB
			hl = doSCB(de);
		} else if (fnc == 50) {	// direct BIOS
			hl = dirBIOS(de);
		} else if (fnc == 59 || fnc == 60) {	// load overlay / call RSX
			// unclear if this can be made to work,
			// or if it is needed.
			hl = 0x00ff;
		} else if (fnc >= 98) {
			hl = bdos3Ext(fnc, de);
		} else {
System.err.format("Unsupported BDOS function %d\n", fnc);
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

	private void coldStart() {
		// already done in ctor...
		//setDrv(0);
	}

	private void warmStart() {
		// TODO: 2.2 vs 3.1
		if (mem[SCB_VER] == 0x31) {
			hfb.bdosCall(98, mem, alvbf, 1, fcb1, defdma);
		} else {
			mem[alvbf] = (byte)0xff;
			mem[alvbf + 1] = (byte)0xff;
			hfb.bdosCall(39, mem, alvbf, 2, fcb1, defdma);
		}
		coldBoot(); // always do this?
		bdosRESET();
	}

	//////// Runnable /////////
	public void run() {
		int clk = 0;
		coldStart();
		while (cmds.size() > 0) {
			warmStart();
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
