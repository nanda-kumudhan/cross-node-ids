# Linux C/eBPF agent

Implemented event types: successful process exec and successful **blocking IPv4
TCP** connect/accept. Target: Linux x86-64 with BTF, ring-buffer support and the
named hooks. Build success is not runtime/kernel compatibility verification.

```sh
# Ubuntu 24.04 build prerequisites; install inside a test VM/build container.
sudo apt-get install clang make gcc pkg-config libbpf-dev libc6-dev linux-libc-dev
make agent-build
make test-agent

# No kernel privileges: synthetic serialization test only.
agent/bin/ids-agent --node fixture --self-test

# Native collection on an isolated VM, from the repository root:
sudo agent/bin/ids-agent --node node-a --duration 30 \
  --output deploy/logs/node-a.jsonl
.venv/bin/python agent/tools/check_gaps.py deploy/logs/node-a.jsonl
```

Stop with SIGINT/SIGTERM or `--duration`. `--object` selects the compiled BPF
object; `--ring-bytes` selects a power-of-two ring size between 4 KiB and 64 MiB.
An unwritable output or failed BPF operation exits nonzero. Output is append-only;
do not run two loaders with the same node identity during a measurement.

## Hooks and identity

- `sched_process_exec` runs after successful exec. It records the filename as
  exposed by that tracepoint, up to 255 bytes plus terminator. Relative paths,
  symlinks, scripts and fileless execution limit the location rule; this is not
  a canonical executable resolver. Truncation is explicit.
- `tcp_v4_connect` saves a socket by thread identity. Its return alone is not
  treated as success. `sys_exit_connect` emits only for syscall result zero.
- `inet_csk_accept` return saves the accepted socket. `sys_exit_accept` and
  `sys_exit_accept4` emit only for a successful userspace return.
- Failed/nonblocking connects, IPv6, io_uring and sockets created outside these
  paths are excluded. The Go connect workload uses nonblocking networking;
  use `attacks/demo.py` for the blocking cross-VM fixture instead.
- Pending bookkeeping has 8192 entries, syscall-exit/thread-exit cleanup and
  explicit update/read failure counters. In-flight operations at detach are
  outside the completed-event coverage; this is not universal syscall capture.

Process identity uses node, kernel boot ID, TGID and the group leader's monotonic
start time. Boot ID and a fresh random run ID are read from procfs. No command
arguments are collected. Path bytes outside printable ASCII are escaped as
U+00xx byte values to keep JSON valid; this is not Unicode path normalization.

`src_*` always means client and `dst_*` server, on both connect and accept. Local
ports are host order; remote socket ports are converted from network order.
Direct VM addresses are required; NAT, namespaces and proxies are outside MVP.

## Counters, timestamps and shutdown

Failed ring reservations increment per-CPU `kernel_drops`; explicit capture/
bookkeeping failures increment `capture_errors`. Userspace sums maps each second.
These maps are created fresh per loader run and are not pinned. Each reader
callback assigns a sequence; heartbeats consume a sequence too. Startup/one-second
heartbeats and the clean final heartbeat carry their own sequence as high-watermark.
`ready=true` appears only after every program attached successfully.

Shutdown detaches hooks, drains the ring and emits a final heartbeat. An output
failure/crash or missing final heartbeat leaves tail uncertainty. Syscall hooks
are not attached atomically: operations crossing startup/detach boundaries can
be missed and are outside benchmark workload intervals.

The loader samples REALTIME between two MONOTONIC readings at startup and every
heartbeat, converts event monotonic time using the sampled midpoint offset, and
records sampling uncertainty. This is not chrony/network synchronization error.
Wall-clock steps can move event timestamps and affect windows; keep hosts synced
and retain chrony readings. `userspace_delay_ns` is a same-host monotonic interval,
so it avoids cross-host wall-clock subtraction.

## Docker and attribution

`agent/Dockerfile` builds an amd64 image; CI publishes it alongside engine/UI.
The Compose agent profile requests BPF/PERFMON, host PID/network namespaces and
read-only BTF/tracefs. Mount tracefs on the host first. Docker seccomp/LSM or
kernel policy may still reject loading/attachment: validate explicitly on your
test VM. There is no automatic privileged-container fallback.

The implementation uses the ring-buffer/CO-RE pattern demonstrated by
[libbpf-bootstrap](https://github.com/libbpf/libbpf-bootstrap). The minimal named
kernel views in `src/kernel_types.h` are relocated by CO-RE; they are not copied
kernel layouts. The reserved `libbpf-bootstrap/` directory is unused: no upstream
source is vendored or claimed as student-authored. A native runtime test is
required to validate these views, tracepoint availability and the BPF verifier.
