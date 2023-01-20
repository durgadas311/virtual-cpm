// Copyright (c) 2023 Douglas Miller <durgadas311@gmail.com>

// Uses the framework of Alberto Sánchez Terrén Z80 simulator.

public class I8080Disassembler implements Z80Disassembler {
	Memory mem;
	boolean rom;
	int bnk;
	int lastLen;

	public I8080Disassembler(Memory mem) {
		this.mem = mem;
	}

	private int read8(int adr) {
		++lastLen;
		if (bnk < 0) {
			return mem.read(adr & 0xffff);
		} else {
			return mem.read(rom, bnk, adr & 0xffff);
		}
	}

	private int read16(int adr) {
		int w;
		// little endian...
		if (bnk < 0) {
			w = mem.read(adr & 0xffff);
			++adr;
			w |= (mem.read(adr & 0xffff) << 8);
		} else {
			w = mem.read(rom, bnk, adr & 0xffff);
			++adr;
			w |= (mem.read(rom, bnk, adr & 0xffff) << 8);
		}
		lastLen += 2;
		return w;
	}

	private int relAdr(int adr) {
		byte d;
		if (bnk < 0) {
			d = (byte)mem.read(adr & 0xffff);
		} else {
			d = (byte)mem.read(rom, bnk, adr & 0xffff);
		}
		++adr;
		++lastLen;
		return adr + d;
	}

	private static final char[] regs = new char[] {
		'b', 'c', 'd', 'e', 'h', 'l', 'm', 'a' };

	private static final String[] ops = new String[] {
		"add", "adc", "sub", "sbb", "ana", "xra", "ora", "cmp" };

	public int instrLen() { return lastLen; }

	public String disas(int pc) {
		return disas(false, -1, pc);
	}

