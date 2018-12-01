// Copyright (c) 2016 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.util.Date;
import java.util.TimeZone;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileLock;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.lang.reflect.Constructor;
import java.util.Properties;

public class HostFileBdos implements NetworkServer {

	class cpmFcb {
		public static final int byteLength = 36;
		public byte drv;
		public String name;
		private boolean[] attrs = new boolean[11];
		public byte ext;
		public byte s1;
		public byte s2;
		public byte rc;
		public short fd;
		public short fd_;
		public FileLock flk;
		public byte cr;
		public int rr;
		public boolean dummy = false;
		public boolean safe = false;

		// These could be implemented using Unix file modes.
		public boolean ATTR_RO() { return attrs[8]; }
		public boolean ATTR_SYS() { return attrs[9]; }
		public boolean ATTR_AR() { return attrs[10]; }
		public void SET_RO() { attrs[8] = true; }
		public void SET_SYS() { attrs[9] = true; }
		public void SET_AR() { attrs[10] = true; }

		// Interface attributes, never stored to disk.
		// CP/M 3 interface attributes:
		public boolean ATTR_PC() { return attrs[4]; } // CLOSE: partial close
		public boolean ATTR_DELX() { return attrs[4]; } // DELETE: only delete XFCBs
		public boolean ATTR_PW() { return attrs[5]; } // MAKE: assign password
		public boolean ATTR_BC() { return attrs[5]; } // SET ATTR: set byte count
		// MP/M 3 interface attributes (in addition to (most) CP/M 3):
		public boolean ATTR_UNLK() { return attrs[4]; } // OPEN/MAKE: unlocked mode
		public boolean ATTR_OPRO() { return attrs[5]; } // OPEN: R/O mode

		public void SET_USR0() { attrs[7] = true; } // File from user 0 (cur not)

		public byte getDrv() {
			byte d = (byte)(drv & 0x1f);
			if (d == 0) {
				d = (byte)curDsk;
			} else {
				--d;
			}
			return (byte)(d & 0x0f);
		}

		public void clearIntfAttrs() {
			Arrays.fill(attrs, false);
		}

		public cpmFcb() {
			clearIntfAttrs();
			fd = fd_ = 0; // invalid
		}

		public cpmFcb(byte[] buf, int start) {
			drv = buf[start++];
			byte[] nam = new byte[11];
			for (int x = 0; x < 11; ++x) {
				attrs[x] = (buf[start] & 0x80) != 0;
				nam[x] = (byte)(buf[start] & 0x7f);
				++start;
			}
			name = new String(nam);
			ext = buf[start++];
			s1 = buf[start++];
			s2 = buf[start++];
			rc = buf[start++];
			// 16 bytes done.
			fd = (short)(buf[start++] & 0xff);
			fd = (short)(fd | ((buf[start++] & 0xff) << 8));
			fd_ = (short)(buf[start++] & 0xff);
			fd_ = (short)(fd_ | ((buf[start++] & 0xff) << 8));
			safe = (buf[start++] & 0xff) != 0;
			//oflags = oflags | ((buf[start++] & 0xff) << 8);
			//oflags = oflags | ((buf[start++] & 0xff) << 16);
			//oflags = oflags | ((buf[start++] & 0xff) << 24);
			start += 11; // skip rest of 32 bytes.
			cr = buf[start++];
			rr = buf[start++] & 0xff;
			rr = rr | ((buf[start++] & 0xff) << 8);
			rr = rr | ((buf[start++] & 0xff) << 16);
		}

		public void put(byte[] buf, int start) {
			// TODO: only update parts?
			buf[start++] = drv;
			byte[] nam = name.getBytes();
			for (int x = 0; x < 11; ++x) {
				if (attrs[x]) {
					nam[x] |= 0x80;
				}
				buf[start++] = nam[x];
			}
			buf[start++] = ext;
			buf[start++] = s1;
			buf[start++] = s2;
			buf[start++] = rc;
			// 16 bytes done.
			if (dummy) {
				// fill-in alloc with dummy data
				Arrays.fill(buf, start, start + 16, (byte)1);
				// do not fill in FCB CR, RR.
			} else {
				buf[start++] = (byte)(fd & 0xff);
				buf[start++] = (byte)((fd >> 8) & 0xff);
				buf[start++] = (byte)(fd_ & 0xff);
				buf[start++] = (byte)((fd_ >> 8) & 0xff);
				buf[start++] = (byte)(safe ? 1 : 0);
				//buf[start++] = (byte)((oflags >> 8) & 0xff);
				//buf[start++] = (byte)((oflags >> 16) & 0xff);
				//buf[start++] = (byte)((oflags >> 24) & 0xff);
				start += 11; // skip rest of 32 bytes.
				buf[start++] = cr;
				buf[start++] = (byte)(rr & 0xff);
				buf[start++] = (byte)((rr >> 8) & 0xff);
				buf[start++] = (byte)((rr >> 16) & 0xff);
			}
		}

		// Newly opened/created file
		public void putNew(byte[] buf, int start, boolean rand) {
			start +=12; // ext
			buf[start++] = ext;
			buf[start++] = s1;
			buf[start++] = s2;
			buf[start++] = rc;
			// 16 bytes done.
			buf[start++] = (byte)(fd & 0xff);
			buf[start++] = (byte)((fd >> 8) & 0xff);
			buf[start++] = (byte)(fd_ & 0xff);
			buf[start++] = (byte)((fd_ >> 8) & 0xff);
			buf[start++] = (byte)(safe ? 1 : 0);
			//buf[start++] = (byte)((oflags >> 8) & 0xff);
			//buf[start++] = (byte)((oflags >> 16) & 0xff);
			//buf[start++] = (byte)((oflags >> 24) & 0xff);
			start += 11; // skip rest of 32 bytes.
			buf[start++] = cr;
			if (rand) {
				buf[start++] = (byte)(rr & 0xff);
				buf[start++] = (byte)((rr >> 8) & 0xff);
				buf[start++] = (byte)((rr >> 16) & 0xff);
			}
		}

		// put only bytes relavent to I/O operations
		public void putIO(byte[] buf, int start, boolean rand) {
			start += 12; // ext
			buf[start++] = ext;
			buf[start++] = s1;
			buf[start++] = s2;
			buf[start++] = rc;
			// 16 bytes done.
			start += 16; // cr
			buf[start++] = cr;
			if (rand) {
				buf[start++] = (byte)(rr & 0xff);
				buf[start++] = (byte)((rr >> 8) & 0xff);
				buf[start++] = (byte)((rr >> 16) & 0xff);
			}
		}
	};

	// Compatability Attributes bit definitions
	static final byte COMPAT_PRO = (byte)0b10000000;	// Pseudo-R/O on OPEN
	static final byte COMPAT_PC = 0b01000000;	// Partial CLOSE for all
	static final byte COMPAT_NCC = 0b00100000;	// No checksum on CLOSE
	static final byte COMPAT_NCS = 0b00010000;	// No checksum ever

	private void unix2cpmdate(long time, byte[] buf, int start, boolean secs) {
		time += tz.getOffset(time);
		long unx = time / 1000;
		short date = (short)(unx / 86400 - 2922 + 1);
		unx %= 86400;
		int s = (int)(unx % 60); // seconds
		unx /= 60;
		int m = (int)(unx % 60); // minutes
		unx /= 60;
		int h = (int)(unx % 24); // hour
		buf[start++] = (byte)(date & 0xff);
		buf[start++] = (byte)((date >> 8) & 0xff);
		buf[start++] = (byte)(((h / 10) << 4) | (h % 10));
		buf[start++] = (byte)(((m / 10) << 4) | (m % 10));
		if (secs) {
			buf[start++] = (byte)(((s / 10) << 4) | (s % 10));
		}
	}

	// These need to become methods to put/get from byte[]...
	class cpmFcbDate {
		public static final int byteLength = 4;
		public long timet;

		public cpmFcbDate() {
			timet = 0;
		}
		public void put(byte[] buf, int start) {
			unix2cpmdate(timet, buf, start, false);
		}
	};

	class cpmDate {
		public static final int byteLength = 5;
		public long timet;

		public cpmDate() {
			timet = 0;
		}
		public void put(byte[] buf, int start) {
			unix2cpmdate(timet, buf, start, true);
		}
	};

	class cpmSfcbs {
		public static final int byteLength = 2 + 2 * cpmFcbDate.byteLength;
		cpmFcbDate atim;
		cpmFcbDate utim;
		byte pwmode;
		byte reserved;

		public cpmSfcbs() {
			atim = new cpmFcbDate();
			utim = new cpmFcbDate();
			pwmode = 0;
		}
		public void put(byte[] buf, int start) {
			atim.put(buf, start);
			start += cpmFcbDate.byteLength;
			utim.put(buf, start);
			start += cpmFcbDate.byteLength;
			buf[start++] = pwmode;
			buf[start++] = reserved;
		}
	};

	class cpmSfcb {
		public static final int byteLength = 2 + 3 * cpmSfcbs.byteLength;
		byte drv; // always 0x21
		cpmSfcbs[] fcbs = new cpmSfcbs[3];
		byte reserved;
		public cpmSfcb() {
			drv = (byte)0x21;
			fcbs[0] = new cpmSfcbs();
			fcbs[1] = new cpmSfcbs();
			fcbs[2] = new cpmSfcbs();
		}
		public void put(byte[] buf, int start) {
			buf[start++] = drv;
			fcbs[0].put(buf, start);
			start += cpmSfcbs.byteLength;
			fcbs[1].put(buf, start);
			start += cpmSfcbs.byteLength;
			fcbs[2].put(buf, start);
			start += cpmSfcbs.byteLength;
			buf[start++] = reserved;
		}
	};

	class cpmDirl {
		public static final int byteLength = 24 + 2 * cpmFcbDate.byteLength;
		public byte drv;	// always 0x20
		public String name;
		public byte ext;	// mode byte
		public byte s1;	// 0x00
		public byte s2;	// 0x00
		public byte rc;	// 0x00
		public byte[] pwd = new byte[8];	// password
		public cpmFcbDate ctim;
		public cpmFcbDate utim;

		public cpmDirl() {
			drv = (byte)0x20;
			ctim = new cpmFcbDate();
			utim = new cpmFcbDate();
			Arrays.fill(pwd, (byte)' ');
		}
		public void put(byte[] buf, int start) {
			buf[start++] = drv;
			byte[] nam = name.getBytes();
			for (int x = 0; x < 11; ++x) {
				buf[start++] = nam[x];
			}
			buf[start++] = ext;
			buf[start++] = s1;
			buf[start++] = s2;
			buf[start++] = rc;
			for (int x = 0; x < 8; ++x) {
				buf[start++] = pwd[x];
			}
			ctim.put(buf, start);
			start += cpmFcbDate.byteLength;
			utim.put(buf, start);
			start += cpmFcbDate.byteLength;
		}
	};

	class cpmDirf {
		public static final int byteLength = 2 + 3 * cpmSfcbs.byteLength;
		byte drv;
		String name;
		byte ext;
		byte s1;
		byte s2;
		byte rc;

		public cpmDirf(byte[] buf, int start) {
			drv = buf[start++];
			name = new String(buf, start, 11);
			start += 11;
			ext = buf[start++];
			s1 = buf[start++];
			s2 = buf[start++];
			rc = buf[start++];
			// TODO: use HostFileBdos format or CP/M?
		}
	};

	class cpmFind {
		String[] dir; // contents of the dir...
		String pat;  // pattern used to match
		String path; // path to "drive"
		String base; // basename, i.e. file found
		int index;
		int dirlen;
		public cpmFind() {
			dir = null;
			index = 0;
		}
	};

	class cpmSearch {
		public int iter;
		public int lastit;
		public int endit;
		public byte maxdc;	// 3 or 4, depending on timestamps (SFCBs)
		public byte drv;
		public byte usr;
		public byte ext;
		public boolean full;
		public boolean cpm3;
		public byte bc;
		public long size;
		public boolean rw;
		public boolean ex;
		public cpmFind find = new cpmFind();
		public cpmSearch() {
			iter = 0;
			full = false;
			cpm3 = false;
		}
	};

	class cpmDpb {
		static final int byteLength = 15;
		public short spt;
		public byte bsh;
		public byte blm;
		public byte exm;
		public short dsm;
		public short drm;
		public short al0; // big-endian!
		public short cks;
		public short off;

		public cpmDpb() {
		}

		public cpmDpb(byte[] buf, int start) {
		}

		public void put(byte[] buf, int start) {
			buf[start++] = (byte)(spt & 0xff);
			buf[start++] = (byte)((spt >> 8) & 0xff);
			buf[start++] = bsh;
			buf[start++] = blm;
			buf[start++] = exm;
			buf[start++] = (byte)(dsm & 0xff);
			buf[start++] = (byte)((dsm >> 8) & 0xff);
			buf[start++] = (byte)(drm & 0xff);
			buf[start++] = (byte)((drm >> 8) & 0xff);
			// AL0 stored BIG-endian!
			buf[start++] = (byte)((al0 >> 8) & 0xff);
			buf[start++] = (byte)(al0 & 0xff);
			//
			buf[start++] = (byte)(cks & 0xff);
			buf[start++] = (byte)((cks >> 8) & 0xff);
			buf[start++] = (byte)(off & 0xff);
			buf[start++] = (byte)((off >> 8) & 0xff);
		}
	};

	class OpenFile {
		public RandomAccessFile fd;
		public FileLock flk;
		public Vector<FileLock> rlks;
		public int drv; // CP/M drive vector of file
		public OpenFile() {
			fd = null;
			drv = 0;
		}
	};

	interface BdosFunc {
		int exec(byte[] msg, int len);
	}

	static final int DEF_BLS_SH = 14;	// 2^14 = 16K
	static final int DEF_BLS = (1 << DEF_BLS_SH);
	static final int DEF_NBLOCKS = 128;	// keep alloc vec small, disk size 2M
	static final int DEF_NFILE = 32; // Probably never need even 8.
	static final byte dirMode = (byte)0b01100001;

	TimeZone tz = TimeZone.getDefault();

	private OpenFile[] openFiles;
	private cpmSfcb sFcb;
	private cpmDpb curDpb;
	private cpmSearch curSearch;
	private int curDsk;
	private int curUsr;
	private int curLogVec;
	private int curROVec;
	private int curCompat;
	private int clientId;
	private long phyExt;
	private String fileName;
	private String pathName;

	// Includes MAGNet header...
	static final int cpnMsg = NetworkServer.mstart;
	// These are only valid for the duration of bdosFunction()...
	private int fcbadr;
	private int dmaadr;

	// TODO: fix this
	private int errno;
	static final int ENOENT = 2;
	static final int ENXIO = 5;
	static final int EACCES = 6;
	static final int EAGAIN = 9;

	// The reason for 'static' is so that multiple instances (clients)
	// of HostFileBdos may exist, but all use a common server config.
	static final CpnetServerConfig cfgTab = new CpnetServerConfig();
	public static void initCfg(char tmp, byte sid, int max, String dir) {
		HostFileBdos.cfgTab.tmp = (byte)((tmp - 'A') & 0x0f);
		HostFileBdos.cfgTab.sts = (byte)0b00010000; // "ready"
		HostFileBdos.cfgTab.id = sid;
		HostFileBdos.cfgTab.max = (byte)max;
		HostFileBdos.dir = dir;
	}

	public static void initLsts(Properties props, String prefix) {
		String pfx = prefix;
		String s;
		pfx += "_lst";
		int pflen = pfx.length();
		// Could have up to 16 printers assigned...
		for (String prop : props.stringPropertyNames()) {
			if (prop.startsWith(pfx)) {
				s = props.getProperty(prop); // can't be null
				int lid;
				try {
					lid = Integer.valueOf(prop.substring(pflen), 16);
				} catch (Exception ee) {
					System.err.format("Invalid List ID, skipping %s\n", s);
					continue;
				}
				if (lid < 0 || lid >= 16) {
					System.err.format("Invalid List ID %01lx: skipping %s\n", lid, s);
					continue;
				}
				if (s.length() == 0) {
					System.err.format("Empty List property %s\n", prop);
					continue;
				}
				System.err.format("List %01x: %s\n", lid, s);
				initLst(props, lid, s);
			}
		}
	}

	static final OutputStream[] lsts = new OutputStream[16];
	static final int[] lstCid = new int[16];
	static String dir = null;
	static {
		Arrays.fill(lsts, null);
		Arrays.fill(lstCid, 0xff);
	}

	// args: "HostFileBdos" [root-dir [tmp-drv]]
	public HostFileBdos(Properties props, String prefix, Vector<String> args, int cltId) {
		clientId = cltId;
		curDsk = -1;
		curUsr = 0;
		curROVec = 0;
		curLogVec = 0;
		curCompat = 0;
		curSearch = new cpmSearch();
		curDpb = new cpmDpb();
		openFiles = new OpenFile[DEF_NFILE];
		for (int x = 0; x < DEF_NFILE; ++x) {
			openFiles[x] = new OpenFile();
		}
		sFcb = new cpmSfcb();
		sFcb.drv= 0x21;
		curDpb.spt = 64; // some number
		curDpb.bsh = DEF_BLS_SH - 7;
		curDpb.blm = (byte)((1 << curDpb.bsh) - 1);
		curDpb.dsm = DEF_NBLOCKS - 1;
		curDpb.drm = 63; // some number
		if (curDpb.dsm >= 256) {
			curDpb.exm = (byte)((1 << (curDpb.bsh - 4)) - 1);
			phyExt = 8 << curDpb.bsh;
		} else {
			curDpb.exm = (byte)((1 << (curDpb.bsh - 3)) - 1);
			phyExt = 16 << curDpb.bsh;
		}
		curDpb.cks = 0; // perhaps should be non-zero, as
				// files can change without notice.
				// But clients shouldn't be handling that.
		// login this client - unless we wait for LOGIN message with PASSWORD...
		if (!addClient(clientId)) {
			// TODO: need to return error and disable this instance...
			// Or, let LOGON fail later?
		}

		// args[0] is our class name, like argv[0] in main().
		String s = null;

		// In multi-client environment, temp drive is set only by server init.
		if (HostFileBdos.cfgTab.max == 1) {
			if (dir == null) {
				if (args.size() > 1) {
					s = args.get(1);
				} else {
					s = prefix;
					s += "_root_dir";
					s = props.getProperty(s);
				}
				if (s == null) {
					s = System.getProperty("user.home") + "/HostFileBdos";
				}
				dir = s;
			}
			if (args.size() > 2) {
				s = args.get(2);
				if (s.length() == 2 && Character.isLetter(s.charAt(0)) && s.charAt(1) == ':') {
					HostFileBdos.cfgTab.tmp = (byte)((Character.toUpperCase(s.charAt(0)) - 'A') - 0x0f);
				}
			}
		}
		System.err.format("Creating HostFileBdos %02x device with root dir %s\n",
						HostFileBdos.cfgTab.id, dir);
	}

	private static void initLst(Properties props, int lid, String s) {
		if (s.charAt(0) == '>') { // redirect to file
			attachFile(lid, s.substring(1));
		} else if (s.charAt(0) == '|') { // pipe to program
			attachPipe(lid, s.substring(1));
		} else {
			attachClass(props, lid, s);
		}
	}

