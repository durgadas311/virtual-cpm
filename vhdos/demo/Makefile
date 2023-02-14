# Test/Example Makefile for using vhdos
# HDOS utils (asm.abs) should be in "sy0",
# and (at least) hdos.acm in "sy1".

ASM = asm

export HDOSDrive_sy2 = $(PWD)

all: demo.abs

clobber:
	rm -f *.abs

%.abs: %.asm
	vhdos $(ASM) sy2:$*,sy2:$*=sy2:$*,sy1:/err

test: demo.abs
	vhdos sy2:demo