	public String disas(boolean rom, int bnk, int pc) {
		String instr = "";
		lastLen = 0;
		this.rom = rom;
		this.bnk = bnk;
		int opCode = read8(pc++);
		if ((opCode & 0xc0) == 0x40) {
			if (opCode == 0x76) {
				instr = "hlt";
			} else {
				instr = String.format("mov %c,%c",
					regs[(opCode >> 3) & 7], regs[opCode & 7]);
			}
		} else if ((opCode & 0xc0) == 0x80) {
			instr = String.format("%s %c",
					ops[(opCode >> 3) & 7], regs[opCode & 7]);
		} else switch (opCode) {
			case 0x00:       /* NOP */
				instr = "nop";
				break;
			case 0x01:       /* LD BC,nn */
				instr = String.format("lxi b,%04x", read16(pc));
				break;
			case 0x02:       /* LD (BC),A */
				instr = "stax b";
				break;
			case 0x03:       /* INC BC */
				instr = "inx b";
				break;
			case 0x04:       /* INC B */
				instr = "inr b";
				break;
			case 0x05:       /* DEC B */
				instr = "dcr b";
				break;
			case 0x06:       /* LD B,n */
				instr = String.format("mvi b,%02x", read8(pc));
				break;
			case 0x07:       /* RLCA */
				instr = "rlc";
				break;
			case 0x08:        /* EX AF,AF' */
				instr = "*nop";
				break;
			case 0x09:       /* ADD HL,BC */
				instr = "dad b";
				break;
			case 0x0A:       /* LD A,(BC) */
				instr = "ldax b";
				break;
			case 0x0B:       /* DEC BC */
				instr = "dcx b";
				break;
			case 0x0C:       /* INC C */
				instr = "inr c";
				break;
			case 0x0D:       /* DEC C */
				instr = "dcr c";
				break;
			case 0x0E:       /* LD C,n */
				instr = String.format("mvi c,%02x", read8(pc));
				break;
			case 0x0F:       /* RRCA */
				instr = "rrc";
				break;
			case 0x10:       /* DJNZ e */
				instr = "*nop";
				break;
			case 0x11:       /* LD DE,nn */
				instr = String.format("lxi d,%04x", read16(pc));
				break;
			case 0x12:       /* LD (DE),A */
				instr = "stax d";
				break;
			case 0x13:       /* INC DE */
				instr = "inx d";
				break;
			case 0x14:       /* INC D */
				instr = "inr d";
				break;
			case 0x15:       /* DEC D */
				instr = "dcr d";
				break;
			case 0x16:       /* LD D,n */
				instr = String.format("mvi d,%02x", read8(pc));
				break;
			case 0x17:       /* RLA */
				instr = "ral";
				break;
			case 0x18:       /* JR e */
				instr = "*nop";
				break;
			case 0x19:       /* ADD HL,DE */
				instr = "dad d";
				break;
			case 0x1A:       /* LD A,(DE) */
				instr = "ldax d";
				break;
			case 0x1B:       /* DEC DE */
				instr = "dcx d";
				break;
			case 0x1C:       /* INC E */
				instr = "inr e";
				break;
			case 0x1D:       /* DEC E */
				instr = "dcr e";
				break;
			case 0x1E:       /* LD E,n */
				instr = String.format("mvi e,%02x", read8(pc));
				break;
			case 0x1F:       /* RRA */
				instr = "rar";
				break;
			case 0x20:       /* JR NZ,e */
				instr = "*nop";
				break;
			case 0x21:       /* LD HL,nn */
				instr = String.format("lxi h,%04x", read16(pc));
				break;
			case 0x22:       /* LD (nn),HL */
				instr = String.format("shld %04x", read16(pc));
				break;
			case 0x23:       /* INC HL */
				instr = "inx h";
				break;
			case 0x24:       /* INC H */
				instr = "inr h";
				break;
			case 0x25:       /* DEC H */
				instr = "dcr h";
				break;
			case 0x26:       /* LD H,n */
				instr = String.format("mvi h,%02x", read8(pc));
				break;
			case 0x27:       /* DAA */
				instr = "daa";
				break;
			case 0x28:       /* JR Z,e */
				instr = "*nop";
				break;
			case 0x29:       /* ADD HL,HL */
				instr = "dad h";
				break;
			case 0x2A:       /* LD HL,(nn) */
				instr = String.format("lhld %04x", read16(pc));
				break;
			case 0x2B:       /* DEC HL */
				instr = "dcx h";
				break;
			case 0x2C:       /* INC L */
				instr = "inr l";
				break;
			case 0x2D:       /* DEC L */
				instr = "dcr l";
				break;
			case 0x2E:       /* LD L,n */
				instr = String.format("mvi l,%02x", read8(pc));
				break;
			case 0x2F:       /* CPL */
				instr = "cma";
				break;
			case 0x30:       /* JR NC,e */
				instr = "*nop";
				break;
			case 0x31:       /* LD SP,nn */
				instr = String.format("lxi sp,%04x", read16(pc));
				break;
			case 0x32:       /* LD (nn),A */
				instr = String.format("sta %04x", read16(pc));
				break;
			case 0x33:       /* INC SP */
				instr = "inx sp";
				break;
			case 0x34:       /* INC (HL) */
				instr = "inr m";
				break;
			case 0x35:       /* DEC (HL) */
				instr = "dcr m";
				break;
			case 0x36:       /* LD (HL),n */
				instr = String.format("mvi m,%02x", read8(pc));
				break;
			case 0x37:       /* SCF */
				instr = "stc";
				break;
			case 0x38:       /* JR C,e */
				instr = "*nop";
				break;
			case 0x39:       /* ADD HL,SP */
				instr = "dad sp";
				break;
			case 0x3A:       /* LD A,(nn) */
				instr = String.format("lda %04x", read16(pc));
				break;
			case 0x3B:       /* DEC SP */
				instr = "dcx sp";
				break;
			case 0x3C:       /* INC A */
				instr = "inr a";
				break;
			case 0x3D:       /* DEC A */
				instr = "dcr a";
				break;
			case 0x3E:       /* LD A,n */
				instr = String.format("mvi a,%02x", read8(pc));
				break;
			case 0x3F:       /* CCF */
				instr = "cmc";
				break;
			// 0x40 - 0x7f handled above...
			// 0x80 - 0xbf handled above...
			case 0xC0:       /* RET NZ */
				instr = "rnz";
				break;
			case 0xC1:       /* POP BC */
				instr = "pop b";
				break;
			case 0xC2:       /* JP NZ,nn */
				instr = String.format("jnz %04x", read16(pc));
				break;
			case 0xC3:       /* JP nn */
				instr = String.format("jmp %04x", read16(pc));
				break;
			case 0xC4:       /* CALL NZ,nn */
				instr = String.format("cnz %04x", read16(pc));
				break;
			case 0xC5:       /* PUSH BC */
				instr = "push b";
				break;
			case 0xC6:       /* ADD A,n */
				instr = String.format("adi %02x", read8(pc));
				break;
			case 0xC7:       /* RST 00H */
				instr = "rst 0";
				break;
			case 0xC8:       /* RET Z */
				instr = "rz";
				break;
			case 0xC9:       /* RET */
				instr = "ret";
				break;
			case 0xCA:       /* JP Z,nn */
				instr = String.format("jz %04x", read16(pc));
				break;
			case 0xCB:  
				instr = String.format("*jmp %04x", read16(pc));
				break;
			case 0xCC:       /* CALL Z,nn */
				instr = String.format("cz %04x", read16(pc));
				break;
			case 0xCD:       /* CALL nn */
				instr = String.format("call %04x", read16(pc));
				break;
			case 0xCE:       /* ADC A,n */
				instr = String.format("aci %02x", read8(pc));
				break;
			case 0xCF:       /* RST 08H */
				instr = "rst 1";
				break;
			case 0xD0:       /* RET NC */
				instr = "rnc";
				break;
			case 0xD1:       /* POP DE */
				instr = "pop d";
				break;
			case 0xD2:       /* JP NC,nn */
				instr = String.format("jnc %04x", read16(pc));
				break;
			case 0xD3:       /* OUT (n),A */
				instr = String.format("out %02x", read8(pc));
				break;
			case 0xD4:       /* CALL NC,nn */
				instr = String.format("cnc %04x", read16(pc));
				break;
			case 0xD5:       /* PUSH DE */
				instr = "push d";
				break;
			case 0xD6:       /* SUB n */
				instr = String.format("sui %02x", read8(pc));
				break;
			case 0xD7:       /* RST 10H */
				instr = "rst 2";
				break;
			case 0xD8:       /* RET C */
				instr = "rc";
				break;
			case 0xD9:       /* EXX */
				instr = "*ret";
				break;
			case 0xDA:       /* JP C,nn */
				instr = String.format("jc %04x", read16(pc));
				break;
			case 0xDB:       /* IN A,(n) */
				instr = String.format("in %02x", read8(pc));
				break;
			case 0xDC:       /* CALL C,nn */
				instr = String.format("cc %04x", read16(pc));
				break;
			case 0xDD:
				instr = String.format("*call %04x", read16(pc));
				break;
			case 0xDE:       /* SBC A,n */
				instr = String.format("sbi %02x", read8(pc));
				break;
			case 0xDF:       /* RST 18H */
				instr = "rst 3";
				break;
			case 0xE0:       /* RET PO */
				instr = "rpo";
				break;
			case 0xE1:       /* POP HL */
				instr = "pop h";
				break;
			case 0xE2:       /* JP PO,nn */
				instr = String.format("jpo %04x", read16(pc));
				break;
			case 0xE3:       /* EX (SP),HL */
				instr = "xthl";
				break;
			case 0xE4:       /* CALL PO,nn */
				instr = String.format("cpo %04x", read16(pc));
				break;
			case 0xE5:       /* PUSH HL */
				instr = "push h";
				break;
			case 0xE6:       /* AND n */
				instr = String.format("ani %02x", read8(pc));
				break;
			case 0xE7:       /* RST 20H */
				instr = "rst 4";
				break;
			case 0xE8:       /* RET PE */
				instr = "rpe";
				break;
			case 0xE9:       /* JP (HL) */
				instr = "pchl";
				break;
			case 0xEA:       /* JP PE,nn */
				instr = String.format("jpe %04x", read16(pc));
				break;
			case 0xEB:       /* EX DE,HL */
				instr = "xchg";
				break;
			case 0xEC:       /* CALL PE,nn */
				instr = String.format("cpe %04x", read16(pc));
				break;
			case 0xED:
				instr = String.format("*call %04x", read16(pc));
				break;
			case 0xEE:       /* XOR n */
				instr = String.format("xri %02x", read8(pc));
				break;
			case 0xEF:       /* RST 28H */
				instr = "rst 5";
				break;
			case 0xF0:       /* RET P */
				instr = "rp";
				break;
			case 0xF1:       /* POP AF */
				instr = "pop psw";
				break;
			case 0xF2:       /* JP P,nn */
				instr = String.format("jp %04x", read16(pc));
				break;
			case 0xF3:       /* DI */
				instr = "di";
				break;
			case 0xF4:       /* CALL P,nn */
				instr = String.format("cp %04x", read16(pc));
				break;
			case 0xF5:       /* PUSH AF */
				instr = "push psw";
				break;
			case 0xF6:       /* OR n */
				instr = String.format("ori %02x", read8(pc));
				break;
			case 0xF7:       /* RST 30H */
				instr = "rst 6";
				break;
			case 0xF8:       /* RET M */
				instr = "rm";
				break;
			case 0xF9:       /* LD SP,HL */
				instr = "sphl";
				break;
			case 0xFA:       /* JP M,nn */
				instr = String.format("jm %04x", read16(pc));
				break;
			case 0xFB:       /* EI */
				instr = "ei";
				break;
			case 0xFC:       /* CALL M,nn */
				instr = String.format("cm %04x", read16(pc));
				break;
			case 0xFD:
				instr = String.format("*call %04x", read16(pc));
				break;
			case 0xFE:       /* CP n */
				instr = String.format("cpi %02x", read8(pc));
				break;
			case 0xFF:       /* RST 38H */
				instr = "rst 7";
				break;
		}
		return instr;
	}
}
