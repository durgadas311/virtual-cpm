# Test/Example Makefile for using vhdos with SWTW C/80 and M80/L80
# HDOS utils (c.abs,m80.abs,l80.abs,clibrary.rel) should be in "sy0".

# This may also be overridden on the command, e.g.
#	make VHDOS=foobar
VHDOS = vhdos

.PRECIOUS: %.mac %.rel

export HDOSDrive_dk0 = $(PWD)

all: hello.abs

clobber:
	rm -f *.abs *.mac *.rel

%.mac: %.c
	$(VHDOS) c -m1 dk0:$*

%.rel: %.mac
	$(VHDOS) m80 =dk0:$*

%.abs: %.rel
	$(VHDOS) l80 dk0:$*,sy0:clibrary/s,dk0:$*/n/e

test: hello.abs
	$(VHDOS) dk0:hello
