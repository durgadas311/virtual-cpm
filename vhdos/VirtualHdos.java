// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Properties;

import z80core.*;
import z80debug.*;

public class VirtualHdos implements Computer, Memory, Runnable {
	static final int EC_OK = 0;	// no error
	static final int EC_EOF = 1;	// EOF
	static final int EC_ILC = 2;	// illegal syscall
	static final int EC_CNA = 3;	// channel not available
	static final int EC_DNS = 4;	// device not suitable
	static final int EC_IDN = 5;	// illegal device name
	static final int EC_IFN = 6;	// illegal file name
	static final int EC_NRD = 7;	// no room for device driver
	static final int EC_FNO = 8;	// channel not open
	static final int EC_ILR = 9;	// illegal request
	static final int EC_FUC = 10;	// file usage conflict
	static final int EC_FNF = 11;	// file not found
	static final int EC_UND = 12;	// unknown device
	static final int EC_ICN = 13;	// illegal channel number
	static final int EC_DIF = 14;	// directory full
	static final int EC_IFC = 15;	// illegal file contents
	static final int EC_NEM = 16;	// not enough memory
	static final int EC_RF = 17;	// read failure
	static final int EC_WF = 18;	// write failure
	static final int EC_WPV = 19;	// write prot violation
	static final int EC_WP = 20;	// disk write prot
	static final int EC_FAP = 21;	// file already present
	static final int EC_DDA = 22;	// device driver abort
	static final int EC_FL = 23;	// file locked
	static final int EC_FAO = 24;	// file already open
	static final int EC_IS = 25;	// illegal switch
	static final int EC_UUN = 26;	// unknown unit number
	static final int EC_FNR = 27;	// file name rquired
	static final int EC_DIW = 28;	// device not writeable
	static final int EC_UNA = 29;	// unit not available
	static final int EC_ILV = 30;	// illegal value
	static final int EC_ILO = 31;	// illegal option
	static final int EC_VPM = 32;	// volume presently mounted
	static final int EC_NVM = 33;	// no volume presently mounted
	static final int EC_FOD = 34;	// file open on device
	static final int EC_NPM = 35;	// no prov made for mounting
	static final int EC_DNI = 36;	// disk not initialized
	static final int EC_DNR = 37;	// disk not readable
	static final int EC_DSC = 38;	// disk structure corrupt
	static final int EC_NCV = 39;	// not correct version of HDOS
	static final int EC_NOS = 40;	// no OS mounted
	static final int EC_IOI = 41;	// illegal overlay index
	static final int EC_OTL = 42;	// overlay too large

	static final String[] devs = {
		"sy0", "sy1", "sy2", "sy3",
		"dk0", "dk1", "dk2", "dk3",
		"???"
	};

	private CPU cpu;
	private CPUTracer trc;
	private long clock;
	private byte[] mem;
	private boolean running;
	private boolean stopped;
	private Semaphore stopWait;
	private ReentrantLock cpuLock;
	private Vector<String[]> cmds;

	private HdosOpenFile[] chans;
	private String[] dirs;
	private String root;
	private BufferedReader lin;

	static final int hdosv = 0x0038; // the ONLY entry?
	static final int stack = 0x2280;
	static final int tpa = 0x2280;
	static final int memtop = 0xff00; // or ????
	static final int hdose = memtop - 256; // or ????

	private int intvec = memtop + 16;

	private static VirtualHdos vhdos;
	private static String coredump = null;
	private int exitCode = 0; // TODO: System.exit(exitCode)

