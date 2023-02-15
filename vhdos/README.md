## vhdos - HDOS emulator

Currently, reports version as 2.0 and is only expected to work for
programs compatible with HDOS 2.0.

The only built-in command currently is DIR. Only implemented for whole-disk
(does not yet support filename wildcard expressions).

Disk mounting/dismounting is not required, associated SCALLs are
ignored and always return success.

JAR file is in bin/VirtualHdos.jar.
Documentation is in doc/VirtualHdos.pdf.

### EXAMPLES

demo: Assemble and run Heath "demo" program using a Makefile.
      Uses standard HDOS 2.0 assembler.
      [example run](demo/demo-log.txt)

c-demo: Compile and run classic "hello world" C program using a Makefile.
        Uses SWTW C/80, Microsoft M80/L80.
        [example run](c-demo/hello-log.txt)
