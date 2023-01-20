// Copyright 2023 Douglas Miller <durgadas311@gmail.com>

import java.util.Properties;
import z80core.*;

class Z80Tracer extends CPUTracer {
	private Z80 cpu;
	private Memory mem;
	private Z80Disassembler disas;
	private String traceStr;

	public Z80Tracer(Properties props, CPU cpu, Memory mem, String args) {
		super(props, args);
		this.cpu = (Z80)cpu;
		this.mem = mem;
		// TODO: prefix property with "vcpm_"?
		String s = props.getProperty("disas");
		if (s != null && s.equalsIgnoreCase("zilog")) {
			disas = new Z80DisassemblerZilog(mem);
		} else {
			disas = new Z80DisassemblerMAC80(mem);
		}
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
			(f & 0x04) == 0 ? "x" : "X",
			(f & 0x02) == 0 ? "n" : "N",
			(f & 0x01) == 0 ? "c" : "C");
	}

	// before cpu.execute()...
	public boolean preTrace(int pc, long clk) {
		if (!shouldTrace(pc)) {
			return false;
		}
		// No interrupt state (etc) in this machine.
		traceStr = String.format("{%05d} %04x: %02x %02x %02x %02x " +
				": %s %02x %04x %04x %04x %04x %04x [%04x] {%%d} %s\n",
			clk & 0xffff,
			pc, mem.read(pc), mem.read(pc + 1),
			mem.read(pc + 2), mem.read(pc + 3),
			getFlags(),
			cpu.getRegA(),
			cpu.getRegBC(),
			cpu.getRegDE(),
			cpu.getRegHL(),
			cpu.getRegIX(),
			cpu.getRegIY(),
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

