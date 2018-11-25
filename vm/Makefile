CLASS_PATH = .:z80cpu/z80core.jar

JAVAS = $(filter-out Cpnet%Server.java,$(wildcard *.java))
CLASSES = $(subst .java,.class,$(JAVAS))
SOURCE = vcpm.tgz
JARS = VirtualCpm.jar
SHIPS = $(SOURCE) $(JARS)

all: z80cpu/z80core.jar $(CLASSES)

%.class: %.java
	javac -cp $(CLASS_PATH) $?

jar: $(JARS)

src: $(SOURCE)
# make -C z80cpu src

VirtualCpm.jar: all temp
	echo "Main-Class: VirtualCpm" >Manifest.txt
	jar -cmf Manifest.txt VirtualCpm.jar *.class \
		-C temp .

# NOTE: H89plugin.jar and Diablo630Embedded.jar both have SuffFile*.class,
# but currently identical versions.
temp: __FRC__
	mkdir -p temp
	rm -rf temp/*
	cd temp; jar -xf ../z80cpu/z80core.jar
	rm -rf temp/META-INF

z80cpu/z80core.jar:
	make -C z80cpu z80core.jar

vcpm.tgz: __FRC__
	cd ..; tar -czf vm/vcpm.tgz vm/Makefile \
		vm/LICENSE vm/README vm/INSTALL \
		vm/*.java

__FRC__:
