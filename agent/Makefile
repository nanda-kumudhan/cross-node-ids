CC ?= cc
CLANG ?= clang
PYTHON ?= $(if $(wildcard ../.venv/bin/python),../.venv/bin/python,python3)
ARCH := $(shell uname -m)
CFLAGS := -std=gnu11 -O2 -g -Wall -Wextra -Werror
BPF_INCLUDES := -I/usr/include/$(shell $(CC) -dumpmachine)
.PHONY: all check
all: bin/ids-agent bin/ids.bpf.o
bin:
	mkdir -p bin
bin/ids-agent: src/ids.c src/events.h | bin
	$(CC) $(CFLAGS) src/ids.c -o $@ $$(pkg-config --cflags --libs libbpf)
bin/ids.bpf.o: src/ids.bpf.c src/events.h src/kernel_types.h | bin
	@test "$(ARCH)" = x86_64 || { echo 'MVP currently supports x86-64 Linux only'; exit 1; }
	$(CLANG) -O2 -g -target bpf -D__TARGET_ARCH_x86 -Wall -Werror $(BPF_INCLUDES) -c $< -o $@
check: all
	$(PYTHON) -m unittest discover -s tests
