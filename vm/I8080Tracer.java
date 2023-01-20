// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.util.Properties;
import z80core.*;

class I8080Tracer extends CPUTracer {
	private I8080 cpu;
	private byte[] mem;
	private Z80Disassembler disas;
	private String traceStr;

	public I8080Tracer(Properties props, CPU cpu, byte[] mem, String args) {
		super(props, args);
		this.cpu = (I8080)cpu;
		this.mem = mem;
		disas = new I8080Disassembler(mem);
	}

	// This should be part of CPU...
	private String getFlags() {
		int f = cpu.getRegAF() & 0xff;
		return String.format("%s%s%s%s%s%s%s%s",
			(f & 0x80) == 0 ? "s" : "S",
			(f & 0x40) == 0 ? "z" : "Z",
			(f & 0x20) == 0 ? "." : "5",
			(f & 0x10) == 0 ? "h" : "H",
			(f & 0x08) == 0 ? "." : "3",
			(f & 0x04) == 0 ? "p" : "P",
			(f & 0x02) == 0 ? "." : "1",
			(f & 0x01) == 0 ? "c" : "C");
	}

	// before cpu.execute()...
	public boolean preTrace(int pc, long clk) {
		if (!shouldTrace(pc)) {
			return false;
		}
		// No interrupt state (etc) in this machine.
		traceStr = String.format("{%05d} %04x: %02x %02x %02x %02x " +
				": %s %02x %04x %04x %04x [%04x] {%%d} %s\n",
			clk & 0xffff,
			pc, mem[pc], mem[pc + 1], mem[pc + 2], mem[pc + 3],
			getFlags(),
			cpu.getRegA(),
			cpu.getRegBC(),
			cpu.getRegDE(),
			cpu.getRegHL(),
			cpu.getRegSP(),
			disas.disas(pc));
		// TODO: keep 'tracing' state ourselves?
		return true;
	}
	// after cpu.execute()... only called if preTrace() was true?
	public void postTrace(int pc, int cy) {
		System.err.format(traceStr, cy);
		didTrace(pc, cy);
	}
}

