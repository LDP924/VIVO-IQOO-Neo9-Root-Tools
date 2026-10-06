obj-m := unpatch.o
UNPATCH_SRC := /root/kernel/unpatch
KDIR := /root/kernel/source

all:
	$(MAKE) -C $(KDIR) M=$(UNPATCH_SRC) modules

clean:
	$(MAKE) -C $(KDIR) M=$(UNPATCH_SRC) clean
