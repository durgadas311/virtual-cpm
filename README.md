# generic-cpm a.k.a. VirtualCpm

This project is (yet another) CP/M emulator, written in JAVA.
It should run on any platform that has a JAVA Runtime Environment.
It uses a Z80 emulation with 64K of RAM.
The emulated BDOS resembles a CP/M 3 BDOS. The emulated CCP has features of CP/M 3 also.

See also [documentation](doc/VirtualCpm.pdf).
JAR file is [here](bin/VirtualCpm.jar).

Note that it should not ordinarily be necessary to rebuild
this project. The existing JAR file "bin/VirtualCpm.jar"
should be up-to-date and may be used as-is.

There also is an [HDOS equivalent](vhdos/README.md).

Building this project requires another project
[z80cpu](https://github.com/durgadas311/z80cpu).
The minimum required is to create a subdirectory
"vm/z80cpu" and copy "z80core.jar" into it.
Alternatively, the entire z80cpu github repo may be
cloned into the "vm" directory.
Or, a symlink may be created in "vm" that points to the
"z80cpu" clone.
