CLASS_PATH = .:z80cpu/z80core.jar:z80cpu/z80debug.jar

JAVAS = $(wildcard *.java)
CLASSES = $(subst .java,.class,$(JAVAS))
JARS = VirtualHdos.jar
SHIPS = $(SOURCE) $(JARS)

all: z80cpu/z80core.jar $(CLASSES)

%.class: %.java
	javac -cp $(CLASS_PATH) $?

jar: $(JARS)

VirtualHdos.jar: all temp
	echo "Main-Class: VirtualHdos" >Manifest.txt
	jar -cmf Manifest.txt VirtualHdos.jar *.class \
		*.sys *.rom \
		-C temp .

temp: __FRC__
	mkdir -p temp
	rm -rf temp/*
	cd temp; jar -xf ../z80cpu/z80core.jar
	rm -rf temp/META-INF
	cd temp; jar -xf ../z80cpu/z80debug.jar
	rm -rf temp/META-INF

z80cpu/z80core.jar:
	make -C z80cpu z80core.jar

z80cpu/z80debug.jar:
	make -C z80cpu z80debug.jar

vhdos.tgz: __FRC__
	cd ..; tar -czf vm/vhdos.tgz vhdos/Makefile \
		vhdos/LICENSE vhdos/README vhdos/INSTALL \
		vhdos/*.java

ship:
	rsync -Wuv VirtualHdos.jar ../bin

__FRC__:
