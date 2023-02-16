// Copyright (c) 2020 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.io.*;
import java.lang.reflect.Constructor;
import java.util.Properties;

public class CpnetServer {

	public CpnetServerConfig cfgTab;
	public OutputStream[] lsts;
	public int[] lstCid;
	public String dir = null;
	public String[] dirs;
	public String home;
	public String cwd;

	public CpnetServer(Properties props, String prefix,
			char tmp, byte sid, int max, String dir) {
		cfgTab = new CpnetServerConfig();
		lsts = new OutputStream[16];
		lstCid = new int[16];
		dirs = new String[16];
		Arrays.fill(lsts, null);
		Arrays.fill(lstCid, 0xff);

		cfgTab.tmp = (byte)((tmp - 'A') & 0x0f);
		cfgTab.sts = (byte)0b00010000; // "ready"
		cfgTab.id = sid;
		cfgTab.max = (byte)max;
		this.dir = dir;

		home = System.getProperty("user.home");
		cwd = System.getProperty("user.dir");
		String s;
		// See if individual drive paths are specified...
		for (int x = 0; x < 16; ++x) {
			String p = String.format("%s_drive_%c", prefix, (char)('a' + x));
			s = props.getProperty(p);
			if (s == null || s.length() == 0) {
				continue;
			}
			s = expandDir(s);
			File f = new File(s);
			if (!f.exists()) {
				try {
					f.mkdirs();
				} catch (Exception ee) {}
			}
			if (!f.exists() || !f.isDirectory()) {
				System.err.format("HostFileBdos invalid path in %s: %s\n",
					p, s);
				continue;
			}
			dirs[x] = s;
		}

		String pfx = prefix;
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

	public String expandDir(String s) {
		if (s.startsWith("${PWD}")) {
			return s.replaceFirst("\\$\\{PWD\\}", cwd);
		} else if (s.startsWith("${HOME}")) {
			return s.replaceFirst("\\$\\{HOME\\}", home);
		}
		return s;
	}

	private void initLst(Properties props, int lid, String s) {
		if (s.charAt(0) == '>') { // redirect to file
			attachFile(lid, s.substring(1));
		} else if (s.charAt(0) == '|') { // pipe to program
			attachPipe(lid, s.substring(1));
		} else {
			attachClass(props, lid, s);
		}
	}

	private void attachFile(int lid, String s) {
		// TODO: allow parameters? Allow spaces? APPEND at least?
		String[] args = s.split("\\s");
		try {
			lsts[lid] = new FileOutputStream(args[0]);
		} catch (Exception ee) {
			System.err.format("Invalid file in attachment: %s\n", s);
			return;
		}
	}

	private void attachPipe(int lid, String s) {
		System.err.format("Pipe attachments not yet implemented: %s\n", s);
	}

	private void attachClass(Properties props, int lid, String s) {
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

	public synchronized boolean acquireLst(int cid, int lst) {
		// TODO: lock after checking max?
		if (cfgTab.max == 1) {
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

	public synchronized void releaseLst(int cid, int lst) {
		// TODO: confirm we own it?
		lstCid[lst] = 0xff;
	}

	public synchronized boolean addClient(int cid) {
		int x;
		if (cfgTab.cur >= cfgTab.max) {
			return false;
		}
		int y = 16;
		for (x = 0; x < 16; ++x) {
			if ((cfgTab.rid[x] & 0xff) == cid) {
				return true;
			}
			if ((cfgTab.vec & (1 << x)) == 0) {
				if (y >= 16) {
					y = x;
				}
			}
		}
		if (y >= 16) {
			return false;	// should not happen
		}
		++cfgTab.cur;
		cfgTab.vec |= (1 << y);
		cfgTab.rid[y] = (byte)cid;
		return true;
	}

	public synchronized boolean chkClient(int cid) {
		int x;
		for (x = 0; x < 16; ++x) {
			if ((cfgTab.rid[x] & 0xff) == cid) {
				return true;
			}
		}
		return false;
	}

	public synchronized int rmClient(int cid) {
		int x;
		for (x = 0; x < 16; ++x) {
			if ((cfgTab.rid[x] & 0xff) == cid) {
				break;
			}
		}
		if (x < 16) {
			--cfgTab.cur;
			cfgTab.vec &= ~(1 << x);
			cfgTab.rid[x] = (byte)0xff;
			return 0;
		}
		return -1;
	}

	public synchronized void shutdown(int cid) {
		for (int x = 0; x < lstCid.length; ++x) {
			if (lstCid[x] == cid) {
				releaseLst(cid, x);
			}
		}
		rmClient(cid);
	}
}
