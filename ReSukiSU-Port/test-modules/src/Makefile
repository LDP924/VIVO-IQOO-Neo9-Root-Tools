obj-m := ksu_testmod.o puretest.o proctest.o
KSU_TESTMOD_SRC := /root/kernel/testmod
KDIR := /root/kernel/source

all:
	$(MAKE) -C $(KDIR) M=$(KSU_TESTMOD_SRC) modules

clean:
	$(MAKE) -C $(KDIR) M=$(KSU_TESTMOD_SRC) clean
