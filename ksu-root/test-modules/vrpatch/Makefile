obj-m := vrpatch.o
KDIR := /root/kernel/source
PWD := $(shell pwd)

all:
	$(MAKE) ARCH=arm64 LLVM=1 LLVM_IAS=1 -C $(KDIR) M=$(PWD) modules

clean:
	$(MAKE) ARCH=arm64 LLVM=1 LLVM_IAS=1 -C $(KDIR) M=$(PWD) clean