	private static void attachFile(int lid, String s) {
		// TODO: allow parameters? Allow spaces? APPEND at least?
		String[] args = s.split("\\s");
		try {
			lsts[lid] = new FileOutputStream(args[0]);
		} catch (Exception ee) {
			System.err.format("Invalid file in attachment: %s\n", s);
			return;
		}
	}

	private static void attachPipe(int lid, String s) {
		System.err.format("Pipe attachments not yet implemented: %s\n", s);
	}

	private static void attachClass(Properties props, int lid, String s) {
		String[] args = s.split("\\s");
		Vector<String> argv = new Vector<String>(Arrays.asList(args));
		// try to construct from class...
		try {
			Class<?> clazz = Class.forName(args[0]);
			Constructor<?> ctor = clazz.getConstructor(Properties.class,
					argv.getClass());
			lsts[lid] = (OutputStream)ctor.newInstance(props, argv);
		} catch (Exception ee) {
ee.printStackTrace();
			System.err.format("Invalid class in attachment: \"%s\"\n", args[0]);
			return;
		}
	}

	private void makeError(byte[] buf) {
		ServerDispatch.putCode(buf,
			ServerDispatch.getCode(buf) & 0xfe);
		ServerDispatch.putBC(buf, 0);
		ServerDispatch.putDE(buf, 1); // error ?
	}

	private byte[] doMagNet(byte[] msgbuf, int length) {
		int code = ServerDispatch.getCode(msgbuf);
		if (code == 0xd0) { // Token - status
			// TODO: merge our own copy...
			int ix = NetworkServer.mpayload + 1 +
				HostFileBdos.cfgTab.id;
			msgbuf[ix] = (byte)NetworkServer.tfileserver;
			ServerDispatch.putBC(msgbuf, 0x1000 | HostFileBdos.cfgTab.id);
			return msgbuf;
		} else if (code == 0xb1) { // Boot
			// this gets tricky, we don't have room in msgbuf.
			String file = String.format("%s/boot%02x.img", dir, clientId);
			try {
				FileInputStream boot = new FileInputStream(file);
				int len = (int)boot.getChannel().size();
				int adr = 0x3000; // TODO: how to get?
				// Or... does file contain entire message header?
				byte[] img = new byte[NetworkServer.mpayload + len];
				boot.read(img, NetworkServer.mpayload, len);
				ServerDispatch.putCode(img, 0xb0);
				ServerDispatch.putBC(img, len);
				ServerDispatch.putDE(img, 0);
				ServerDispatch.putHL(img, adr);
				return img;
			} catch (Exception ee) {
				makeError(msgbuf);
				return msgbuf;
			}
		} else {
			// TODO: this may not be correct...
			makeError(msgbuf);
			return msgbuf;
		}
	}

	public byte[] checkRecvMsg(byte clientId) {
		// For HostFileBdos, this is never used.
		// The response was posted from sendMsg().
		return null;
	}

	public int bdosCall(int fnc, byte[] mem, int param, int len, int fcb, int dma) {
		fcbadr = fcb;
		dmaadr = dma;
		return bdosFunction(fnc, mem, param, len);
	}

	public byte[] sendMsg(byte[] msgbuf, int len) {
		if (ServerDispatch.getCode(msgbuf) != 0xc1) {
			// MAGNet messages
			return doMagNet(msgbuf, len);
		}
		int fnc = msgbuf[NetworkServer.mfunc] & 0xff;
		//dumpMsg(true, msgbuf, len);
		fcbadr = cpnMsg + 1;
		if (fnc == 17) {
			fcbadr += 1;
		}
		dmaadr = cpnMsg + 37;
		int lr = -1;
		if (fnc == 64 || chkClient(clientId)) {
			lr = bdosFunction(fnc, msgbuf, cpnMsg, len);
		} else {
			lr = -1;
		}
		//dumpMsg(false, msgbuf, lr);
		if (lr <= 0) {
			msgbuf[cpnMsg] = (byte)0xff;
			msgbuf[cpnMsg + 1] = (byte)12;
			lr = 2;
		}
		// fix up CP/NET message header for reply...
		msgbuf[NetworkServer.msize] = (byte)(lr - 1);
		byte src = msgbuf[NetworkServer.msid];
		msgbuf[NetworkServer.msid] = msgbuf[NetworkServer.mdid];
		msgbuf[NetworkServer.mdid] = src;
		msgbuf[NetworkServer.mcode] |= 1;
		// fix-up MAGNet header for reply...
		ServerDispatch.putCode(msgbuf, 0xc0);
		ServerDispatch.putBC(msgbuf, lr + NetworkServer.mhdrlen);
		ServerDispatch.putDE(msgbuf, 0);
		ServerDispatch.putHL(msgbuf, 0);
		return msgbuf;
	}

	// 'start' points to cpnMsg already...
	private int bdosFunction(int fnc, byte[] msg, int start, int len) {
		switch(fnc) {
		case 5:
			return listOut(msg, start, len);
		case 14:
			return selectDisk(msg, start, len);
		case 15:
			return openFile(msg, start, len);
		case 16:
			return closeFile(msg, start, len);
		case 17:
			return searchFirst(msg, start, len);
		case 18:
			return searchNext(msg, start, len);
		case 19:
			return deleteFile(msg, start, len);
		case 20:
			return readSeq(msg, start, len);
		case 21:
			return writeSeq(msg, start, len);
		case 22:
			return createFile(msg, start, len);
		case 23:
			return renameFile(msg, start, len);
		case 24:
			return getLoginVec(msg, start, len);
		case 27:
			return getAllocVec(msg, start, len);
		case 28:
			return writeProt(msg, start, len);
		case 29:
			return getROVec(msg, start, len);
		case 30:
			return setFileAttrs(msg, start, len);
		case 31:
			return getDPB(msg, start, len);
		case 33:
			return readRand(msg, start, len);
		case 34:
			return writeRand(msg, start, len);
		case 35:
			return compFileSize(msg, start, len);
		case 36:
			return setRandRec(msg, start, len);
		case 37:
			return resetDrive(msg, start, len);
		case 38:
			return accessDrive(msg, start, len);
		case 39:
			return freeDrive(msg, start, len);
		case 40:
			return writeRandZF(msg, start, len);
		case 42:
			return lockRec(msg, start, len);
		case 43:
			return unlockRec(msg, start, len);
		case 46:
			return getFreeSp(msg, start, len);
		case 48:
			return flushBuf(msg, start, len);
		case 64:
			return login(msg, start, len);
		case 65:
			return logoff(msg, start, len);
		case 70:
			return setCompAttrs(msg, start, len);
		case 71:
			return getServCfg(msg, start, len);
		case 98:
			return freeBlks(msg, start, len);
		case 99:
			return truncFile(msg, start, len);
		case 101:
			return getDirLab(msg, start, len);
		case 102:
			return readFStamps(msg, start, len);
		case 105:
			return getTime(msg, start, len);
		case 106:
			return setDefPwd(msg, start, len);
		default:
			return -1;
		}
	}

	private static synchronized boolean acquireLst(int cid, int lst) {
		// TODO: lock after checking max?
		if (HostFileBdos.cfgTab.max == 1) {
			return true;
		}
		if (lstCid[lst] == cid) {
			return true;
		}
		if (lstCid[lst] != 0xff) {
			return false;
		}
		lstCid[lst] = cid;
		return true;
	}

	private static synchronized void releaseLst(int cid, int lst) {
		// TODO: confirm we own it?
		lstCid[lst] = 0xff;
	}

	private static synchronized boolean addClient(int cid) {
		int x;
		if (HostFileBdos.cfgTab.cur >= HostFileBdos.cfgTab.max) {
			return false;
		}
		int y = 16;
		for (x = 0; x < 16; ++x) {
			if ((HostFileBdos.cfgTab.rid[x] & 0xff) == cid) {
				return true;
			}
			if ((HostFileBdos.cfgTab.vec & (1 << x)) == 0) {
				if (y >= 16) {
					y = x;
				}
			}
		}
		if (y >= 16) {
			return false;	// should not happen
		}
		++HostFileBdos.cfgTab.cur;
		HostFileBdos.cfgTab.vec |= (1 << y);
		HostFileBdos.cfgTab.rid[y] = (byte)cid;
		return true;
	}

	private static synchronized boolean chkClient(int cid) {
		int x;
		for (x = 0; x < 16; ++x) {
			if ((HostFileBdos.cfgTab.rid[x] & 0xff) == cid) {
				return true;
			}
		}
		return false;
	}

	private static synchronized int rmClient(int cid) {
		int x;
		for (x = 0; x < 16; ++x) {
			if ((HostFileBdos.cfgTab.rid[x] & 0xff) == cid) {
				break;
			}
		}
		if (x < 16) {
			--HostFileBdos.cfgTab.cur;
			HostFileBdos.cfgTab.vec &= ~(1 << x);
			HostFileBdos.cfgTab.rid[x] = (byte)0xff;
			return 0;
		}
		return -1;
	}

	public void shutdown() {
		for (int x = 0; x < lstCid.length; ++x) {
			if (lstCid[x] == clientId) {
				releaseLst(clientId, x);
			}
		}
		rmClient(clientId);
	}

