# Test/Example Makefile for using vhdos with SWTW C/80 and M80/L80
# HDOS utils (asm.abs) should be in "sy0",
# and (at least) hdos.acm in "sy1".

.PRECIOUS: %.mac %.rel

export HDOSDrive_dk0 = $(PWD)

all: hello.abs

clobber:
	rm -f *.abs *.mac *.rel

%.mac: %.c
	vhdos c -m1 dk0:$*

%.rel: %.mac
	vhdos m80 =dk0:$*

%.abs: %.rel
	vhdos l80 dk0:$*,sy0:clibrary/s,dk0:$*/n/e

test: hello.abs
	vhdos dk0:hello
