// Copyright (c) 2016 Douglas Miller <durgadas311@gmail.com>

public interface NetworkServer {
	// All messages are prefixed by a 7-byte header
	//         Sent                           Reply
	// Boot    B1 00 00 ss dd xx xx           B0 nl nh ee xx al ah c1..cn
	// CP/NET  C1 nl nh ss dd xx xx m1..mn    C0 nl nh xx xx xx xx m1..mn
	// Status  D1 xx xx xx xx xx xx           D0 id st xx xx xx xx t1..t65
	// Send Status * (Both B1 and C1)         D6 ee xx xx xx xx xx
	//
	// TOKEN Exchange (internal):
	//         D0 id st xx xx xx xx t1..t65   D0 id st xx xx xx xx t1..t65
	//      Recipient merges tables and sends result back. Essentially,
	//      t1..t65 in sent message is sender's table, t1..t65 in reply
	//      is target node's table (after merge).
	//
	// ee = error indicator, non-zero (error) invalidates other fields.
	// nh:nl = number of bytes in payload
	// ah:al = address to load and execute code
	// c1..cn = executable coed
	// m1..mn = CP/NET standard message
	// dd = destination node ID
	// ss = sender node ID
	// id = node ID of this client
	// st = network status (0x10 = online)
	//
	// * "D6" will be received *before* reply (B0 or C0), and no reply
	// is expected if "ee" is not zero.
	//
	// Message "D0" is used internally to share network topology between
	// nodes. If a node receives this message from "the network", it should
	// echo back with it's node status updated (possibly merging other
	// known nodes). MAGNet is limited to 64 nodes, but other topologies
	// may allow 255 nodes ("255" is the broadcast ID). The node table
	// is expanded accordingly.

	// MAGNet message header format
	public static final int mtype  = 0; // index of MAGNet msg code in buffer
	public static final int mBC = 1; // index of MAGNet BC field in buffer
	public static final int mBCh = 2; // index of MAGNet BC field in buffer
	public static final int mDE = 3; // index of MAGNet DE field in buffer
	public static final int mDEh = 4; // index of MAGNet DE field in buffer
	public static final int mHL = 5; // index of MAGNet HL field in buffer
	public static final int mHLh = 6; // index of MAGNet HL field in buffer
	public static final int mpayload = 7; // index of MAGNet payload in buffer

	// MAGNet node types (t2..t65 of "D0" message)
	public static final int toffline = 0x00;
	public static final int tunknown = 0x10;
	public static final int tprinter = 0x30;
	public static final int tcpnos = 0x40;
	public static final int tfileserver = 0x70;
	public static final int tcpnet = 0x80;
	public static final int tcpm = 0x90;
	public static final int tmsdos = 0xa0;
	public static final int tcdos = 0xb0;

	// CP/NET message format
	public static final int mcode  = mpayload + 0; // CP/Net msg code in buffer
	public static final int mdid   = mpayload + 1; // CP/Net DID in buffer
	public static final int msid   = mpayload + 2; // CP/Net SID in buffer
	public static final int mfunc  = mpayload + 3; // CP/Net function code in buffer
	public static final int msize  = mpayload + 4; // CP/Net msg size in buffer
	public static final int mhdrlen = 5;
	public static final int mstart = mpayload + mhdrlen; // CP/Net msg start in buffer

	byte[] checkRecvMsg(byte clientId);
	byte[] sendMsg(byte[] msgbuf, int len);
	void shutdown();
}