	// Debug only.
	private void dumpMsg(boolean send, byte[] msgbuf, int n) {
		// For send use msize + 1, else use 'n'...
		int fnc = msgbuf[NetworkServer.mfunc];
		int msg = cpnMsg;
		int len = send ? (msgbuf[NetworkServer.msize] & 0xff) + 1 : n;
		if (!send && (fnc == 17 || fnc == 18)) {
			if (len < 32) {
				System.err.format("dirent %d\n", msgbuf[msg]);
				return;
			}
			int x = 0;
			int dir = msg + 1;
			while (len > 32) {
				if (msgbuf[dir] == 0x21) {
					System.err.format("dirent %d " +
						"%02x " +
						"%02x%02x %02x:%02x %02x%02x %02x:%02x %02x " +
						"%02x%02x %02x:%02x %02x%02x %02x:%02x %02x " +
						"%02x%02x %02x:%02x %02x%02x %02x:%02x %02x\n",
						x,
						msgbuf[dir] & 0xff,
						msgbuf[dir + 2] & 0xff, msgbuf[dir + 1] & 0xff,
						msgbuf[dir + 3] & 0xff, msgbuf[dir + 4] & 0xff,
						msgbuf[dir + 6] & 0xff, msgbuf[dir + 5] & 0xff,
						msgbuf[dir + 7] & 0xff, msgbuf[dir + 8] & 0xff,
						msgbuf[dir + 9] & 0xff,
						// 10
						msgbuf[dir + 12] & 0xff, msgbuf[dir + 11] & 0xff,
						msgbuf[dir + 13] & 0xff, msgbuf[dir + 14] & 0xff,
						msgbuf[dir + 16] & 0xff, msgbuf[dir + 15] & 0xff,
						msgbuf[dir + 17] & 0xff, msgbuf[dir + 18] & 0xff,
						msgbuf[dir + 19] & 0xff,
						// 20
						msgbuf[dir + 22] & 0xff, msgbuf[dir + 21] & 0xff,
						msgbuf[dir + 23] & 0xff, msgbuf[dir + 24] & 0xff,
						msgbuf[dir + 26] & 0xff, msgbuf[dir + 25] & 0xff,
						msgbuf[dir + 27] & 0xff, msgbuf[dir + 28] & 0xff,
						msgbuf[dir + 29] & 0xff
						// 30, 31

						);
				} else {
					cpmDirf fcb = new cpmDirf(msgbuf, dir);
					System.err.format("dirent %d " +
						"%02x \"%s\" %02x %02x %02x %02x\n",
						x,
						fcb.drv, fcb.name,
						fcb.ext, fcb.s1, fcb.s2, fcb.rc
						// TODO: use HostFileBdos format or CP/M?
						);
				}
				dir += 32;
				len -= 32;
				++x;
			}
			return;
		}
		if (fnc == 17) { // must be send
			cpmDirf fcb = new cpmDirf(msgbuf, msg + 2);
			System.err.format("MSG %02x: %d: %02x %02x " +
				"%02x \"%s\" %02x %02x %02x %02x ...\n",
				fnc, len, msgbuf[cpnMsg] & 0xff, msgbuf[cpnMsg + 1] & 0xff,
				fcb.drv, fcb.name,
				fcb.ext, fcb.s1, fcb.s2, fcb.rc);
			return;
		}
		if (len > 30) { // must have FCB
			cpmFcb fcb = new cpmFcb(msgbuf, msg + 1);
			System.err.format("MSG %02x: %d: %02x " +
				"%02x \"%s\" %02x %02x %02x %02x " +
				"%04x %04x " +
				"... %02x %06x\n",
				fnc, len, msgbuf[cpnMsg] & 0xff,
				fcb.drv & 0xff, fcb.name,
				fcb.ext & 0xff, fcb.s1 & 0xff, fcb.s2 & 0xff, fcb.rc & 0xff,
				fcb.fd, fcb.fd_,
				fcb.cr & 0xff, fcb.rr);
			return;
		}
		System.err.format("MSG %02x: %d: %02x %02x [...]\n",
			fnc, len, msgbuf[cpnMsg] & 0xff, msgbuf[cpnMsg + 1] & 0xff);
	}

	String cpmDrive(int drive) {
		drive &= 0x0f;
		return String.format("%s/%c", dir, (char)(drive + 'a'));
	}

	String cpmFilename(int user, String file) {
		String str = "";
		user &= 0x1f;
		if (user > 0) {
			str += String.format("%d:", user);
		}
		str += file;
		return str;
	}

	public String cpmPath(int drive, int user, String file) {
		String str = cpmDrive(drive);
		str += '/';
		str += cpmFilename(user, file);
		return str;
	}

	String cpmNameFound(cpmFind find) {
		return find.base;
	}

	String cpmPathFound(cpmFind find) {
		return find.path + '/' + find.base;
	}

	// Not used, as init is automatic on Search First.
	void cpmFindInit(cpmFind find, int drive, String pattern) {
		if (find.dir != null) {
			find.dir = null; // "close" previous file list...
		}
		find.pat = pattern;
		find.path = cpmDrive(drive);
		find.dirlen = find.path.length(); // not used?
		find.dir = new File(find.path).list();
		find.index = 0;
		if (find.dir == null) {
			//System.err.format("no dir: %s\n", find.path);
		}
	}

	String cpmFind(cpmFind find, byte usr) {
		if (find.dir == null) {
			errno = ENXIO;
			return null;
		}
		String de = null;
		do {
			if (find.index >= find.dir.length) {
				errno = ENOENT;
				return null;
			}
			de = find.dir[find.index++];
			if (de.startsWith(".")) {
				continue;
			}
			//System.err.format("looking at %s... %s\n", de.d_name, find.pat);
			// need to prevent "*.*" at user 0 from matching all users!
			if (usr == 0 && (de.charAt(1) == ':' || de.charAt(2) == ':')) {
				continue;
			}
			if (de.matches(find.pat)) {
				break;
			}
		} while (true);
		find.base = de;
		return cpmNameFound(find);
	}

	String getFileName(cpmFcb fcb) {
		int x;
		String dst = "";
		for (x = 0; x < 8 && (fcb.name.charAt(x)) != ' '; ++x) {
			dst += Character.toLowerCase(fcb.name.charAt(x));
		}
		for (x = 8; x < 11 && (fcb.name.charAt(x)) != ' '; ++x) {
			if (x == 8) {
				dst += '.';
			}
			dst += Character.toLowerCase(fcb.name.charAt(x));
		}
		return dst;
	}

	String getAmbFileName(cpmFcb fcb, byte usr) {
		int x;
		String buf = "";
		boolean sawQ = false;
		char c;
		for (x = 0; x < 8 && (fcb.name.charAt(x)) != ' '; ++x) {
			c = fcb.name.charAt(x);
			if (c == '?') {
				buf += ".*";
				sawQ = true;
				break;
			}
			if (c == '$') {
				buf += '\\';
			}
			buf += Character.toLowerCase(c);
		}
		for (x = 8; x < 11 && (fcb.name.charAt(x)) != ' '; ++x) {
			c = fcb.name.charAt(x);
			if (x == 8) {
				if (c == '?') {
					// special case, need "*.*" to be just "*"
					if (sawQ) {
						break;
					}
				}
				buf += "\\.";
			}
			if (c == '?') {
				buf += ".*";
				break;
			}
			if (c == '$') {
				buf += '\\';
			}
			buf += Character.toLowerCase(c);
		}
		return cpmFilename(usr, buf);
	}

	private void dumpDirent(byte[] buf, int start) {
		String s = String.format("%d: %02x ", curSearch.iter, buf[start]);
		if (buf[start] == 0x21) {
			s += String.format("%02x %02x %02x %02x %02x %02x %02x %02x" +
				" %02x %02x %02x",
				buf[start+001], buf[start+002], buf[start+003],
				buf[start+004], buf[start+005], buf[start+006],
				buf[start+007], buf[start+010], buf[start+011],
				buf[start+012], buf[start+013]);
		} else {
			s += new String(buf, start+1, 11);
		}
		System.err.format("%s %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x" +
			" %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x\n",
			s, buf[start+014], buf[start+015], buf[start+016], buf[start+017],
			buf[start+020], buf[start+021], buf[start+022], buf[start+023],
			buf[start+024], buf[start+025], buf[start+026], buf[start+027],
			buf[start+030], buf[start+031], buf[start+032], buf[start+033],
			buf[start+034], buf[start+035], buf[start+036], buf[start+037]);
	}

	void copyOutDir(cpmFcb fcb, String name) {
		byte[] nam = new byte[11];
		int t = name.indexOf(':');
		byte u;
		if (t > 0) {
			try {
				u = Byte.valueOf(name.substring(0, t));
			} catch(Exception ee) {
				u = 0;
			}
			++t;
		} else {
			u = 0;
			t = 0;
		}
		int x = 0;
		fcb.drv = u;	// user code, not drive
		while (t < name.length() && name.charAt(t) != '.' && x < 8) {
			nam[x++] = (byte)Character.toUpperCase(name.charAt(t++));
		}
		while (x < 8) {
			nam[x++] = ' ';
		}
		while (t < name.length() && name.charAt(t) != '.') {
			++t;
		}
		if (t < name.length() && name.charAt(t) == '.') {
			++t;
		}
		while (t < name.length() && name.charAt(t) != '.' && x < 11) {
			nam[x++] = (byte)Character.toUpperCase(name.charAt(t++));
		}
		while (x < 11) {
			nam[x++] = ' ';
		}
		fcb.name = new String(nam);
	}