	public static void main(String[] argv) {
		Properties props = new Properties();
		File f = new File("./vhdos.rc");
		if (!f.exists()) {
			f = new File(System.getProperty("user.home") + "/.vhdosrc");
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
		String s = System.getenv("HDOSDrives");
		if (s != null) {
			int x = 0;
			for (String ss : s.split(",")) {
				if (ss == null || ss.length() == 0) {
					++x;
					continue;
				}
				String p = String.format("vhdos_drive_%c", (char)('a' + x));
				props.setProperty(p, ss.trim());
				++x;
			}
		}
		for (int x = 0; x < 8; ++x) {
			String v = String.format("HDOSDrive_%s", devs[x]);
			s = System.getenv(v);
			if (s == null || s.length() == 0) {
				continue;
			}
			String p = String.format("vhdos_drive_%s", devs[x]);
			props.setProperty(p, s.trim());
		}
		s = System.getenv("VHDOSCoreDump");
		if (s != null) {
			props.setProperty("vhdos_dump", s);
		}
		s = System.getenv("VHDOSTrace");
		if (s != null) {
			props.setProperty("vhdos_trace", s);
		}
		s = System.getenv("VHDOSCPU");
		if (s != null) {
			props.setProperty("vhdos_cpu", s);
		}
		s = System.getenv("HDOSDefault");
		if (s == null) {
			s = "sy0";
		}
		vhdos = new VirtualHdos(props, argv, s.toLowerCase());
		vhdos.start();
	}

	public VirtualHdos(Properties props, String[] argv, String defdrv) {
		String s;
		int x;
		running = false;
		stopped = true;
		stopWait = new Semaphore(0);
		cpuLock = new ReentrantLock();
		cmds = new Vector<String[]>();
		dirs = new String[8];
		chans = new HdosOpenFile[8];
		mem = new byte[65536];
		boolean silent = (props.getProperty("silent") != null);
		String t = props.getProperty("vhdos_trace");
		s = props.getProperty("vhdos_cpu");
		if (s != null) {
			if (s.matches("[iI]?8080")) {
				cpu = new I8080(this);
				if (t != null) {
					trc = new I8080Tracer(props, "vhdos", cpu, this, t);
				}
			} else if (s.matches("[iI]?8085")) {
				cpu = new I8085(this);
				if (t != null) {
					trc = new I8085Tracer(props, "vhdos", cpu, this, t);
				}
			} else if (s.matches("[zZ]80")) {
				cpu = new Z80(this);
				if (t != null) {
					trc = new Z80Tracer(props, "vhdos", cpu, this, t);
				}
			} else if (s.matches("[zZ]180")) {
				Z180 z180 = new Z180(this, null, true); // Z80S180
				cpu = z180;
				if (t != null) {
					trc = new Z180Tracer(props, "vhdos", cpu, this, t);
				}
			}
		}
		if (cpu == null) {
			cpu = new Z80(this);
			if (t != null) {
				trc = new Z80Tracer(props, "vhdos", cpu, this, t);
			}
		}
		s = props.getProperty("vhdos_dump");
		if (s != null) {
			if (s.length() > 0) {
				coredump = s;
			} else {
				coredump = "vhdos.core";
			}
		}
		if (!silent) {
			System.err.format("Using CPU %s\n", cpu.getClass().getName());
		}

		try {
			String rom = "2716_444-19_H17.rom";
			InputStream fi = this.getClass().getResourceAsStream(rom);
			fi.read(mem, 0x1800, 0x0800);
			fi.close();
		} catch (Exception ee) {
			ee.printStackTrace();
			System.exit(1);
		}
		// Do H17 init...
		System.arraycopy(mem, 0x1f5a, mem, 0x2048, 88);
		Arrays.fill(mem, 0x20a0, 0x20be, (byte)0);
		setJMP(0x201f, intvec);
		setJMP(0x2022, intvec);
		setJMP(0x2025, intvec);
		setJMP(0x2028, intvec);
		setJMP(0x202b, intvec);
		setJMP(0x202e, intvec);
		setJMP(0x2031, intvec);
		//
		setJMP(0x0008, 0x201f);
		setJMP(0x0010, 0x2022);
		setJMP(0x0018, 0x2025);
		setJMP(0x0020, 0x2028);
		setJMP(0x0028, 0x202b);
		setJMP(0x0030, 0x202e);
		setJMP(0x0038, 0x2031);
		// ... not sure who is responsible for these...
		// setJMP(0x2108, sysvec++);	// S.SDD
		// setJMP(0x210b, sysvec++);	// S.FATSERR
		// setJMP(0x210e, sysvec++);	// S.DIREAD
		// setJMP(0x2111, sysvec++);	// S.FCI
		// setJMP(0x2114, sysvec++);	// S.SCI
		// setJMP(0x2117, sysvec++);	// S.GUP

		lin = new BufferedReader(new InputStreamReader(System.in));
		for (x = 0; x < dirs.length; ++x) {
			s = String.format("vhdos_drive_%s", hdosDevice(x));
			s = props.getProperty(s);
			if (s != null) {
				File f = new File(s);
				// TODO: f.mkdirs(); ?
				if (!f.exists() || !f.isDirectory()) {
					System.err.format("Invalid path in %s: %s\n",
						hdosDevice(x), s);
					continue;
				}
				dirs[x] = s;
			}
		}
		s = props.getProperty("vhdos_root_dir");
		if (s == null) {
			s = System.getProperty("user.home") + "/HostFileHdos";
		}
		root = s;
		for (x = 0; x < dirs.length; ++x) {
			if (dirs[x] != null) continue;
			dirs[x] = String.format("%s/%s", root, devs[x]);
		}
		s = System.getenv("VHDOSShow");
		if (s != null && s.length() == 3) {
			int dx = hdosDrive(s);
			if (dx >= 0) {
				System.out.println(dirs[dx]);
			}
			System.exit(0);
		}
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
	public void changeSpeed(int a,int b) {}

	private int getWORD(int adr) {
		return getWORD(mem, adr);
	}

	private int getWORD(byte[] buf, int adr) {
		int val = buf[adr + 0] & 0xff;
		val |= ((buf[adr + 1] & 0xff) << 8);
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

	private void setPage0(String[] argv) {
		// TODO: where is the commandline placed?
		// pushed onto stack...
		int sp = cpu.getRegSP();
		String s = "";
		for (int x = 1; x < argv.length; ++x) {
			s += ' ';
			s += argv[x].toUpperCase();
		}
		mem[--sp] = (byte)0;	// always NUL-terminated
		if (s.length() > 0) {
			sp -= s.length();
			System.arraycopy(s.getBytes(), 0, mem, sp, s.length());
		}
		cpu.setRegSP(sp);
	}

	// convert drive number to device string
	private String hdosDevice(int d) {
		if (d >= devs.length) d = devs.length - 1;
		return devs[d];
	}

	// TODO: convert "sy[0-9]:" or "dk[0-9]:" prefix to drive number
	private int hdosDrive(String s) {
		if (!s.matches("sy[0-3]") && !s.matches("dk[0-3]")) {
			return -1;
		}
		int d = s.charAt(2) - '0';
		if (s.charAt(0) == 'd') d += 4;
		return d;
	}

	// TODO: support this?
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
				s = s.replace("$$", "$");
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

	private int doABS(HdosOpenFile of) {
		byte[] hdr = new byte[8];
		try {
			if (!of.open()) return -1;
			if (of.read(hdr, 0, 8) <= 0) return -1;
			int load = getWORD(hdr, 2);
			int len = getWORD(hdr, 4);
			int entry = getWORD(hdr, 6);
			if (of.read(mem, load, len) <= 0) return -1;
			return entry;
		} finally {
			of.close(); // or left open?
		}
	}

	private int linkABS(File path) {
		HdosOpenFile of = new HdosVirtualFile(path, 042);
		return doABS(of);
	}

	private int loadABS(File path) {
		if (chans[0] != null && !chans[0].closed()) {
			// TODO: close channel...
			chans[0] = null;
		}
		chans[0] = new HdosVirtualFile(path, 042);
		return doABS(chans[0]);
	}

	// TODO: should '/' be included?
	private boolean delimiter(int c) {
		return (c == 0 || c == ' ' || c == ',' || c == '=');
	}

	// create a normalized HDOS file description
	// from filename and (optional) default block.
	// The device and type (extension) are only guaranteed
	// if def is provided.
	private int hdosDecodeName(int def, int fs, int dst) {
		int x;
		int c;
		int i = dst;	// ptr in dst
		int j = 0;	// ptr in fs
		mem[i++] = (byte)0x00;	// Start out with error
		if ((mem[fs + 3] & 0x7f) != ':') {
			if (def >= 0 && mem[def] != 0) {
				for (x = 0; x < 3; ++x) {
					c = mem[def + x] & 0x7f;
					mem[i++] = (byte)Character.toUpperCase((char)c);
				}
			} else {
				mem[i++] = 0; //'S';
				mem[i++] = 0; //'Y';
				mem[i++] = 0; //'0';
			}
		} else {
			for (j = 0; j < 3; ++j) {
				c = mem[fs + j] & 0x7f;
				mem[i++] = (byte)Character.toUpperCase((char)c);
			}
			++j;	// skip ':'
		}
		if (mem[dst + 1] == 0) {
			return EC_ICN;
		}
		int dx = hdosDrive(new String(mem, dst + 1, 3).toLowerCase());
		if (dx < 0) return EC_IDN;
		mem[i - 1] = (byte)(mem[i - 1] - '0');
		c = 0;
		for (x = 0; x < 8; ++x) {
			c = mem[fs + j++] & 0x7f;
			if (c == '.' || delimiter(c)) break;
			mem[i++] = (byte)Character.toUpperCase((char)c);
		}
		while (i < dst + 12) mem[i++] = 0;
		if (c == '.') {
			for (x = 0; x < 3; ++x) {
				c = mem[fs + j++] & 0x7f;
				if (delimiter(c)) break;
				mem[i++] = (byte)Character.toUpperCase((char)c);
			}
		} else if (def >= 0 && mem[def + 3] != 0) {
			for (x = 0; x < 3; ++x) {
				c = mem[def + 3 + x] & 0x7f;
				if (delimiter(c)) break;
				mem[i++] = (byte)Character.toUpperCase((char)c);
			}
		}
		while (i < dst + 15) {
			mem[i++] = 0;
		}
		// TODO: AIO.DTA...
		mem[dst] = (byte)0x01;	// TODO: AIO.FLG - valid device/file
		return EC_OK;
	}

	private void doCCP(String[] argv) {
		boolean ok = false;
		int entry = -1;
		// Special case: "submit" file is directly referenced.
		// TODO: make this more discriminating? */*? !pwd?
		if (argv.length < 1) {
			// silently ignore empty commands
			// (especially don't throw ArrayIndexOutOfBoundsException)
			return;
		}
		File f = new File(argv[0]);
		if (argv[0].indexOf('/') >= 0 && f.exists()) {
			ok = loadSUB(f, argv);
			// nothing to run, yet...
			running = false; // skip to next command
			return;
		}
		String cmd = ">";
		cmd += argv[0];
		for (int x = 1; x < argv.length; ++x) {
			cmd += ' ';
			cmd += argv[x];
		}
		System.out.format("%s\n", cmd);
		cmd = argv[0].toLowerCase();
		File path = mkFilePath(cmd);
		if (path == null) {
			System.out.format("%s?\n", cmd);
			running = false;
			return;
		}
		if (path.getName().endsWith(".sub")) {
			ok = loadSUB(path, argv);
			// nothing to run, yet...
			running = false; // skip to next command
			return;
		} else {
			entry = loadABS(path);
			ok = (entry >= 0);
		}
		if (!ok) {
			// TODO: take more-direct action?
			running = false;
			return;
		}
		cpu.setRegSP(stack);
		setPage0(argv);
		cpu.setRegPC(entry);
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
			//if (a == '\n') a = '\r';
		} catch (Exception ee) {}
		return a;
	}

	private void conout(int e) {
		System.out.append((char)e);
		System.out.flush();
	}

	private void doPRINT() {
		int hl = cpu.getRegHL();
		int c;
		do {
			c = mem[hl++] & 0xff;
			System.out.append((char)(c & 0x7f));
		} while ((c & 0x80) == 0);
		System.out.flush();
		cpu.setRegHL(hl);
	}

	private int getChannel() {
		int ch = (cpu.getRegA() + 1) & 0xff; // -1..5 typical
		if (ch >= chans.length) {
			cpu.setRegA(EC_ILC);
			cpu.setCarryFlag(true);
			return -1;
		}
		return ch;
	}

	private int getChannel(boolean empty) {
		int ch = getChannel();
		if (ch < 0) return -1;
		if (!empty && (chans[ch] == null || chans[ch].closed())) {
			cpu.setRegA(EC_FNO);
			cpu.setCarryFlag(true);
			return -1;
		}
		if (empty && chans[ch] != null && !chans[ch].closed()) {
			cpu.setRegA(EC_CNA);
			cpu.setCarryFlag(true);
			return -1;
		}
		return ch;
	}

	private void checkRW(int bc, int de, int n) {
		if (n > 0) {
			cpu.setRegDE(de + n);
			cpu.setRegBC(bc - n);
		}
		if (n != bc) {
			cpu.setCarryFlag(true);
			cpu.setRegA(EC_EOF);
		} else {
			cpu.setCarryFlag(false);
			cpu.setRegA(EC_OK);
		}
	}

	private boolean checkOpens(File path, int fnc) {
		int x;
		for (x = 0; x < chans.length; ++x) {
			if (chans[x] == null || chans[x].closed()) continue;
			if (!path.equals(chans[x].file)) continue;
			if (chans[x].mode != 042) return false;
			if (fnc != 042) return false;
		}
		return true;
	}

	// defaults to SY0: and .ABS
	private File mkFilePath(String fn) {
		int n = fn.length();
		int dx = 0;
		if (n > 4 && fn.charAt(3) == ':') {
			dx = hdosDrive(fn.substring(0, 3));
			if (dx < 0) return null;
			fn = fn.substring(4);
		}
		if (fn.indexOf('.') < 0) {
			fn += ".abs";
		}
		return new File(dirs[dx], fn);
	}

	private void hexDump(int adr, int len) {
		int x;
		while (len > 0) {
			System.err.format("%04x:", adr);
			for (x = 0; x < 16 && x < len; ++x) {
				System.err.format(" %02x", mem[adr + x] & 0xff);
			}
			System.err.format("\n");
			adr += 16;
			len -= 16;
		}
	}

	private File mkFilePath(int def, int fs) {
		int x;
		String fn = "";
		String dv = "";
		int dx;
		int c;
		if ((mem[fs + 3] & 0x7f) == ':') {
			for (x = 0; x < 3; ++x) {
				c = mem[fs + x] & 0x7f;
				dv += Character.toLowerCase((char)c);
			}
			fs += 4;
		} else {
			if (def >= 0 && mem[def] != 0) {
				for (x = 0; x < 3; ++x) {
					c = mem[def + x] & 0x7f;
					dv += Character.toLowerCase((char)c);
				}
			} else {
				dv = "sy0"; // TODO: correct?
			}
		}
		dx = hdosDrive(dv.toLowerCase());
		if (dx < 0) return null;
		boolean dot = false;
		for (x = 0; x < 12; ++x) {
			c = mem[fs + x] & 0x7f;
			if (delimiter(c)) break;
			if (c == '.') dot = true;
			fn += Character.toLowerCase((char)c);
		}
		if (!dot && mem[def + 3] != 0) {
			fn += '.';
			for (x = 0; x < 3; ++x) {
				c = mem[def + 3 + x] & 0x7f;
				fn += Character.toLowerCase((char)c);
			}
		}
		return new File(dirs[dx], fn);
	}

	private void doNAME() {
		int ch = getChannel();
		if (ch < 0) return;
		int de = cpu.getRegDE();
		int hl = cpu.getRegHL();
		String f = chans[ch].file.getAbsolutePath();
		int x = f.lastIndexOf('/');
		String d = f.substring(0, x);
		byte[] fn = f.substring(x + 1).getBytes();
		int y;
		for (x = 0; x < dirs.length; ++x) {
			if (d.equals(dirs[x])) {
				break;
			}
		}
		Arrays.fill(mem, de, de + 6, (byte)0);
		Arrays.fill(mem, hl, hl + 9, (byte)0);
		System.arraycopy(devs[x].getBytes(), 0, mem, de, 3);
		for (x = 0; x < 8 && fn[x] != '.'; ++x) {
			mem[hl + x] = (byte)Character.toUpperCase(fn[x]);
		}
		if (fn[x++] != '.') return;
		for (y = 0; y < 3 && x < fn.length; ++y) {
			mem[de + 3 + y] = (byte)Character.toUpperCase(fn[x++]);
		}
	}

	private void error(int ec) {
		cpu.setRegA(ec);
		cpu.setCarryFlag(ec != EC_OK);
	}

	private void doOPEN(int fnc) {
		int ch = getChannel(true);
		if (ch < 0) return;
		int de = cpu.getRegDE();
		int hl = cpu.getRegHL();
		File path = mkFilePath(de, hl);
		if (path == null) {
			error(EC_IFN);
			return;
		}
		if (!checkOpens(path, fnc)) {
			error(EC_FUC);
			return;
		}
		HdosOpenFile of;
		if (path.getName().equals("direct.sys")) {
			of = new HdosDirectoryFile(path.getParentFile(), fnc);
		} else {
			of = new HdosVirtualFile(path, fnc);
		}
		if (!of.open()) {
			error(EC_FUC);
			return;
		}
		chans[ch] = of;
		error(EC_OK);
	}

	private void doCLOSE() {
		int ch = getChannel(false);
		if (ch < 0) return;
		chans[ch].close();
		error(EC_OK);
	}

	private void doREAD() {
		int ch = getChannel(false);
		if (ch < 0) return;
		int bc = cpu.getRegBC();
		int de = cpu.getRegDE();
		int n = chans[ch].read(mem, de, bc);
		checkRW(bc, de, n);
	}

	private void doWRITE() {
		int ch = getChannel(false);
		if (ch < 0) return;
		int bc = cpu.getRegBC();
		int de = cpu.getRegDE();
		int n = chans[ch].write(mem, de, bc);
		checkRW(bc, de, n);
	}

	private void doPOSIT() {
		int ch = getChannel(false);
		if (ch < 0) return;
		int bc = cpu.getRegBC();
		int fl = chans[ch].length() / 256;
		if (bc > fl) {
			cpu.setRegBC(fl);
			error(EC_EOF);
			return;
		}
		chans[ch].seek(bc * 256);
		error(EC_OK);
	}

	private void doLINK() {
		int entry;
		int hl = cpu.getRegHL();
		File fn = mkFilePath(-1, hl);
		if (fn == null) {
			error(EC_IFN);
			return;
		}
		entry = linkABS(fn);
		if (entry < 0) {
			error(EC_RF);
			return;
		}
		// TODO: pop return address???
		cpu.setRegPC(entry);
		error(EC_OK);
	}

	private void doSETTOP() {
		int hl = cpu.getRegHL();
		int ec = EC_OK;
		// TODO: anything we care about?
		if (hl >= memtop) {
			cpu.setRegHL(memtop);
			//ec = EC_NEM; // no errors allowed?
		}
		error(ec);
	}

	private void doDELETE() {
		int hl = cpu.getRegHL();
		int de = cpu.getRegDE();
		File oldf = mkFilePath(de, hl);
		if (oldf.exists()) try {
			oldf.delete();
		} catch (Exception ee) {
			error(EC_FNF);
			return;
		}
		error(EC_OK);
	}

	private void doRENAME() {
		int hl = cpu.getRegHL();
		int de = cpu.getRegDE();
		int bc = cpu.getRegBC();
		File oldf = mkFilePath(de, hl);
		File newf = mkFilePath(de, bc);
		if (!oldf.exists()) {
			error(EC_FNF);
			return;
		}
		if (!oldf.getParent().equals(newf.getParent())) {
			error(EC_IDN);
			return;
		}
		try {
			oldf.renameTo(newf);
		} catch (Exception ee) {
			error(EC_WF);
			return;
		}
		error(EC_OK);
	}

	private void doDECODE() {
		int hl = cpu.getRegHL();
		int de = cpu.getRegDE();
		int bc = cpu.getRegBC();

		int ec = hdosDecodeName(de, hl, bc);
		error(ec);
	}

	private void doCLEAR() {
	}
	private void doCLEARA() {
	}
	private void doERROR() {
		int a = cpu.getRegA();
		int h = cpu.getRegH();
		System.out.format(" %02d%c", a, h);
	}

	private void hdosTrap(int pc) {
		if (pc != hdosv) {	// not possible?
			System.err.format("Invalid BDOS entry at %04x\n", pc);
			running = false;
			return;
		}
		int ret = doPOP();
		int fnc = mem[ret++] & 0xff;
		doPUSH(ret);
		switch (fnc) {
		case 0:	// .EXIT
			exitCode = cpu.getRegA();
			running = false;
			break;
		case 1:	// .SCIN
			if (constat() != 0) {
				cpu.setRegA(conin());
				cpu.setCarryFlag(false);
			} else {
				cpu.setCarryFlag(true);
			}
			break;
		case 2:	// .SCOUT
			conout(cpu.getRegA());
			break;
		case 3:	// .PRINT
			// print string HL to console, last byte +0x80
			doPRINT();
			break;
		case 4:	// .READ
			doREAD();
			break;
		case 5:	// .WRITE
			doWRITE();
			break;
		case 6:	// .CONSL - console options
			error(EC_OK);
			break;
		case 7:	// .CLRCO - clear console buffer
			error(EC_OK);
			break;
		case 8:	// .LOADO - load overlay
			// TODO: for now... no OS overlays required?
			error(EC_OK);
			break;
		case 9:	// .VERS - HDOS version
			cpu.setRegA(0x20);
			cpu.setCarryFlag(false);
			break;
		// TODO: non-resident functions...
		case 040:	// .LINK
			doLINK();
			break;
		case 041:	// .CTLC
			// TODO: need to support this?
			break;
		case 042:	// .OPENR - read only
		case 043:	// .OPENW - write only
		case 044:	// .OPENU - read/write
		case 045:	// .OPENC - write contiguous
			doOPEN(fnc);
			break;
		case 046:	// .CLOSE
			doCLOSE();
			break;
		case 047:	// .POSIT
			doPOSIT();
			break;
		case 050:	// .DELET
			doDELETE();
			break;
		case 051:	// .RENAM
			doRENAME();
			break;
		case 052:	// .SETTP
			doSETTOP();
			break;
		case 053:	// .DECODE
			doDECODE();
			break;
		case 054:	// .NAME - get name from channel
			doNAME();
			break;
		case 055:	// .CLEAR - clear channel
			doCLEAR();
			break;
		case 056:	// .CLEARA - clear all channels
			doCLEARA();
			break;
		case 057:	// .ERROR - lookup error
			doERROR();
			break;
		case 060:	// .CHFLG - change flags
			// no need? not supported
			error(EC_OK);
			break;
		case 061:	// .DISMT
			// no need? not supported
			error(EC_OK);
			break;
		case 062:	// .LOADD - load device driver
			// no need? not supported
			error(EC_OK);
			break;
		case 063:	// .OPEN - parameterized open
			error(EC_ILC);
			break;
		case 0200:	// .MOUNT - mount
			// no need? not supported
			error(EC_OK);
			break;
		case 0201:	// .DMOUN - dismount
			// no need? not supported
			error(EC_OK);
			break;
		case 0202:	// .MONMS - mount, no message
			// no need? not supported
			error(EC_OK);
			break;
		case 0203:	// .DMNMS - dismount, no message
			// no need? not supported
			error(EC_OK);
			break;
		case 0204:	// .RESET - dismount/remount
			// no need? not supported
			error(EC_OK);
			break;
		case 0205:	// .CLEAN - clean device
			// no need? not supported
			error(EC_OK);
			break;
		case 0206:	// .DAD - dismount all
			// no need? not supported
			error(EC_OK);
			break;
		default:
			System.err.format("Unknown HDOS function %d from %04x\n", fnc, ret);
			// TODO: crash...? return CY?
			break;
		}
		doRET();
	}

	private void coldStart() {
		// already done in ctor...
	}

	private void warmStart() {
		// TODO: close all? ???
		//setJMP(hdosv, hdose);
	}

	//////// Runnable /////////
	public void run() {
		long clock = 0;
		int clk = 0;
		boolean tracing = false;
		coldStart();
		while (cmds.size() > 0) {
			warmStart();
			running = true;
			String[] cmd = cmds.remove(0);
			doCCP(cmd); // parse command... setup execution...
			while (running) {
				int PC = cpu.getRegPC();
				// Doing this early allows triggering off OS calls
				if (trc != null) {
					tracing = trc.preTrace(PC, clock);
				}
				if (PC == hdosv) {
					hdosTrap(PC);
					if (!running) {
						break;
					}
					PC = cpu.getRegPC();
				}
				if (PC >= memtop || PC < 0x1800) {
					System.err.format("Crash %04x\n", PC);
					running = false;
					break;
				}
				clk = cpu.execute();
				if (tracing) {
					trc.postTrace(PC, clk, null);
				}
				clock += clk;
			}
		}
		if (coredump != null) dumpCore(coredump);
		stopped = true;
		stopWait.release();
		System.out.format("\n");
		// System.exit(exitCode);
	}

	public String dumpDebug() {
		String ret = "";
		return ret;
	}

	// Memory interface implemented
	public int read(boolean rom, int bank, int address) { // debugger interface
		return read(address);
	}
	public int read(int address) {
		return mem[address & 0xffff] & 0xff;
	}
	public void write(int address, int value) {
		// not allowed for debugging
	}
	// public void reset() { }
	public void dumpCore(String file) {
		try {
			FileOutputStream f = new FileOutputStream(file);
			f.write(mem);
			f.close();
			System.err.format("VCP/M core dumped to \"%s\"\n", file);
		} catch (Exception ee) {
			ee.printStackTrace();
		}
	}
	// public String dumpDebug() { return ""; }
}
