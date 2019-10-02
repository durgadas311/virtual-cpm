// Copyright (c) 2016 Douglas Miller <durgadas311@gmail.com>

import java.util.Arrays;
import java.util.Vector;
import java.io.*;
import java.net.*;
import java.util.Properties;

public class CpnetSocketClient implements NetworkServer, Runnable {
	private int clientId = 0xff;
	private InetAddress ia = null;
	private int pt = 0;
	private int serverId = 0xff;
	private Socket server = null;
	private InputStream in;
	private OutputStream out;
	private byte[] buf = new byte[NetworkServer.mstart + 256];
	private byte[] respBuf = null;
	private int bufoff = 0;
	private int buflen = 0; // total size, once CP/Net header received
	private boolean magNet = false; // use MAGNet protocol
	private boolean quit = false;
	private NetworkListener lstn;

	public CpnetSocketClient(Properties props,
			Vector<String> args, int srvId, int cltId, NetworkListener lstn) {
		clientId = cltId;
		serverId = srvId;
		this.lstn = lstn;
		String pfx = String.format("cpnetserver%02x_", srvId);
		String s = props.getProperty(pfx + "host");
		if (s != null) {
			setInetAddr(s);
		}
		s = props.getProperty(pfx + "port");
		if (s != null) {
			setPort(s);
		}
		// args[0] is our class name, like argv[0] in C progs main().
		// Usage: Socket <host> <port>
		if (args.size() > 1) {
			String h = args.get(1);
			setInetAddr(h);
			if (args.size() > 2) {
				setPort(args.get(2));
			}
		}
		if (ia == null || pt <= 0) {
			System.err.format("No valid host/port\n");
			return;
		}
		Thread t = new Thread(this);
		t.start();
	}

	private void setInetAddr(String h) {
		try {
			if (h.length() == 0 || h.equals("localhost")) {
				ia = InetAddress.getByName(null); // "localhost"
			} else {
				ia = InetAddress.getByName(h);
			}
		} catch (Exception ee) {}
	}

	private void setPort(String p) {
		try {
			pt = Integer.valueOf(p);
		} catch (Exception ee) {}
	}

	private void dispose() {
		if (server != null) {
			try {
				server.close();
			} catch (Exception eee) {}
			server = null;
			if (lstn != null) {
				lstn.dropNode(serverId);
			}
		}
	}

	private boolean isCpnet(byte[] buf) {
		return (buf[NetworkServer.mcode] & 0xfe) == 0;
	}

	private byte[] makeError(byte[] msgbuf, int len) {
		if (isCpnet(msgbuf)) {
			len = 2;
			ServerDispatch.putCode(msgbuf, 0x01);
			ServerDispatch.putBC(msgbuf, len + NetworkServer.mhdrlen);
			msgbuf[NetworkServer.mstart] = (byte)0xff;
			msgbuf[NetworkServer.mstart + 1] = (byte)0x0c;
			msgbuf[NetworkServer.msize] = (byte)(len - 1);
			msgbuf[NetworkServer.mcode] |= 1;
			byte src = msgbuf[NetworkServer.msid];
			msgbuf[NetworkServer.msid] = msgbuf[NetworkServer.mdid];
			msgbuf[NetworkServer.mdid] = src;
		} else {
			ServerDispatch.putCode(msgbuf, 0x38);
			ServerDispatch.putBC(msgbuf, 0);
			ServerDispatch.putDE(msgbuf, 1);
		}
		return msgbuf;
	}

	public synchronized byte[] checkRecvMsg(byte clientId) {
		if (server == null) {
			// TODO: requires error packet?
			return null;
		}
		if (buflen > 0 && bufoff >= buflen) {
			// TODO: how to tell if overrun? n > buflen...
			// got whole message, return it...
			if (respBuf.equals(buf)) {
				respBuf = Arrays.copyOf(buf, buflen);
			}
			byte[] ret = respBuf;
			// reset for next message. Our thread is in read(),
			// but buf[] cannot be changing as no message
			// will appear until we send another.
			respBuf = buf;
			bufoff = 0;
			buflen = 0;
			// TODO: Only need to return > 0, at least for now...
			return ret;
		}
		return null;
	}

	public byte[] sendMsg(byte[] msgbuf, int len) {
		if (server == null) {
			// TODO: try again right now?
			return makeError(msgbuf, len);
		}
		try {
			int cd = ServerDispatch.getCode(msgbuf);
			// Mark message as "MAGnet format"
			ServerDispatch.putCode(msgbuf, cd | 0x80);
			out.write(msgbuf, 0, len);
			out.flush();
			ServerDispatch.putCode(msgbuf, cd);
		} catch (Exception ee) {
			// socket is dead? start connection over?
			// The thread should always notice this before we can,
			// so let the thread be exclusive owner of disposal.
			return makeError(msgbuf, len);
		}
		// message was sent, tell caller to wait for response
		return null;
	}

	private synchronized void handlePacket(byte[] rbuf, int n) {
		// TODO: memory ordering concerns?
		System.arraycopy(rbuf, 0, respBuf, bufoff, n);
		boolean payload = bufoff < NetworkServer.mpayload;
		bufoff += n;
		payload = payload && (bufoff >= NetworkServer.mpayload);
		if (payload) {
			int cd = ServerDispatch.getCode(respBuf);
			buflen = NetworkServer.mpayload +
				ServerDispatch.getBC(respBuf);
			if (cd == 0x10) { // the only problem case
				byte[] nb = new byte[buflen];
				System.arraycopy(respBuf, 0, nb, 0, bufoff);
				respBuf = nb;
			} else if (cd == 0x30) { // the only exception
				buflen = NetworkServer.mpayload + 65;
			}
		}
	}

	public void shutdown() {
		quit = true;
		dispose(); // TODO: is this the right action?
	}

	public void run() {
		boolean initial = true;
		byte[] rbuf = new byte[NetworkServer.mstart + 256];
		while (!quit) {
			if (server == null) {
				bufoff = 0;
				buflen = 0;
				respBuf = buf;
				try {
					if (!initial) {
						Thread.sleep(10000); // TODO: how much to sleep
					}
					initial = false;
					server = new Socket(ia, pt);
					in = server.getInputStream();
					out = server.getOutputStream();
					System.err.format("Restored connection to server %02x\n", serverId);
					if (lstn != null) {
						// Could assume it's a server, but...
						// unless we actually communicate here
						// we don't know (yet). Could perform a
						// "D0" token exchange and find out...
						// NetworkListener could do that, but
						// not from this context...
						lstn.addNode(serverId, NetworkServer.tunknown);
					}
				} catch (Exception ee) {
					dispose();
				}
			}
			if (server != null) {
				int n = 0;
				try {
					// This could be the start of a
					// VERY LONG boot response... need to
					// manage buffer...
					n = in.read(rbuf);
					// should never be EOF...
					if (n < 0) {
						dispose();
						continue;
					}
					handlePacket(rbuf, n);
				} catch (Exception ee) {
					// socket is dead? start connection over?
					dispose();
					continue;
				}
			}
		}
	}
}