	// 'start' points to DIRENT (may be DIRBUF[0])
	int copyOutSearch(byte[] buf, int start, String name) {
		Arrays.fill(buf, start, start + 32, (byte)0);
		if (name.charAt(0) == ' ') { // DIR LABEL
			cpmDirl dirLab = new cpmDirl();
			dirLab.name = String.format("SERVER%02x%c  ",
						cfgTab.id, curSearch.drv + 'A');
			dirLab.ext = dirMode;
			dirLab.drv = (byte)0x20;
			dirLab.put(buf, start);
			//dumpDirent(buf, start);
			// nothing meaningful for timestamps?
			return curSearch.iter++ & 0x03; // should be "0" (iter == 0)
		} else if (name.charAt(0) == '!') { // SFCB (already populated)
			// this should never happen...
			System.err.format("Copy Out SFCB in wrong place\n");
			sFcb.put(buf, start);
			return curSearch.iter++ & 0x03; // should be "3"
		}
		cpmFcb fcb = new cpmFcb();
		fcb.dummy = true;
		copyOutDir(fcb, name);
		// curSearch.size is only valid if curSearch.ext == '?'.
		int nb = 16;
		if (curSearch.ext == '?') {
			// create fake info in FCB to fool caller into
			// thinking the file is of about the right size.
			// This does not account for sparse files.
			long len = curSearch.size;	// len in bytes
			if (len <= phyExt * 128) {
				curSearch.size = 0;
			} else {
				curSearch.size -= phyExt * 128;
			}
			len = (len + 127) / 128;	// len in records
			// STAT only cares about (ext & exm), so actually ext can wrap.
			// But, (ext & exm) must reflect the number of
			// logical extents in this dirent.
			int x;
			if (len <= phyExt) {
				nb = (int)((len + curDpb.blm) >> curDpb.bsh);
				fcb.ext = (byte)(len / 128);	// num logical extents
				fcb.rc = (byte)(len & 0x7f);
				if (len > 0 && fcb.rc == 0) {
					fcb.rc = (byte)128;
				}
			} else {
				fcb.ext = curDpb.exm;	// should have advancing num in hi bits
				fcb.rc = (byte)128;
			}
		}
		fcb.s1 = curSearch.bc;
		if (curSearch.ex) {
			fcb.SET_SYS();
		}
		if (!curSearch.rw) {
			fcb.SET_RO();
		}
		fcb.put(buf, start);
		for (int x = 0; x < 16; ++x) {
			buf[start + 16 + x] = (byte)(x < nb ? 1 : 0);
		}
		//dumpDirent(buf, start);

		// Generally, no one (STAT) cares if this is always 0.
		// CP/Net will place this in a DMA buffer for us.
		return curSearch.iter++ & 0x03;
	}

	String commonSearch(cpmSearch search) {
		if (search.full && search.cpm3) {
			if (search.iter == 0) { // DIR LABEL
				return " ";
			} else if ((search.iter & 0x03) == 0x03) { // SFCB
				return "!";
			}
		}
		if (search.ext == '?' && search.size > 0) {
			// copyOutSearch() should ensure we don't keep landing here,
			// but caller MUST invoke copyOutSearch() each time.
			return cpmNameFound(search.find);
		}
		String f = cpmFind(search.find, search.usr);
		if (f == null) {
			return null;
		}
		// return size of file... by some definition...
		// should never fail since we just got filename from directory...
		File fi = new File(cpmPathFound(search.find));
		if (search.ext == '?') {
			search.size = fi.length();
		}
		search.bc = (byte)(fi.length() & 0x7f);
		search.rw = fi.canWrite();
		search.ex = fi.canExecute();
		if (search.full && search.cpm3) {
			// SFCB timestamps are only used if the
			// matching FCB is for extent 0. However,
			// we just populate it anyway, especially since
			// extent info is not computed until copyOut.
			int x = search.iter & 0x03; // 0, 1, 2 only
			BasicFileAttributes attrs = getAttrs(fi.getAbsolutePath());
			if (attrs != null) {
				sFcb.fcbs[x].atim.timet =
					attrs.lastAccessTime().toMillis();
				sFcb.fcbs[x].utim.timet =
					attrs.lastModifiedTime().toMillis();
			}
			//sFcb.fcbs[x].atim.timet = fi.lastAccess();
			//sFcb.fcbs[x].utim.timet = fi.lastModified();
		}
		return f;
	}

	// 'start' points to DIRBUF
	int fullSearch(byte[] dirbuf, int start, cpmSearch search, String ff) {
		int ix = 0;	// by definition, we are starting with DIRENT 0.
		int saveIter = search.iter;
		search.endit = search.iter + curSearch.maxdc;
		do {
			copyOutSearch(dirbuf, start, ff);
			start += 32;
			++ix;
			ff = doSearch(search);
		} while (ff != null && ix < search.maxdc); // never includes SFCB
		search.lastit = search.iter; // if >= saveIter+maxdc then no error
		search.iter = saveIter;
		while (ix < 4) {
			if (ix == search.maxdc) { // can only happen for SFCB
				sFcb.put(dirbuf, start);
			} else {
				dirbuf[start] = (byte)0xe5;
			}
			//dumpDirent(dirbuf, start);
			start += 32;
			++ix;
		}
		return search.iter++ & 0x03;
	}

	String doSearch(cpmSearch search) {
		return commonSearch(search);
	}

	void endSearch(cpmSearch search) {
		if (search.find.dir != null) {
			search.find.dir = null;
		}
	}

	String startSearch(cpmFcb fcb, cpmSearch search, byte u) {
		String pat;
		byte d = (byte)(fcb.drv & 0x7f);
		search.iter = 0;
		if (d == '?') {
			search.full = true;
			search.cpm3 = ((fcb.drv & 0x80) != 0); // CP/M 3 sets hi bit
			search.ext = '?';
			search.maxdc = (byte)(search.cpm3 ? 3 : 4);
			d = (byte)curDsk;
			pat = ".*";
		} else {
			search.full = false;
			search.cpm3 = false; // don't care if !full
			search.ext = fcb.ext; // might still be '?' (e.g. CP/M 2 STAT.COM)
			if (d == 0) {
				d = (byte)curDsk;
			} else {
				--d;
			}
			pat = getAmbFileName(fcb, u);
		}
		search.drv = d;
		search.usr = u;
		//System.err.format("Searching \"%s\" user %d drive %c\n", pat, u, 'A'+d);
		cpmFindInit(search.find, search.drv, pat);
		return commonSearch(search);
	}

	void seekFile(cpmFcb fcb, OpenFile of) {
		if (of == null) {
			of = getFileFcb(fcb);
			if (of == null) {
				return;
			}
		}
		long r = fcb.rr;
		r *= 128;
		try {
			of.fd.seek(r);
		} catch (Exception ee) {}
		fcb.s1 = fcb.cr;
	}

	int newFileFcb() {
		int x;
		for (x = 0; x < DEF_NFILE; ++x) {
			if (openFiles[x].fd == null) {
				break;
			}
		}
		if (x >= DEF_NFILE) {
			System.err.format("Too many open files\n");
			return -1;
		}
		openFiles[x].rlks = new Vector<FileLock>();
		return x;
	}

	int locFileFcb(cpmFcb fcb) {
		if (((fcb.fd ^ fcb.fd_) & 0xffff) != 0xffff) {
			return -1;
		}
		int x = fcb.fd;
		if (x < 0 || x >= DEF_NFILE) {
			fcb.fd = fcb.fd_ = 0;
			return -1;
		}
		return x;
	}

	void putFileFcb(cpmFcb fcb, int ix, RandomAccessFile fd, byte drv, FileLock flk) {
		openFiles[ix].fd = fd;
		openFiles[ix].flk = flk;
		if (fd == null) {
			openFiles[ix].drv = 0;
			fcb.fd = fcb.fd_ = 0;
			openFiles[ix].flk = null;
			openFiles[ix].rlks.removeAllElements();
		} else {
			openFiles[ix].drv = (1 << drv);
			fcb.fd = (short)ix;
			fcb.fd_ = (short)~ix;
			// Only put file ID in FCB for OPEN/MAKE, the
			// NDOS decides whether to copy it out.
			fcb.rr = ix;
		}
	}

	void closeAll(int vec) {
		int n = 0;
		int x;
		for (x = 0; x < DEF_NFILE; ++x) {
			if (openFiles[x].fd != null && (openFiles[x].drv & vec) != 0) {
				try {
					openFiles[x].fd.close();
				} catch (Exception ee) {}
				openFiles[x].fd = null;
				++n;
			}
		}
		//if (n > 0) {
		//	System.err.format("Close all files %04x - closed %d\n", vec, n);
		//}
	}

	// Returns Java file object, not CP/M File ID...
	OpenFile getFileFcb(cpmFcb fcb) {
		int x = locFileFcb(fcb);
		if (x < 0) {
			return null;
		}
		return openFiles[x];
	}
	OpenFile getFileWrFcb(cpmFcb fcb) {
		int x = locFileFcb(fcb);
		if (x < 0) {
			return null;
		}
		if ((openFiles[x].drv & curROVec) != 0) {
			// TODO: return -2;
			return null;
		}
		return openFiles[x];
	}

	// Returns CP/M File Id, or -1 on error.
	int closeFileFcb(cpmFcb fcb) {
		int x = locFileFcb(fcb);
		if (x < 0) {
			return -1;
		}
		RandomAccessFile fd = openFiles[x].fd;
		if (fd == null) {
			return -1; // or "return x"?
		}
		putFileFcb(fcb, x, (RandomAccessFile)null, (byte)0, (FileLock)null);
		try {
			fd.close();
			return x;
		} catch (Exception ee) {
			return -1;
		}
	}

