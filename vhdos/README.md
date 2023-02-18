## vhdos - HDOS emulator

Default version is 2.0, and 3.0 may be selected through
configuration options.

The only built-in commands currently provided are DIR and TYPE.
syscmd.sys may run, but generally depends on specific memory contents.
PIP versions of the commands should work, like (DIR) "PIP /LIST" or
(TYPE) "PIP TT0:=file". Note that directory listings from PIP will
not show correct files sizes, or disk usage.

Disk mounting/dismounting is not required, associated SCALLs are
ignored and always return success.

JAR file is in [bin/VirtualHdos.jar](../bin/VirtualHdos.jar).
Documentation is in [doc/VirtualHdos.pdf](../doc/VirtualHdos.pdf).

### EXAMPLES

demo: Assemble and run Heath "demo" program using a Makefile.
      Uses standard HDOS 2.0 assembler.
      [example run](demo/demo-log.txt)

c-demo: Compile and run classic "hello world" C program using a Makefile.
        Uses SWTW C/80, Microsoft M80/L80.
        [example run](c-demo/hello-log.txt)
