// Copyright (c) 2016 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;

public class ServerDispatch {
	private Map<Integer, NetworkServer> servers;

///
/// For reference, standard CP/Net message header is:
/// +0  format code (00 = CP/Net send, 01 = response)
/// +1  dest node ID (server or this client, depending on direction)
/// +2  src node ID (this client or server, '')
/// +3  CP/Net, MP/M, CP/M BDOS function number
/// +4  msg size - 1 (00 = 1, FF = 256)
/// +5...   message body
///

	protected ServerDispatch() {
		servers = new HashMap<Integer, NetworkServer>();
	}

	protected void addServer(int serverId, NetworkServer server) {
		servers.put(serverId, server);
	}

	// Returns HID (host ID).
	protected int install_ServerDispatch(Properties props,
			String prefix, int cid, int hid, int max, NetworkListener lstn) {
		String s;
		boolean[] nodes = new boolean[max];
		Arrays.fill(nodes, false);
		nodes[cid] = true;
		prefix += "server";
		int pflen = prefix.length();
		CpnetSocketClient ssrv = null;
		for (String prop : props.stringPropertyNames()) {
			// property syntax: cpnetdevice_server## = ClassId [args...]
			// where '##' is serverId in hex.
			if (prop.startsWith(prefix)) {
				int sid;
				try {
					sid = Integer.valueOf(prop.substring(pflen), 16);
				} catch (Exception ee) {
					System.err.format("Invalid Server property: skipping %s\n", prop);
					continue;
				}
				// TODO: check for conflicts/duplicates?
				if (sid < 0 || sid >= max) {
					System.err.format("Invalid Server ID %02x: skipping %s\n", sid, prop);
					continue;
				}
				if (nodes[sid]) {
					System.err.format("Duplicate Server ID %02x: skipping %s\n", sid, prop);
					continue;
				}
				nodes[sid] = true;
				s = props.getProperty(prop); // can't be null?
				System.err.format("Server %02x: %s\n", sid, s);
				Vector<String> args = new Vector<String>(
					Arrays.asList(s.split("\\s")));
				if (args.get(0).equalsIgnoreCase("HostFileBdos")) {
					// In this environment, only one client is allowed.
					// TODO: make temp drive configurable...
					String pfx = String.format("hostfilebdos%02x", sid);
					HostFileBdos.initCfg('P', (byte)sid, 1, null);
					HostFileBdos.initLsts(props, pfx);
					NetworkServer nws = new HostFileBdos(props, pfx, args, cid);
					addServer(sid, nws);
					if (lstn != null) {
						lstn.addNode(sid, NetworkServer.tfileserver);
					}
					if (hid == -1) {
						hid = sid;
					}

				} else if (args.get(0).equalsIgnoreCase("Socket")) {
					// One instance of SocketServer could handle
					// multiple/all servers... But, each server
					// instance is so small that it makes no sense.
					NetworkServer nws = new CpnetSocketClient(props,
						args, sid, cid, lstn);
					addServer(sid, nws);
				}
			}
		}
		return hid;
	}

	public static int getCode(byte[] buf) {
		return buf[NetworkServer.mtype] & 0xff;
	}

	public static int getBC(byte[] buf) {
		return (buf[NetworkServer.mBC] & 0xff) |
			((buf[NetworkServer.mBCh] & 0xff) << 8);
	}

	public static int getDE(byte[] buf) {
		return (buf[NetworkServer.mDE] & 0xff) |
			((buf[NetworkServer.mDEh] & 0xff) << 8);
	}

	public static int getHL(byte[] buf) {
		return (buf[NetworkServer.mHL] & 0xff) |
			((buf[NetworkServer.mHLh] & 0xff) << 8);
	}

	public static void putCode(byte[] buf, int code) {
		buf[NetworkServer.mtype] = (byte)code;
	}

	public static void putBC(byte[] buf, int bc) {
		buf[NetworkServer.mBC] = (byte)bc;
		buf[NetworkServer.mBCh] = (byte)(bc >> 8);
	}

	public static void putDE(byte[] buf, int de) {
		buf[NetworkServer.mDE] = (byte)de;
		buf[NetworkServer.mDEh] = (byte)(de >> 8);
	}

	public static void putHL(byte[] buf, int hl) {
		buf[NetworkServer.mHL] = (byte)hl;
		buf[NetworkServer.mHLh] = (byte)(hl >> 8);
	}

	protected void shutdown() {
		for (NetworkServer nws : servers.values()) {
			nws.shutdown();
		}
		servers.clear();
	}

	protected byte[] checkRecvMsg(byte clientId) {
		byte[] msg = null;
		for (NetworkServer nws : servers.values()) {
			msg = nws.checkRecvMsg(clientId);
			// TODO: implement round-robin to avoid starvation.
			// although, client only does one message at a time anyway,
			// and CP/NET does not allow for unsolicited messages.
			if (msg != null) {
				// sender must have filled in message, incl size.
				break;
			}
		}
		return msg;
	}

	// All messages passed here must have BC set to length and D set to dest.
	protected byte[] sendMsg(byte[] msgbuf, int len) {
		// *might* re-use msgbuf for response...
		int did = msgbuf[NetworkServer.mDEh] & 0xff;
		// TODO: override 'len' with getBC()?
		NetworkServer nws = servers.get(did);

		if (nws == null) {
			System.err.format("Attempted send to null server %02x\n", did);
			putCode(msgbuf, 0xd6);
			putBC(msgbuf, 1);
			return msgbuf;
		}
		//System.err.format("Message: %02x %02x %02x %02x %02x %02x %02x : %02x\n",
		//	msgbuf[0], msgbuf[1], msgbuf[2], msgbuf[3],
		//	msgbuf[4], msgbuf[5], msgbuf[6], msgbuf[7]);
		byte[] resp = nws.sendMsg(msgbuf, len);
		// TODO: any post-processing of 'resp'?
		return resp;
	}
}