	// Returns CP/M File Id, or -1 on error (<0).
	int openFileFcb(cpmFcb fcb, byte u) {
		boolean usr0 = false;
		byte d;
		boolean ro = fcb.ATTR_OPRO();
		boolean unlk = fcb.ATTR_UNLK();
		fcb.clearIntfAttrs();
		// FCB might still be open... fix that...
		closeFileFcb(fcb);
		int x = newFileFcb();
		if (x < 0) {
			return -10;	// Open File Limit Exceeded
		}
		fileName = getFileName(fcb);
		d = fcb.getDrv();
		curLogVec |= (1 << d);	// drive must be valid
		pathName = cpmPath(d, u, fileName);
		File fi = new File(pathName);
		//System.err.format("Opening %s\n", pathName);
		String flags = "rw";
		if (!fi.exists() && u > 0) {
			usr0 = true;
			flags = "r";
			pathName = cpmPath(d, 0, fileName);
			fi = new File(pathName);
			if (!fi.canExecute()) { // no "SYS" attribute
				return -1;
			}
		}
		if (!fi.canWrite()) {
			flags = "r";
		}
		if (ro) {
			//System.err.format("File R/O requested: %s\n", pathName);
			flags = "r";
		}
		// NOTE: for this function, file must exist already.
		RandomAccessFile fd =null;
		try {
			fd = new RandomAccessFile(fi, flags);
		} catch (Exception ee) {}
		FileLock flk = null;
		if (fd == null) {
			//System.err.format("Fail to open %s (%d)\n", pathName, errno);
			return -1;
		}
		if ((curCompat & COMPAT_PRO) != 0) {
			// disable record locking...
			fcb.safe = true;
		} else if (!unlk && !flags.equals("r")) {
			//System.err.format("File LOCKED mode: %s\n", pathName);
			try {
				flk = fd.getChannel().tryLock();
				if (flk == null) {
					fd.close();
					return -5;
				}
			} catch (Exception ee) {
				return -5;
			}
			fcb.safe = true;
		}
		putFileFcb(fcb, x, fd, d, flk);
		if (usr0) {
			fcb.SET_USR0();
		}
		if (fi.canExecute()) {
			fcb.SET_SYS();
		}
		if (!fi.canWrite()) {
			fcb.SET_RO();
		}
		fcb.ext = 0;
		long len = fi.length();
		len = (len + 127) / 128;	// num records
		fcb.rc = (byte)(len > 127 ? 128 : (len & 0x7f));
		if ((fcb.cr & 0xff) == 0xff) {
			fcb.cr = (byte)(fi.length() & 0x7f); // byte count
		}
		fcb.s1 = 0;
		fcb.s2 = (byte)0x80;	// flag if close needs to update...
		return x;	// "File ID"
	}

	// Returns CP/M File ID, or -1 on error (<0).
	int makeFileFcb(cpmFcb fcb, byte u) {
		byte d;
		boolean unlk = fcb.ATTR_UNLK();
		fcb.clearIntfAttrs();
		// FCB might still be open... fix that...
		closeFileFcb(fcb);
		int x = newFileFcb();
		if (x < 0) {
			return -10;	// Open File Limit Exceeded
		}
		fileName = getFileName(fcb);
		d = fcb.getDrv();
		curLogVec |= (1 << d);
		if ((curROVec & (1 << d)) != 0) {
			return -2;	// R/O Disk
		}
		pathName = cpmPath(d, u, fileName);
		RandomAccessFile fd = null;
		try {
			fd = new RandomAccessFile(new File(pathName), "rw");
		} catch (Exception ee) {}
		FileLock flk = null;
		if (fd == null) {
			return -1;
		}
		if ((curCompat & COMPAT_PRO) != 0) {
			// disable record locking...
			fcb.safe = true;
		} else if (!unlk) {
			//System.err.format("File LOCKED mode: %s\n", pathName);
			// should never fail...
			try {
				flk = fd.getChannel().lock();
			} catch (Exception ee) {}
			fcb.safe = true;
		}
		putFileFcb(fcb, x, fd, d, flk);
		fcb.ext = 0;
		fcb.rc = 0;
		fcb.s2 = (byte)0x80;	// flag to update on close
		return x;	// "File ID"
	}

