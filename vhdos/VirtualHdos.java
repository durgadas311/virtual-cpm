// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.io.*;
import java.awt.event.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.Properties;
import java.util.Calendar;

import z80core.*;
import z80debug.*;

public class VirtualHdos implements Computer, Memory,
		ActionListener, Runnable {
	static final int NCHAN = 8;	// HDOS supports -1, 0, .. 5

	static final int EC_OK = 0;	// no error
	static final int EC_EOF = 1;	// EOF
	static final int EC_EOM = 2;	// End Of Media
	static final int EC_ILC = 3;	// illegal syscall
	static final int EC_CNA = 4;	// channel not available
	static final int EC_DNS = 5;	// device not suitable
	static final int EC_IDN = 6;	// illegal device name
	static final int EC_IFN = 7;	// illegal file name
	static final int EC_NRD = 8;	// no room for device driver
	static final int EC_FNO = 9;	// channel not open
	static final int EC_ILR = 10;	// illegal request
	static final int EC_FUC = 11;	// file usage conflict
	static final int EC_FNF = 12;	// file not found
	static final int EC_UND = 13;	// unknown device
	static final int EC_ICN = 14;	// illegal channel number
	static final int EC_DIF = 15;	// directory full
	static final int EC_IFC = 16;	// illegal file contents
	static final int EC_NEM = 17;	// not enough memory
	static final int EC_RF = 18;	// read failure
	static final int EC_WF = 19;	// write failure
	static final int EC_WPV = 20;	// write prot violation
	static final int EC_WP = 21;	// disk write prot
	static final int EC_FAP = 22;	// file already present
	static final int EC_DDA = 23;	// device driver abort
	static final int EC_FL = 24;	// file locked
	static final int EC_FAO = 25;	// file already open
	static final int EC_IS = 26;	// illegal switch
	static final int EC_UUN = 27;	// unknown unit number
	static final int EC_FNR = 28;	// file name rquired
	static final int EC_DIW = 29;	// device not writeable
	static final int EC_UNA = 30;	// unit not available
	static final int EC_ILV = 31;	// illegal value
	static final int EC_ILO = 32;	// illegal option
	static final int EC_VPM = 33;	// volume presently mounted
	static final int EC_NVM = 34;	// no volume presently mounted
	static final int EC_FOD = 35;	// file open on device
	static final int EC_NPM = 36;	// no prov made for mounting
	static final int EC_DNI = 37;	// disk not initialized
	static final int EC_DNR = 38;	// disk not readable
	static final int EC_DSC = 39;	// disk structure corrupt
	static final int EC_NCV = 40;	// not correct version of HDOS
	static final int EC_NOS = 41;	// no OS mounted
	static final int EC_IOI = 42;	// illegal overlay index
	static final int EC_OTL = 43;	// overlay too large

	static final int DIF_SYS = 0x80;	// File.canExecute()
	static final int DIF_LOC = 0x40;	// not supported
	static final int DIF_WP = 0x20;		// File.canWrite()

	static final String[] months = { "Jan", "Feb", "Mar", "Apr", "May",
		"Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };

	static final String[] devs = {
		"sy0", "sy1", "sy2", "sy3", "sy4", "sy5", "sy6", "sy7",
		"dk0", "dk1", "dk2", "dk3", "dk4", "dk5", "dk6", "dk7",
		"???"
	};

	private CPU cpu;
	private CPUTracer trc;
	private long clock;
	private byte[] mem;
	private boolean running;
	private Vector<String[]> cmds;
	private Vector<Integer> paths;
	private Map<Integer, String> errv;
	private int[] ctlc;
	private javax.swing.Timer timer;

	private HdosOpenFile[] chans;
	private String[] dirs;
	private String root;
	private java.util.concurrent.LinkedBlockingDeque<Integer> fifo;
	private ConsoleInput console;

	static final int s_date = 0x20bf;	// (9) "DD-MMM-YY"
	static final int s_datc = 0x20c8;	// (2) coded date
	static final int s_time = 0x20ca;	// (3+1) BCD HH:MM:SS (??)
	static final int s_clktr = 0x20cd;	// (1) byte S.CLKTR
	static final int s_himem = 0x20ce;	// (2) h/w hi-mem +1 (0xffff)
	static final int s_sysm = 0x20d0;	// (2) FWA res sys (hdose)
	static final int s_usrm = 0x20d2;	// (2) LWA usr (follows pgm)
	static final int s_omax = 0x20d4;	// (2) max ovr size
	static final int s_scr = 0x2151;	// (2) addr of scratch buffer
	static final int s_dfwa = 0x20ec;	// (2) addr S.DFWA - device table
	static final int s_rfwa = 0x20ee;	// (2) addr S.RFWA - res HDOS

	static final int s_label = 0x0003;	// (2) addr S.LABEL
	static final int s_fmask = 0x0005;	// (2) byte S.FMASK
	static final int s_lwa = 0x0006;	// (2) addr S.LWA
	static final int batbuf = 0x0054;	// (2) addr S.PATH
	static final int batptr = 0x0056;	// (2) addr S.PATH
	static final int subbuf = 0x0058;	// (2) addr S.PATH
	static final int s_path = 0x005a;	// (2) addr S.PATH
	static final int s_prmt = 0x005c;	// (2) addr S.PRMT
	static final int s_edlin = 0x005e;	// (2) addr S.EDLIN
	static final int cslibuf = 0x003e;	// (2) addr CSLIBUF (not used?)
	static final int eiret = 0x1ff9;	//

	static final int hdosv = 0x0038; // the ONLY entry?
	static final int stack = 0x2280;
	static final int tpa = 0x2280;
	static final int memtop = 0xff00; // or ????
	static final int scrbuf = memtop - 512; // or ????
	static final int hdose = scrbuf - 256; // or ????
	static final int tttbl = hdose + 16;
	static final int sytbl = tttbl + 14;
	static final int dktbl = sytbl + 14;

	private int intvec = memtop + 16;
	private int sysvec = intvec + 16;

	private boolean nosys;
	private boolean y2k;
	private boolean syscmd;

	private static VirtualHdos vhdos;
	private static String coredump = null;
	private int exitCode = 0; // TODO: System.exit(exitCode)
	private int vers = 0x20;
	private int cpuType = 0x80;	// Z80 by default
	private boolean done = false;
	private boolean debugIRQ = false;

	static String home;
	static String cwd;
	byte[] secBuf;

	public static void main(String[] argv) {
		Properties props = new Properties();
		home = System.getProperty("user.home");
		cwd = System.getProperty("user.dir");
		File f = new File("./vhdos.rc");
		if (!f.exists()) {
			f = new File(home + "/.vhdosrc");
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
		String s = System.getenv("VHDOSVersion");
		if (s != null) {
			props.setProperty("vhdos_vers", s);
		}
		s = System.getenv("HDOSDrives");
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
		for (int x = 0; x < devs.length - 1; ++x) {
			String v = String.format("HDOSDrive_%s", devs[x]);
			s = System.getenv(v);
			if (s == null || s.length() == 0) {
				continue;
			}
			String p = String.format("vhdos_drive_%s", devs[x]);
			props.setProperty(p, s.trim());
		}
		s = System.getenv("VHDOSPath");
		if (s != null) {
			props.setProperty("vhdos_path", s);
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
		cmds = new Vector<String[]>();
		paths = new Vector<Integer>();
		dirs = new String[devs.length - 1];
		chans = new HdosOpenFile[NCHAN];
		mem = new byte[65536];
		secBuf = new byte[512]; // at least 512...
		ctlc = new int[3];
		boolean silent = (props.getProperty("silent") != null);
		String t = props.getProperty("vhdos_trace");
		s = props.getProperty("vhdos_cpu");
		if (s != null) {
			if (s.matches("[iI]?8080")) {
				cpuType = 0x00;
				cpu = new I8080(this);
				if (t != null) {
					trc = new I8080Tracer(props, "vhdos", cpu, this, t);
				}
			} else if (s.matches("[iI]?8085")) {
				cpuType = 0x40;
				cpu = new I8085(this);
				if (t != null) {
					trc = new I8085Tracer(props, "vhdos", cpu, this, t);
				}
			} else if (s.matches("[zZ]80")) {
				cpuType = 0x80;
				cpu = new Z80(this);
				if (t != null) {
					trc = new Z80Tracer(props, "vhdos", cpu, this, t);
				}
			} else if (s.matches("[zZ]180")) {
				cpuType = 0xc0;
				Z180 z180 = new Z180(this, null, true); // Z80S180
				cpu = z180;
				if (t != null) {
					trc = new Z180Tracer(props, "vhdos", cpu, this, t);
				}
			}
		}
		if (cpu == null) {
			cpuType = 0x80;
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
		nosys = (props.getProperty("vhdos_nosys") != null);
		y2k = (props.getProperty("vhdos_y2k") != null);
		s = props.getProperty("vhdos_vers");
		if (s != null) {
			vers = Integer.decode(s) & 0xff;
		}
		for (x = 0; x < dirs.length; ++x) {
			s = String.format("vhdos_drive_%s", devs[x]);
			s = props.getProperty(s);
			if (s != null) {
				if (s.startsWith("${PWD}")) {
					s = s.replaceFirst("\\$\\{PWD\\}", cwd);
				} else if (s.startsWith("${HOME}")) {
					s = s.replaceFirst("\\$\\{HOME\\}", home);
				}
				File f = new File(s);
				// TODO: mkdir?
				// if (!f.exists()) {
				// 	try { f.mkdirs(); } catch (Exception ee) {}
				// }
				if (!f.exists() || !f.isDirectory()) {
					System.err.format("Invalid path in %s: %s\n",
						devs[x], s);
					continue;
				}
				dirs[x] = s;
			}
		}
		s = props.getProperty("vhdos_root_dir");
		if (s == null) {
			s = home + "/HostFileHdos";
		}
		root = s;
		for (x = 0; x < dirs.length; ++x) {
			if (dirs[x] != null) continue;
			dirs[x] = String.format("%s/%s", root, devs[x]);
			File f = new File(dirs[x]);
			if (!f.exists()) {
				try { f.mkdirs(); } catch (Exception ee) {}
			}
		}
		s = System.getenv("VHDOSShow");
		if (s != null && s.length() == 3) {
			int dx = hdosDrive(s);
			if (dx >= 0) {
				System.out.println(dirs[dx]);
			}
			System.exit(0);
		}
		s = props.getProperty("vhdos_path");
		if (s != null) {
			String[] ss = s.split("[ :;,]");
			for (String e : ss) {
				int n = e.length();
				if (n > 3 || n < 2) {
					System.err.format("Skipping bad path \"%\"\n", e);
					continue;
				}
				if (n == 2) e += '0';
				n = hdosDrive(e.toLowerCase());
				if (n < 0) {
					System.err.format("Skipping unknown path \"%\"\n", e);
					continue;
				}
				paths.add(n);
			}
		} else {
			paths.add(0);
		}

		// start setting up memory
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
		setJMP(0x201f, intvec + 1);
		setJMP(0x2022, intvec + 2);
		setJMP(0x2025, intvec + 3);
		setJMP(0x2028, intvec + 4);
		setJMP(0x202b, intvec + 5);
		setJMP(0x202e, intvec + 6);
		setJMP(0x2031, intvec + 7);
		//
		setJMP(0x0008, 0x201f);
		setJMP(0x0010, 0x2022);
		setJMP(0x0018, 0x2025);
		setJMP(0x0020, 0x2028);
		setJMP(0x0028, 0x202b);
		setJMP(0x0030, 0x202e);
		setJMP(0x0038, 0x2031);
		// ... not sure who is responsible for these...
		setJMP(0x2108, sysvec + 0);	// S.SDD
		setJMP(0x210b, sysvec + 1);	// S.FASER
		setJMP(0x210e, sysvec + 2);	// S.DIREAD
		setJMP(0x2111, sysvec + 3);	// S.FCI
		setJMP(0x2114, sysvec + 4);	// S.SCI
		setJMP(0x2117, sysvec + 5);	// S.GUP

		setWORD(s_himem, 0xffff);
		setWORD(s_sysm, hdose);
		setWORD(s_omax, 0);
		setWORD(s_scr, scrbuf);
		setWORD(s_dfwa, tttbl);	// TT0: is first device in table
		setWORD(s_rfwa, hdose);

		int usd = dktbl + 14 + 1;	// consumes 24 bytes for 3 devs
		setupDevTbl(tttbl, -1, 0, usd);		// TT0 table
		usd += 8;
		setupDevTbl(sytbl, 0, memtop, usd);	// use memtop for fake GRT
		usd += 8;
		setupDevTbl(dktbl, 8, memtop, usd);	// ('')

		if (vers >= 0x30) {
			setJMP(0, 0);
			setupTOD();
			usd = 0x1c00; // TODO: overwrites H17 ROM... confirm
			setWORD(s_label, usd);
			usd += 256;
			setWORD(batbuf, usd);
			setWORD(batptr, usd);
			usd += 256;
			setWORD(subbuf, usd);
			usd += 101;
			setWORD(s_path, usd);
			setupPATH(usd);
			usd += 101;
			setWORD(s_prmt, usd);
			mem[usd] = (byte)0;
			usd += 101;
			setWORD(s_edlin, usd);
			usd += 101;
			setWORD(cslibuf, usd); // never used?
			// usd += 101; // nothing follows
			// setWORD(s_lwa, ?); // never used?
			mem[s_fmask] = (byte)(cpuType | 0x00); // not H19 - no ESC codes
		}
		mem[eiret] = (byte)0xfb;
		mem[eiret + 1] = (byte)0xc9;
		fifo = new java.util.concurrent.LinkedBlockingDeque<Integer>();
		console = new ConsoleInput();
		cmds.add(argv);
	}

	private void setupPATH(int buf) {
		String s = "";
		for (int n : paths) {
			s += devs[n] + ':';
		}
		setSTRING(buf, s.toUpperCase());
	}

	private void setupTOD() {
		Calendar cal = Calendar.getInstance();
		int yr = cal.get(Calendar.YEAR);
		int mo = cal.get(Calendar.MONTH);
		int da = cal.get(Calendar.DAY_OF_MONTH);
		if (!y2k && yr > 1999) yr = 1999;
		int dt = da | ((mo + 1) << 5) | ((yr - 1970) << 9);
		setWORD(s_datc, dt);
		String dts = String.format("%02d-%s-%02d", da, months[mo], yr % 100);
		System.arraycopy(dts.getBytes(), 0, mem, s_date, 9);
		int hr = cal.get(Calendar.HOUR_OF_DAY);
		int mi = cal.get(Calendar.MINUTE);
		int se = cal.get(Calendar.SECOND);
		hr = ((hr / 10) << 4) | (hr % 10);
		mi = ((mi / 10) << 4) | (mi % 10);
		se = ((se / 10) << 4) | (se % 10);
		mem[s_time] = (byte)hr;
		mem[s_time + 1] = (byte)mi;
		mem[s_time + 2] = (byte)se;
		mem[s_time + 3] = (byte)0;
		timer = new javax.swing.Timer(1000, this);
		timer.start();
		mem[s_clktr] = (byte)0x01;
	}

	// return carry in 0x100 bit
	private int bcdIncr60(int n) {
		n += 1;
		if ((n & 0x0f) > 0x09) n += 0x06;
		if ((n & 0xf0) > 0x50) n += 0xa0;
		return n;
	}

	// return carry in 0x100 bit
	private int bcdIncr24(int n) {
		n += 1;
		if ((n & 0x0f) > 0x09) n += 0x06;
		if (n > 0x23) n += 0xdd;
		return n;
	}

	// add 1 second...
	private void updateTOD() {
		int n = bcdIncr60(mem[s_time + 2] & 0xff);
		mem[s_time + 2] = (byte)n;
		if (n < 0x100) return;
		n = bcdIncr60(mem[s_time + 1] & 0xff);
		mem[s_time + 1] = (byte)n;
		if (n < 0x100) return;
		n = bcdIncr24(mem[s_time + 0] & 0xff);
		mem[s_time + 0] = (byte)n;
		if (n < 0x100) return;
		// TODO: overflow into date...
	}

	public void reset() {
		boolean wasRunning = running;
		clock = 0;
		cpu.reset();
		if (wasRunning) {
			start();
		}
	}

	// These must NOT be called from the thread...
	public void start() {
		if (running) {
			return;
		}
		running = true;
		Thread t = new Thread(this);
		t.setPriority(Thread.MAX_PRIORITY);
		t.start();
	}
	// Not used.
	public void stop() {
		running = false;
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
		// The only INT here is for console input, use "3"
		int irq = 3;
		int opCode = 0xc7 | (irq << 3);
		cpu.setINTLine(false);
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

	// copy NUL-terminated string
	private void setSTRING(int adr, String str) {
		int x = str.length();
		System.arraycopy(str.getBytes(), 0, mem, adr, x);
		mem[adr + x] = (byte)0;

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
		// push cmd tail onto stack...
		// this is NOT terminated by NUL.
		int sp = cpu.getRegSP();
		String s = "";
		for (int x = 1; x < argv.length; ++x) {
			s += ' ';
			s += argv[x].toUpperCase();
		}
		if (s.length() > 0) {
			sp -= s.length();
			System.arraycopy(s.getBytes(), 0, mem, sp, s.length());
		}
		cpu.setRegSP(sp);
	}

	// TODO: convert "sy[0-7]:" or "dk[0-7]:" prefix to drive number
	private int hdosDrive(String s) {
		if (!s.matches("sy[0-7].*") && !s.matches("dk[0-7].*")) {
			return -1;
		}
		int d = s.charAt(2) - '0';
		if (s.charAt(0) == 'd') d += 8;
		return d;
	}

	private void setupPattern(byte[] pat, String s) {
		byte[] fn = s.getBytes();
		int x = 0;
		int y = 0;
		char c;
		char b = '\0';
		while (x < s.length() && s.charAt(x) != '.' && y < 8) {
			c = s.charAt(x++);
			if (c == '*') {
				b = '?';
				break;
			}
			pat[y++] = (byte)Character.toUpperCase(c);
		}
		while (y < 8) {
			pat[y++] = (byte)b;
		}
		if (x < s.length() && s.charAt(x) != '.') {
			// TODO: invalid
			return;
		}
		++x;
		b = '\0';
		while (x < s.length() && y < 11) {
			c = s.charAt(x++);
			if (c == '*') {
				b = '?';
				break;
			}
			pat[y++] = (byte)Character.toUpperCase(c);
		}
		while (y < 11) {
			pat[y++] = (byte)b;
		}
		if (x < s.length()) {
			// TODO: invalid
			return;
		}
	}

	private boolean compare(byte[] buf, int off, byte[] pat) {
		int x;
		for (x = 0; x < 11; ++x) {
			if (pat[x] != '?' && pat[x] != buf[off + x]) {
				return false;
			}
		}
		return true;
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
		try {
			if (!of.open()) return -EC_FNF;
			if (of.read(secBuf, 0, 256) <= 0) return -EC_RF;
			// TODO: verify 0xff, 0x00?
			int load = getWORD(secBuf, 2);
			int len = getWORD(secBuf, 4);
			int entry = getWORD(secBuf, 6);
			System.arraycopy(secBuf, 8, mem, load, 256 - 8);
			int lnext = load + 256 - 8;
			int nnext = ((len + 8 + 255) & ~255) - 256;
			if (of.read(mem, lnext , nnext) < 0) return -EC_RF;
			if (getWORD(s_usrm) < lnext + nnext) {
				setWORD(s_usrm, lnext + nnext);
			}
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
			chans[0].close();
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
			return EC_UND;
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
		setWORD(dst + 15, 0); // ???
		setWORD(dst + 17, dx < 8 ? sytbl : dktbl); // (+9) passed to S.GUP
		return EC_OK;
	}

	private String getDate(byte[] buf, int idx) {
		int dt = getWORD(buf, idx);
		if (dt == 0) return "";
		int da = dt & 0x001f;
		int mo = ((dt & 0x01e0) >> 5) - 1;
		int yr = ((dt & 0xfe00) >> 9) + 1970;
		return String.format("%02d-%s-%04d", da, months[mo], yr);
	}

	private void doTYPE(String[] argv) {
		if (argv.length < 2) {
			return;
		}
		File tf = mkFilePath(argv[1]);
		HdosOpenFile of = new HdosVirtualFile(tf, 042);
		if (!of.open()) {
			System.out.format("%s?\n", argv[1]);
			return;
		}
		while (of.read(secBuf, 0, 256) > 0) {
			// TODO: '\0' is EOF or just gobbled?
			try { System.out.write(256); } catch (Exception ee) {}
		}
		of.close();
		System.out.format("\n");
	}

	private void doDIR(String[] argv) {
		// TODO: file matching...
		int x;
		int dx = -1;
		byte[] pat = new byte[11];
		Arrays.fill(pat, (byte)'?');
		if (argv.length == 1) {
			dx = 0;
		} else if (argv[1].length() >= 4 &&
				argv[1].charAt(3) == ':') {
			dx = hdosDrive(argv[1]);
			if (argv[1].length() > 4) {
				setupPattern(pat, argv[1].substring(4));
			}
		}
		if (dx < 0) {
			System.out.format("?\n");
			return;
		}
		File dir = new File(dirs[dx]);
		HdosDirectoryFile of = new HdosDirectoryFile(dir, 042, nosys, y2k);
		if (!of.open()) return;
		String fn, fx;
		System.out.format("NAME    .EXT   SIZE     DATE     FLAGS\n\n");
		while (of.read(secBuf, 0, 512) > 0) {
			for (x = 0; x < 0x1fa; x += 23) {
				if ((secBuf[x] & 0xfe) == 0xfe) continue;
				if (!compare(secBuf, x, pat)) continue;
				fn = new String(secBuf, x, 8).replaceAll("\000", " ");
				fx = new String(secBuf, x + 8, 3).replaceAll("\000", " ");
				dx = getWORD(secBuf, x + 16);
				System.out.format(
					"%s.%s   %4d  %11s  %c %c\n",
					fn, fx, dx,
					getDate(secBuf, x + 19),
					(secBuf[x + 14] & DIF_SYS) != 0 ? 'S' : ' ',
					(secBuf[x + 14] & DIF_WP) != 0 ? 'W' : ' ');
			}
		}
		of.close();
	}

	private boolean ccpBuiltin(String cmd, String[] argv) {
		if (cmd.equals("dir")) {
			doDIR(argv);
		} else if (cmd.equals("type")) {
			doTYPE(argv);
		} else {
			return false;
		}
		return true;
	}

	private void doCCP(String[] argv) {
		boolean ok = false;
		int entry = -1;
		done = false;
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
		if (ccpBuiltin(cmd, argv)) {
			running = false;
			return;
		}
		File path = searchFilePath(cmd);
		if (path == null) {
			System.out.format("%s?\n", cmd);
			running = false;
			return;
		}
		syscmd = path.getName().equals("syscmd.sys");
		if (path.getName().endsWith(".sub")) {
			ok = loadSUB(path, argv);
			// nothing to run, yet...
			running = false; // skip to next command
			return;
		} else {
			setWORD(s_usrm, tpa);
			entry = loadABS(path);
			ok = (entry >= 0);
		}
		if (!ok) {
			// TODO: take more-direct action?
			//System.out.format("%s?? %d\n", path.getAbsolutePath(), entry);
			running = false;
			return;
		}
		cpu.setRegSP(stack);
		setPage0(argv);
		setWORD(0x211a, 1);	// S.MOUNT - is HDOS mounted?
		cpu.setRegA(0);	// syscmd.sys needs this
		doPUSH(entry);
		cpu.setRegPC(eiret);
	}

	private boolean constat() {
		return fifo.size() > 0;
	}

	private int conin() {
		int a = 0;
		if (fifo.size() > 0) try {
			a = fifo.take();
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
		if (n <= 0) {
			error(EC_EOF);
		} else {
			error(EC_OK);
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

	private File searchFilePath(String fn) {
		File f;
		if (fn.length() > 4 && fn.charAt(3) == ':') {
			return mkFilePath(fn);
		}
		if (fn.indexOf('.') < 0) {
			fn += ".abs";
		}
		for (int dx : paths) {
			f = new File(dirs[dx], fn);
			if (f.exists()) return f;
		}
		return null;
	}

	// defaults to SY0: and .ABS
	private File mkFilePath(String fn) {
		int n = fn.length();
		int dx = 0;
		if (n > 4 && fn.charAt(3) == ':') {
			dx = hdosDrive(fn);
			if (dx < 0) return null;
			fn = fn.substring(4);
		}
		if (fn.indexOf('.') < 0) {
			fn += ".abs";
		}
		return new File(dirs[dx], fn);
	}

	private void hexDump(String tag, int adr, int len) {
		hexDump(mem, tag, adr, len);
	}

	private void hexDump(int adr, int len) {
		hexDump(mem, null, adr, len);
	}

	static public void hexDump(byte[] buf, String tag, int adr, int len) {
		int x;
		while (len > 0) {
			if (tag != null) {
				System.err.format("%s %04x:", tag, adr);
			} else {
				System.err.format("%04x:", adr);
			}
			for (x = 0; x < 16 && x < len; ++x) {
				System.err.format(" %02x", buf[adr + x] & 0xff);
			}
			System.err.format("  ");
			for (x = 0; x < 16 && x < len; ++x) {
				char c = (char)(buf[adr + x] & 0xff);
				if (c < ' ' || c > '~') c = '.';
				System.err.format("%c", c);
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

	// dx < 0 for TT0 table (grt=0)
	private void setupDevTbl(int adr, int dx, int grt, int usd) {
		int m = 0x07;
		int n = 8;
		if (dx < 0) {
			mem[adr + 0] = (byte)'T';
			mem[adr + 1] = (byte)'T';
			m = 0x01;
			n = 1;
		} else {
			mem[adr + 0] = (byte)devs[dx].charAt(0);
			mem[adr + 1] = (byte)devs[dx].charAt(1);
		}
		mem[adr + 2] = (byte)0x03; // residence code
		setJMP(adr + 3, hdose);	// JMP to driver processing rtn?
		mem[adr + 6] = (byte)0x0f; // dir, r/w, rnd
		mem[adr + 7] = (byte)m; // unit mask...???
		mem[adr + 8] = (byte)n; // max units ???
		setWORD(adr + 9, usd); // unit-specific data
		setWORD(adr + 11, 0); // driver length
		mem[adr + 13] = (byte)0; // driver grp adr (on disk?)
		mem[adr + 14] = (byte)0; // in case this is last
		//
		if (dx < 0) {
			mem[usd + 0] = (byte)0x06;	// r/w
		} else {
			mem[usd + 0] = (byte)0x0f;	// unit-specific flag
			mem[usd + 1] = (byte)2;	// sec/grp (what value?)
			setWORD(usd + 2, grt);	// GRT (if dir)
			setWORD(usd + 4, 0);	// GRT sector number
			setWORD(usd + 6, 0);	// DIR first sector number
		}
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
		System.arraycopy(devs[x].toUpperCase().getBytes(), 0, mem, de, 3);
		for (x = 0; x < 8 && x < fn.length && fn[x] != '.'; ++x) {
			mem[hl + x] = (byte)Character.toUpperCase(fn[x]);
		}
		if (x >= fn.length || fn[x++] != '.') return; // error? not possible?
		for (y = 0; y < 3 && x < fn.length; ++y) {
			mem[de + 3 + y] = (byte)Character.toUpperCase(fn[x++]);
		}
	}

	private void error(int ec) {
		cpu.setRegA(ec);
		cpu.setCarryFlag(ec != EC_OK);
	}

	private void doCHFLG() {
		int ec = EC_OK;
		int b = cpu.getRegB();
		int c = cpu.getRegC();
		int de = cpu.getRegDE();
		int hl = cpu.getRegHL();
		File path = mkFilePath(de, hl);
		if (path == null) {
			error(EC_IFN);
			return;
		}
		if (!path.exists()) {
			error(EC_FNF);
			return;
		}
		int cur = 0;
		if (!path.canWrite()) cur |= DIF_WP;
		if (!nosys && path.canExecute()) cur |= DIF_SYS;
		int nuw = (cur & ~c) | b;
		int dif = nuw ^ cur;
		if (!nosys && (dif & DIF_SYS) != 0 &&
			!path.setExecutable((nuw & DIF_SYS) != 0)) {
			ec = EC_DSC;
		}
		if ((dif & DIF_WP) != 0 && !path.setWritable((nuw & DIF_WP) == 0)) {
			ec = EC_DSC;
		}
		error(ec);
	}

	private void doOPEN(int fnc) {
		int ch = getChannel(true);
		if (ch < 0) return;
		int de = cpu.getRegDE();
		int hl = cpu.getRegHL();
		// TODO: need better way...
		if (mem[hl + 3] == ':' && mem[hl] == 'T' && mem[hl + 1 ] == 'T') {
			chans[ch] = new HdosTtyFile(new File("tty"), fnc);
			error(EC_OK);
			return;
		}
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
			of = new HdosDirectoryFile(path.getParentFile(), fnc, nosys, y2k);
		} else {
			of = new HdosVirtualFile(path, fnc);
		}
		if (!of.open()) {
			error(EC_FNF);
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
		if (bc == 0) { // HDOS programs seem fond of doing this...
			error(EC_OK);
			return;
		}
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

	// Contrary to HDOS documentation, DE is default block.
	// Note, DE is ignored/don't-care if HL contains full dev:name.typ.
	private void doLINK() {
		int entry;
		int hl = cpu.getRegHL();
		int de = cpu.getRegDE();
		File fn = mkFilePath(de, hl);
		if (fn == null) {
			error(EC_IFN);
			return;
		}
		entry = linkABS(fn);
		if (entry < 0) {
			error(-entry);
			return;
		}
		doPOP();
		doPUSH(entry);	// doRET() will be called...
		error(EC_OK);
	}

	private void doSETTOP() {
		int hl = cpu.getRegHL();
		int ec = EC_OK;
		int top = getWORD(s_sysm);
		if (hl >= top) {
			cpu.setRegHL(top);
			//ec = EC_NEM; // no errors allowed?
		} else {
			setWORD(s_usrm, hl);
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
		int ch = getChannel();
		if (ch < 0) return;
		if (chans[ch] != null) chans[ch].close();
		error(EC_OK);
	}
	private void doCLEARA() {
		int x;
		for (x = 0; x < chans.length; ++x) {
			if (chans[x] != null) chans[x].close();
		}
		error(EC_OK);
	}
	private void doERROR() {
		int a = cpu.getRegA();
		int h = cpu.getRegH();
		if (loadERRORs() && errv.containsKey(a)) {
			System.out.format(" %s%c", errv.get(a), h);
		} else {
			System.out.format(" Error-%02d%c", a, h);
		}
	}

	private void doGDA() {
		int de = cpu.getRegDE();
		if (de == 0x5454) {	// 'TT'
			cpu.setRegBC(tttbl);
		} else if (de == 0x5953) {	// 'SY'
			cpu.setRegBC(sytbl);
		} else if (de == 0x4b44) {	// 'DK'
			cpu.setRegBC(dktbl);
		} else {
			cpu.setRegHL(0);
			cpu.setRegBC(0);
			error(EC_UND);
			return;
		}
		cpu.setRegHL(hdose); // yes?
		error(EC_OK);
	}

	private void doCTLC() {
		int a = cpu.getRegA();
		int hl = cpu.getRegHL();
		if (a < 1 || a > 3) return;
		ctlc[a - 1] = hl;
	}

	private int crc16(int crc, int val) {
		int x;
		int v = val;
		int c = crc;

		for (x = 0; x < 8; ++x) {
			v <<= 1;	// bit in 0x100
			c <<= 1;	// bit in 0x10000
			if ((((c >> 8) ^ v) & 0x100) != 0) {
				c ^= 0x8005;
			}
		}
		return c & 0xffff;
	}

	private void doCRC16() {
		int hl = cpu.getRegHL(); // buffer
		int de = cpu.getRegDE(); // CRC16 in
		int bc = cpu.getRegBC(); // count

		while (bc > 0) {
			de = crc16(de, mem[hl] & 0xff);
			++hl;
			--bc;
		}
		cpu.setRegHL(hl);
		cpu.setRegDE(de);
		cpu.setRegBC(0);
		error(EC_OK);
	}

	private void doS_FASER() {
		System.out.format("\n?02 Fatal system error!!\n");
		running = false;
	}

	private void doS_GUP() {
		int hl = cpu.getRegHL(); // buffer
		// all units use the same - ignore A
		hl = getWORD(hl);
		cpu.setRegHL(hl);
		error(EC_OK); // needed?
	}

	private void doDELAY() {
		int a = cpu.getRegA();
		// TODO: any reason to actually delay?
		// try { System.sleep(a * 2); } catch (Exception ee) {}
	}

	private void doEXIT() {
		exitCode = cpu.getRegA();
		if (syscmd) {
			if (done && vers < 0x30) {
				// This is not how 3.0 shuts down...
				System.out.format("SYSTEM SHUTDOWN\n");
				running = false;
				return;
			}
			// is re-load necessary?
			File sc = new File(dirs[0], "syscmd.sys");
			int entry = loadABS(sc);
			if (entry < 0) {
				System.err.format("No %s\n", sc.getAbsolutePath());
				running = false;
				return;
			}
			cpu.setRegSP(stack);
			cpu.setRegA(0);	// syscmd.sys needs this?
			doPUSH(entry);	// doRET() will be called...
		} else {
			running = false;
		}
	}

	private boolean loadERRORs() {
		if (errv != null) return true;
		errv = new HashMap<Integer, String>();
		try {
			InputStream fi = this.getClass().getResourceAsStream("errormsg.sys");
			BufferedReader lin = new BufferedReader(new InputStreamReader(fi));
			String s;
			while ((s = lin.readLine()) != null) {
				int ec = Integer.valueOf(s.substring(0, 3));
				errv.put(ec, s.substring(3));
			}
			lin.close();
			return true;
		} catch (Exception ee) {
			//ee.printStackTrace();
			return false;
		}
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
			doEXIT();
			break;
		case 1:	// .SCIN
			if (constat()) {
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
			cpu.setRegA(vers);
			cpu.setCarryFlag(false);
			break;
		case 10: // .GDA (3.0)
			if (vers >= 0x30) {
				doGDA();
			} else {
				error(EC_ILC);
			}
			break;
		case 11: // .CRC16 (3.0)
			if (vers >= 0x30) {
				doCRC16();
			} else {
				error(EC_ILC);
			}
			break;
		// TODO: non-resident functions...
		case 040:	// .LINK
			doLINK();
			break;
		case 041:	// .CTLC
			doCTLC();
			error(EC_OK);	// errors always ignored?
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
			doCHFLG();
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
		case 0101:	// .TASK (3.0)
			if (vers >= 0x30) {
				// TODO: what is this function?
				// B=TAS.DEA
				error(EC_OK); // nothing checked anyway...
			} else {
				error(EC_ILC);
			}
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
		case 0205:	// .CLEAN (2.0) - clean device
				// .RESNMS (3.0)
			// no need? not supported
			error(EC_OK);
			break;
		case 0206:	// .DAD - dismount all
			// no need? not supported
			done = true;
			error(EC_OK);
			break;
		default:
			System.err.format("Unknown HDOS function %d from %04x\n", fnc, ret);
			// TODO: crash...? return CY?
			error(EC_ILC);
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

	// For now, only if Ctrl-C pressed
	private void doIRQ3() {
		if (ctlc[2] == 0) return;
		doPUSH(0xffff); // need some token for return to ourself
		doPUSH(ctlc[2]);
		doPUSH(eiret);
		// doRET() is next
	}

	// 1 = crash w/message, -1 = crash w/o msg, 0 = was handled
	// If not fatal, returning causes doRET() (do not set PC directly).
	private int checkTrap(int pc) {
		if (pc == 0x0000 && vers >= 0x30 && syscmd &&
				(mem[0] & 0xff) == 0xc3 &&
				(pc = getWORD(1)) != 0) {
			doPUSH(pc);	// for doRET()
			return 0;
		}
		if (pc == 0xffff) { // return to IRQ3
			fifo.add(0x03);	// send Ctrl-C
			return 0;
		}
		if ((pc & ~0x0038) == 0) {
			int irq = pc >> 3;
			if (irq == 3) {
				doIRQ3();
				return 0;
			} else {
				System.err.format("Crash RST%d\n", irq);
				return -1;
			}
		}
		if (pc == 0x002b) { // delay A * 2 mS
			doDELAY();
			return 0;
		}
		int vec = pc - sysvec;
		if (vec >= 0 && vec < 6) {
			String s = null;
			switch (vec) {
			case 0:	s = "S.SDD"; break;
			case 1:	doS_FASER(); break; // Fatal System Error (halt)
			case 2:	s = "S.DIREAD"; break;
			case 3:	s = "S.FCI"; break;
			case 4:	s = "S.SCI"; break;
			case 5:	doS_GUP(); break;
			}
			if (s != null) System.err.format("Crash %s\n", s);
			return s == null ? 0 : -1;
		}
		return 1;
	}

	private String traceExtra() {
		if (debugIRQ) {
			return String.format("%c%s",
				cpu.isIE() ? '*' : ' ',
				cpu.isINTLine() ? "INT" : "");
		} else {
			return null;
		}
	}

	//////// Runnable /////////
	public void run() {
		String xtra = null;
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
					if (tracing) {
						xtra = traceExtra();
					}
				}
				if (PC == hdosv) {
					if (tracing) {
						trc.postTrace(PC, clk, xtra);
					}
					hdosTrap(PC);
					if (!running) {
						break;
					}
					// TODO: might return to TRAP?
					// need 'while (PC == hdosv)...'?
					PC = cpu.getRegPC();
					if (trc != null) {
						tracing = trc.preTrace(PC, clock);
						if (tracing) {
							xtra = traceExtra();
						}
					}
				}
				if (PC >= hdose || PC < 0x1800) {
					int e = checkTrap(PC);
					if (e == 0) {
						doRET();
						continue;
					}
					if (e == 1) {
						System.err.format("Crash %04x\n", PC);
					}
					running = false;
					break;
				}
				clk = cpu.execute();
				if (tracing) {
					trc.postTrace(PC, clk, xtra);
				}
				if (vers >= 0x30 && done && !cpu.isIE() &&
						PC == cpu.getRegPC()) {
					System.out.format("\n");
					running = false;
				}
				clock += clk;
			}
		}
		if (timer != null) timer.stop();
		if (coredump != null) dumpCore(coredump);
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

	public void actionPerformed(ActionEvent e) {
		// only could be timer...
		updateTOD();
	}

	class ConsoleInput implements Runnable {
		private Thread thread;
		private BufferedReader lin;
		public ConsoleInput() {
			lin = new BufferedReader(new InputStreamReader(System.in));
			thread = new Thread(this);
			thread.setDaemon(true); // so we can exit gracefully
			thread.start();
		}
		private void processLine(String s) {
			int x, c;
			int n = s.length();
			for (x = 0; x < n; ++x) {
				c = s.charAt(x);
				// Ctrl-C or Ctrl-X = CTLC
				if (c == 0x03 || c == 0x18) {
					// Ctrl-C goes immediately
					cpu.setINTLine(true);
					return; // discard rest
				}
				fifo.add(c);
			}
			fifo.add((int)'\n');
		}
		public void run() {
			int c;
			String s;
			boolean gobble = false;
			while (true) {
				try {
					s = lin.readLine();
					if (s == null) break;
					processLine(s);
				} catch (Exception ee) {
					break;
				}
			}
		}
	}
}
