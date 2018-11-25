// Copyright (c) 2017 Douglas Miller <durgadas311@gmail.com>

public interface NetworkListener {
	// These are called from the socket thread.
	// Listener must return immediately, and must
	// not try to send/receive from this callback.
	void addNode(int nodeId, int type);
	void dropNode(int nodeId);
}