	int recLocking(byte[] msgbuf, int start, int len, boolean lock) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		short fid = (short)((msgbuf[dmaadr] & 0xff) | (msgbuf[dmaadr + 1] << 8));
		OpenFile of = getFileFcb(fcb);
		if (of == null) {
			msgbuf[start] = (byte)9;
			return 1;
		}
		if (fid != fcb.fd) {
			msgbuf[start] = (byte)13;
			return 1;
		}
		// TODO: fix this - detect need for record locks...
		if (!fcb.safe) {
			if (!lock) {
				// find matching lock and release...
				for (FileLock flk : of.rlks) {
					if (flk.position() == fcb.rr) {
						try {
							flk.release();
						} catch (Exception ee) {}
						of.rlks.remove(flk);
						break;
					}
				}
			} else {
				FileLock flk = null;
				try {
					flk = of.fd.getChannel().tryLock(fcb.rr, 128, false);
					if (flk == null) {
						msgbuf[start] = (byte)8;
						return 1;
					}
				} catch (Exception ee) {
					msgbuf[start] = (byte)12;
					return 1;
				}
				of.rlks.add(flk);
			}
		}
		msgbuf[start] = (byte)0;
		// TODO: need this? NDOS copies back FCB+RR
		// but if nothing changed...
		//fcb.putIO(msgbuf, fcbadr, true);
		return 37;
	}

	private int getDPB(byte[] msgbuf, int start, int len) {
		curDpb.put(msgbuf, start);
		return curDpb.byteLength;
	}
	private int writeProt(byte[] msgbuf, int start, int len) {
		curROVec |= (1 << (msgbuf[start] & 0x0f));
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int resetDrive(byte[] msgbuf, int start, int len) {
		curROVec &= ~(1 << (msgbuf[start] & 0x0f));
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int getROVec(byte[] msgbuf, int start, int len) {
		msgbuf[start] = (byte)(curROVec & 0x0ff);
		msgbuf[start + 1] = (byte)((curROVec >> 8) & 0x0ff);
		return 2;
	}
	private int getAllocVec(byte[] msgbuf, int start, int len) {
		// TODO: is there anything here?
		Arrays.fill(msgbuf, start, start + 256, (byte)0);
		return 256;
	}
	private int getLoginVec(byte[] msgbuf, int start, int len) {
		msgbuf[start] = (byte)(curLogVec & 0x0ff);
		msgbuf[start + 1] = (byte)((curLogVec >> 8) & 0x0ff);
		return 2;
	}

	private int selectDisk(byte[] msgbuf, int start, int len) {
		byte d = msgbuf[start];
		msgbuf[start] = (byte)0;
		if (curDsk == d) {
			return 1;
		}
		curDsk = d;
		fileName = cpmDrive(d);
		File fi = new File(fileName);
		if (!fi.exists()) {
			//System.err.format("Mkdir %s\n", fileName);
			try {
				fi.mkdirs();
			} catch (Exception ee) {}
		}
		if (!fi.exists()) {
			//System.err.format("Seldisk error (%d) %s\n", errno, fileName);
			curLogVec &= ~(1 << d);
			msgbuf[start] = (byte)255;
			return 1;
		}
		//System.err.format("Seldisk: %s\n", fileName);
		curLogVec |= (1 << d);
		return 1;
	}

	private int openFile(byte[] msgbuf, int start, int len) {
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		// ignore password...
		int rc = openFileFcb(fcb, u);
		if (rc < 0) {
			if (rc < -1) {
				msgbuf[start] = (byte)255;
				msgbuf[start + 1] = (byte)-rc;
				return 2;
			}
			msgbuf[start] = (byte)255; // a.k.a. File Not Found
			return 1;
		} else {
			fcb.putNew(msgbuf, fcbadr, false);
			return 37;
		}
	}

	private int closeFile(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		boolean partial = fcb.ATTR_PC() || (curCompat & COMPAT_PC) != 0;
		fcb.clearIntfAttrs();
		OpenFile of = getFileFcb(fcb);
		if (of == null) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		msgbuf[start] = (byte)0;
		if (fcb.s2 == 0) {
			// special truncate for SUBMIT (CCP)
			long ln = fcb.rc;
			ln *= 128;
			try {
				of.fd.setLength(ln);
			} catch (Exception ee) {
				System.err.format("SUBMIT truncate %d\n", ln);
			}
		}
		int rc = 0;
		if (partial) {
			//System.err.format("Partial close\n");
			try {
				of.fd.getChannel().force(true);
				of.fd.getFD().sync();
				// TODO: will this file ever be closed?
				// could avoid this if we knew no locks were acquired...
				if (of.flk != null) {
					of.flk.release();
					of.flk = null;
				} else {
					// TODO: cannot be both?
					for (FileLock flk : of.rlks) {
						flk.release();
					}
					of.rlks.removeAllElements();
				}
			} catch (Exception ee) {
				// TODO: force actual close()?
				System.err.format("Partial close error\n");
				rc = -1;
			}
		} else {
			rc = closeFileFcb(fcb);
		}
		if (rc < 0) {
			msgbuf[start] = (byte)255;
			return 1;
		} else {
			fcb.putNew(msgbuf, fcbadr, false);
			return 37;
		}
	}

	private int searchNext(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start + 1] & 0x1f;
		if (curSearch.full) {
			int dc = curSearch.iter & 0x03;
			if (curSearch.iter < curSearch.lastit) {
				++curSearch.iter;
				msgbuf[start] = (byte)dc;
				return 1;
			}
			if (curSearch.iter < curSearch.endit) {
				msgbuf[start] = (byte)255;
				return 1;
			}
			// force iter to next record
			curSearch.iter = (curSearch.iter + 0x03) & ~0x03;
		}
		String name = doSearch(curSearch);
		if (name == null) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		if (curSearch.full) {
			// curSearch.iter is 0, must be. We know at least one exists.
			// fill the entire dma buf, if possible.
			msgbuf[start] = (byte)fullSearch(msgbuf, start + 1, curSearch, name);
			return 129;
		} else {
			msgbuf[start] = (byte)copyOutSearch(msgbuf, start + 1, name);
			return 33;
		}
	}

	// CP/M 3 DIR uses fcb.drv == '?' to do full listing,
	// and older CP/M STAT uses fcb.ext == '?'.
	// However, fcb.drv == '?' is documented in older CP/M
	// so we cannot assume CP/M 3 based on that.
	private int searchFirst(byte[] msgbuf, int start, int len) {
		//byte d = msgbuf[start] & 0x0f; // should be curDsk...
		byte u = (byte)(msgbuf[start + 1] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		if ((fcb.drv & 0x7f) == '?') {
			// This means return every dir entry...
			//System.err.format("Search with drv = '?'\n");
			fcb.name = "???????????";
			fcb.ext = (byte)'?';
		}
		String f = startSearch(fcb, curSearch, u);
		curLogVec |= (1 << curSearch.drv);
		if (f == null) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		if (curSearch.full) {
			// curSearch.iter is 0, must be. We know at least one exists.
			// fill the entire dma buf, if possible.
			msgbuf[start] = (byte)fullSearch(msgbuf, start + 1, curSearch, f);
			return 129;
		} else {
			msgbuf[start] = (byte)copyOutSearch(msgbuf, start + 1, f);
			return 33;
		}
	}

	// TODO: if this is an open file, then close it?
	private int deleteFile(byte[] msgbuf, int start, int len) {
		cpmSearch era;
		byte u = (byte)(msgbuf[start] & 0x1f);
		msgbuf[start] = (byte)0;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		boolean delx = fcb.ATTR_DELX();
		fcb.clearIntfAttrs();
		if (fcb.drv == '?') {
			msgbuf[start] = (byte)255;
			msgbuf[start + 1] = (byte)9;
			return 2;
		}
		byte d = fcb.getDrv();
		curLogVec |= (1 << d);
		if ((curROVec & (1 << d)) != 0) {
			msgbuf[start] = (byte)255;
			msgbuf[start + 1] = (byte)2;
			return 2;
		}
		if (delx) {
			// no XFCBs in this implementation,
			// do not remove timestamps.
			return 1;
		}
		// TODO: change ext in msgbuf?
		fcb.ext = 0;	// in case it was '?'
		// We are supposed to remove NOTHING if ANY matching file is R/O...
		era = new cpmSearch();
		errno = 0;
		String name = startSearch(fcb, era, u);
		int rc = 0;
		while (name != null) {
			if (!era.rw) {
				rc = -1;
				break;
			}
			++rc;
			name = doSearch(era);
		}
		endSearch(era);
		if (rc < 0) {
			msgbuf[start] = (byte)255;
			msgbuf[start + 1] = (byte)3;
			return 2;
		}
		if (rc == 0) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		// Start search over, remove files this time...
		errno = 0;
		name = startSearch(fcb, era, u);
		rc = 0;
		while (name != null) {
			++rc;
			try {
				File fi = new File(cpmPathFound(era.find));
				fi.delete();
			} catch (Exception ee) {}
			name = doSearch(era);
		}
		endSearch(era);
		if (rc == 0) {
			// Probably can't happen since we already checked before.
			msgbuf[start] = (byte)255;
			return 1;
		}
		return 1;
	}

	private int readSeq(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		OpenFile of = getFileFcb(fcb);
		if (of == null) {
			msgbuf[start] = (byte)9;
			return 1;
		}
		int rc = 0;
		try {
			if ((fcb.ext == 0 && fcb.cr == 0) ||
					fcb.cr != fcb.s1) {
				long ln = fcb.cr;
				ln += fcb.ext * 128;
				ln *= 128;
				of.fd.seek(ln);
			}
			rc = of.fd.read(msgbuf, dmaadr, 128);
			if (rc < 0) {
				rc = 0;
			}
		} catch (Exception ee) {
			rc = -1;
		}
		if ((fcb.cr & 0xff) > 127) {
			fcb.cr = 0; // cr
			++fcb.ext;
			fcb.ext &= 0x1f;
		}
		if (rc < 0) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		if (rc == 0) {
			msgbuf[start] = (byte)1;
			return 1;
		}
		++fcb.cr;
		fcb.s1 = fcb.cr;
		fcb.putIO(msgbuf, fcbadr, false);
		// fill any partial "sector" with Ctrl-Z, in case it's text.
		Arrays.fill(msgbuf, dmaadr + rc, dmaadr + 128, (byte)0x1a);
		// detect media change?
		return 165;
	}

	private int writeSeq(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		OpenFile of = getFileWrFcb(fcb);
		if (of == null) {
			// TODO: additional errno info...
			//if (fd < -1) {
			//	msgbuf[start] = (byte)255;
			//	msgbuf[start] = (byte)-fd;
			//	return 2;
			//} else {
				msgbuf[start] = (byte)9;
				return 1;
			//}
		}
		int rc = 0;
		try {
			if ((fcb.ext == 0 && fcb.cr == 0) ||
					fcb.cr != fcb.s1) {
				long ln = fcb.cr;
				ln += fcb.ext * 128;
				ln *= 128;
				of.fd.seek(ln);
			}
			of.fd.write(msgbuf, dmaadr, 128);
			rc = 128;
		} catch (Exception ee) {
			rc = -1;
		}
		if ((fcb.cr & 0xff) > 127) {
			fcb.cr = 0;
			fcb.rc = 0;
			++fcb.ext;
			fcb.ext &= 0x1f;
		}
		++fcb.cr;
		fcb.s1 = fcb.cr;
		if ((fcb.rc & 0xff) < (fcb.cr & 0xff)) {
			fcb.rc = fcb.cr;
		}
		if (rc < 0) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		if (rc == 0) {
			msgbuf[start] = (byte)1;
			return 1;
		}
		// fcb.s2 = 0; // don't need this here...
		// detect media change?
		fcb.putIO(msgbuf, fcbadr, false);
		return 37;
	}

	// File is open on successful return.
	private int createFile(byte[] msgbuf, int start, int len) {
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		int rc = makeFileFcb(fcb, u);
		if (rc < 0) {
			if (rc < -1) {
				msgbuf[start] = (byte)255;
				msgbuf[start + 1] = (byte)-rc;
				return 2;
			}
			msgbuf[start] = (byte)255;
			return 1;
		}
		fcb.putNew(msgbuf, fcbadr, false);
		return 37;
	}

	private int renameFile(byte[] msgbuf, int start, int len) {
		String newn;
		byte d;
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		fileName = getFileName(fcb);
		d = fcb.getDrv();
		curLogVec |= (1 << d);
		if ((curROVec & (1 << d)) != 0) {
			msgbuf[start] = (byte)255;
			msgbuf[start + 1] = (byte)2;
			return 2;
		}
		pathName = cpmPath(d, u, fileName);
		File fi = new File(pathName);
		fcb = new cpmFcb(msgbuf, fcbadr + 16);
		fileName = getFileName(fcb);
		newn = cpmPath(d, u, fileName);
		try {
			fi.renameTo(new File(newn));
		} catch (Exception ee) {
			// todo: decode errno...
			msgbuf[start] = (byte)255;
			return 1;
		}
		return 1;
	}

	private int readRand(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		OpenFile of = getFileFcb(fcb);
		if (of == null) {
			msgbuf[start] = (byte)9;
			return 1;
		}
		seekFile(fcb, of);
		int rc = 0;
		try {
			rc = of.fd.read(msgbuf, dmaadr, 128);
			if (rc < 0) {
				rc = 0;
			}
		} catch (Exception ee) {
			rc = -1;
		}
		seekFile(fcb, of);
		if (rc < 0) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		if (rc == 0) {
			msgbuf[start] = (byte)1;
			return 1;
		}
		Arrays.fill(msgbuf, dmaadr + rc, dmaadr + 128, (byte)0x1a);
		// detect media change?
		fcb.putIO(msgbuf, fcbadr, true);
		return 37 + 128;
	}

	private int writeRand(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		OpenFile of = getFileWrFcb(fcb);
		if (of == null) {
			// TODO: extra error decode
			//if (fd < -1) {
			//	msgbuf[start] = (byte)255;
			//	msgbuf[start] = (byte)-fd;
			//	return 2;
			//} else {
				msgbuf[start] = (byte)9;
				return 1;
			//}
		}
		seekFile(fcb, of);
		int rc = 0;
		try {
			of.fd.write(msgbuf, dmaadr, 128);
			rc = 128;
		} catch (Exception ee) {
			rc = -1;
		}
		seekFile(fcb, of);
		if (rc < 0) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		if (rc == 0) {
			msgbuf[start] = (byte)1;
			return 1;
		}
		// detect media change?
		fcb.putIO(msgbuf, fcbadr, true);
		return 37;
	}

	private int setRandRec(byte[] msgbuf, int start, int len) {
		//byte u = msgbuf[start] & 0x1f;
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		OpenFile of = getFileFcb(fcb);
		if (of == null) {
			msgbuf[start] = (byte)9;
			return 1;
		}
		long r = 0;
		try {
			r = of.fd.getFilePointer();
		} catch (Exception ee) {}
		if (r > 0x03ffff * 128) {
			r = 0x03ffff;
		} else {
			r = (r + 127) / 128;
		}
		fcb.rr = (int)r;
		fcb.putIO(msgbuf, fcbadr, true);
		return 37;
	}

	private int compFileSize(byte[] msgbuf, int start, int len) {
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		long r = 0;
		OpenFile of = getFileFcb(fcb);
		if (of != null) {
			try {
				r = of.fd.length();
			} catch (Exception ee) {}
		} else {
			fileName = getFileName(fcb);
			pathName = cpmPath(fcb.getDrv(), u, fileName);
			File fi = new File(pathName);
			if (!fi.exists()) {
				msgbuf[start] = (byte)255;
				return 1;
			}
			r = fi.length();
		}
		if (r > 0x03ffff * 128) {
			r = 0x03ffff;
		} else {
			r = (r + 127) / 128;
		}
		msgbuf[fcbadr + 33] = (byte)(r & 0x0ff);
		r >>= 8;
		msgbuf[fcbadr + 34] = (byte)(r & 0x0ff);
		r >>= 8;
		msgbuf[fcbadr + 35] = (byte)(r & 0x003);
		fcb.putIO(msgbuf, fcbadr, true);
		return 37;
	}

	private int setFileAttrs(byte[] msgbuf, int start, int len) {
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		int bc = fcb.cr & 0x7f; // 0 means 128
		if (bc == 0) {
			bc = 128;
		}
		// file must not be open?
		OpenFile of = getFileFcb(fcb);
		if (of != null) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)0; // TODO: more error codes?
			return 2;
		}
		fileName = getFileName(fcb);
		pathName = cpmPath(fcb.getDrv(), u, fileName);
		File fi = new File(pathName);
		if (!fi.exists()) {
			msgbuf[start] = (byte)255;
			return 1;
		}
		// pathName should now be set, since file was not open
		if (fcb.ATTR_BC()) {
			// Set byte count, essentially truncate last 128-byte record.
			long r = fi.length();
			if (r > bc) {
				r = ((r + 127) & ~0x7fL) - 128 + bc;
				try {
					RandomAccessFile fd = new RandomAccessFile(pathName, "rw");
					fd.setLength(r);
					fd.close();
				} catch (Exception ee) {
					msgbuf[start] = (byte)0xff;
					msgbuf[start + 1] = (byte)1; // probably a R/O file, but not an option.
					return 2;
				}
			}
		}
		boolean err = false;
		boolean sys = fi.canExecute();
		boolean nsys = fcb.ATTR_SYS();
		if (nsys != sys) {
			if (!fi.setExecutable(nsys)) {
				err = true;
			}
		}
		boolean ro = !fi.canWrite();
		boolean nro = fcb.ATTR_RO();
		if (nro != ro) {
			if (!fi.setWritable(!nro)) {
				err = true;
			}
		}
		if (err) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)1; // probably a R/O file, but not an option.
			return 2;
		}
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int accessDrive(byte[] msgbuf, int start, int len) {
		int vec = (msgbuf[start] & 0xff) | ((msgbuf[start + 1] << 8) & 0xff);
		curLogVec |= vec;
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int freeDrive(byte[] msgbuf, int start, int len) {
		int vec = (msgbuf[start] & 0xff) | ((msgbuf[start + 1] << 8) & 0xff);
		//System.err.format("FREE DRIVE(s) %04x %04x\n", vec, curLogVec);
		// vec==curLogVec: warm boot... via CP/Net 1.2
		closeAll(vec);
		curLogVec &= ~vec;
		curROVec &= ~vec;
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int writeRandZF(byte[] msgbuf, int start, int len) {
		// Linux zero-fills for us...
		return writeRand(msgbuf, start, len);
	}
	private int lockRec(byte[] msgbuf, int start, int len) {
		return recLocking(msgbuf, start, len, true);
	}
	private int unlockRec(byte[] msgbuf, int start, int len) {
		return recLocking(msgbuf, start, len, false);
	}
	private int getFreeSp(byte[] msgbuf, int start, int len) {
		int cap = ((curDpb.dsm + 1) << curDpb.bsh);
		//System.err.format("DISK FREE SPACE %06x\n", cap);
		msgbuf[start] = (byte)(cap & 0xff);
		msgbuf[start + 1] = (byte)((cap >> 8) & 0xff);
		msgbuf[start + 2] = (byte)((cap >> 16) & 0xff);
		return 3;
	}
	private int flushBuf(byte[] msgbuf, int start, int len) {
		int x;
		for (x = 0; x < DEF_NFILE; ++x) {
			if (openFiles[x].fd != null) {
				try {
					openFiles[x].fd.getChannel().force(true);
					openFiles[x].fd.getFD().sync();
				} catch (Exception ee) {}
			}
		}
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int freeBlks(byte[] msgbuf, int start, int len) {
		// CP/Net 3 Warm Boot indicator
		closeAll(0xffff);
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int truncFile(byte[] msgbuf, int start, int len) {
		int rc;
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		OpenFile of = getFileWrFcb(fcb);
		if (of != null) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)0; // TODO: more error codes?
			return 2;
		}
		byte d = fcb.getDrv();
		fileName = getFileName(fcb);
		pathName = cpmPath(d, u, fileName);
		File fi = new File(pathName);
		if (!fi.canWrite()) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)(!fi.exists() ? 0 : 3);
			return 2;
		}
		long r = fcb.rr;
		if (r > fi.length()) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)0x00;
			return 2;
		}
		try {
			RandomAccessFile fd = new RandomAccessFile(fi, "rw");
			fd.setLength(r);
			fd.close();
		} catch (Exception ee) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)0xff;
			return 2;
		}
		// TODO: need to update ext,rc,cr? file is not open... as far as we know.
		return 37;
	}
	private int login(byte[] msgbuf, int start, int len) {
		// Might already be logged in, must handle/ignore
		if (!addClient(clientId)) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)0x0c;
			return 2;
		}
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int logoff(byte[] msgbuf, int start, int len) {
		rmClient(clientId);
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int setCompAttrs(byte[] msgbuf, int start, int len) {
		curCompat = msgbuf[start] & (COMPAT_PRO | COMPAT_PC); // only honor a couple bits...
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int getServCfg(byte[] msgbuf, int start, int len) {
		cfgTab.put(msgbuf, start);
		return cfgTab.byteLength;
	}
	private int setDefPwd(byte[] msgbuf, int start, int len) {
		msgbuf[start] = (byte)0;
		return 1;
	}
	private int getDirLab(byte[] msgbuf, int start, int len) {
		// CP/M 3 DIR.COM uses full-search, not this, to
		// detect timestamps.
		msgbuf[start] = dirMode;
		return 1;
	}

	private BasicFileAttributes getAttrs(String name) {
		Path path = FileSystems.getDefault().getPath(name);
		try {
			return Files.readAttributes(path, BasicFileAttributes.class);
		} catch (Exception ee) {
			return null;
		}
	}

	// Get timestamp for given file
	private int readFStamps(byte[] msgbuf, int start, int len) {
		byte u = (byte)(msgbuf[start] & 0x1f);
		cpmFcb fcb = new cpmFcb(msgbuf, fcbadr);
		msgbuf[start] = (byte)0;
		byte d = fcb.getDrv();
		fileName = getFileName(fcb);
		pathName = cpmPath(d, u, fileName);
		File fi = new File(pathName);
		if (!fi.exists()) {
			msgbuf[start] = (byte)0xff;
			msgbuf[start + 1] = (byte)0x00;
			return 2;
		}
		msgbuf[fcbadr + 12] = (byte)0; // ext: no passwords
		BasicFileAttributes attrs = getAttrs(pathName);
		if (attrs != null) {
			unix2cpmdate(attrs.lastAccessTime().toMillis(),
					msgbuf, start + 25, false);
			unix2cpmdate(attrs.lastModifiedTime().toMillis(),
					msgbuf, start + 29, false);
		}
		//unix2cpmdate(fi.lastAccess(), msgbuf, start + 25, false);
		//unix2cpmdate(fi.lastModified(), msgbuf, start + 29, false);
		msgbuf[fcbadr + 32] = (byte)0; // cr
		return 37;
	}
	private int getTime(byte[] msgbuf, int start, int len) {
		Date now = new Date();
		unix2cpmdate(now.getTime(), msgbuf, start, true);
		//System.err.format("getTime: %04x %02x %02x %02x\n",
		//	(msgbuf[start] & 0xff) |
		//		((msgbuf[start + 1] & 0xff) << 8),
		//	msgbuf[start + 2] & 0xff,
		//	msgbuf[start + 3] & 0xff,
		//	msgbuf[start + 4] & 0xff);
		return 5;
	}

	private int listOut(byte[] msgbuf, int start, int len) {
		int lid = msgbuf[start] & 0xff;
		if (lid >= 16 || lsts[lid] == null) {
			msgbuf[start] = (byte)0;
			return 1;
		}
		// TODO: scan buffer for 0xff to see if release is needed?
		// Assumption is that last byte will be 0xff iff ENDLIST.
		// If any characters follow the 0xff, the user is still
		// printing something (although could cause starvation).
		int lstLen = len - cpnMsg - 1;
		boolean acquire = (lstLen > 1 || (msgbuf[start + 1] & 0xff) != 0xff);
		// last byte is at msgbuf[start + 1 + lstLen - 1]
		boolean release = ((msgbuf[start + lstLen] & 0xff) == 0xff);
		if (acquire && !acquireLst(clientId, lid)) {
			// TODO: log a message, someplace, to help user
			// understand why their output did not print.
			// By default, CP/M will never recognize an error
			// from LST: output. Maybe needs to be caught in
			// the client, so it is logged closer to user?

			// The documentation is rather vague about this...
			// Could implement a spooler, too...
			// Or just have an instance of Diable630 for each
			// client and keep filenames unique.
			msgbuf[start] = (byte)0xff;
			msgbuf[start] = (byte)0x0c;
			return 2;
		}
		// hopefully this doesn't sleep...
		try {
			lsts[lid].write(msgbuf, start + 1, len - 1);
		} catch (Exception ee) {}
		if (release) {
			releaseLst(clientId, lid);
		}
		msgbuf[start] = (byte)0;
		return 1;
	}

}
